/***************************************************************************
 *   Copyright (C) 2026 by Soeren Gutbrod                                  *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 3 of the License, or     *
 *   (at your option) any later version.                                   *
 *                                                                         *
 *   This program is distributed in the hope that it will be useful,       *
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of        *
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the         *
 *   GNU General Public License for more details.                          *
 *                                                                         *
 *   You should have received a copy of the GNU General Public License     *
 *   along with this program; if not, write to the                         *
 *   Free Software Foundation, Inc.,                                       *
 *   59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.             *
 ***************************************************************************/

package de.akaflieg_freiburg.enroute.wear.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import de.akaflieg_freiburg.enroute.wear.MainActivity
import de.akaflieg_freiburg.enroute.wear.R
import de.akaflieg_freiburg.enroute.wear.data.SessionHolder
import de.akaflieg_freiburg.enroute.wear.data.SettingsStore
import de.akaflieg_freiburg.enroute.wear.transport.http.HttpNavTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the link to the phone alive while the pilot is not looking at the watch.
 *
 * Three things have to be held for that, and each was observed failing without it.
 *
 * The foreground status keeps the process from being reaped once the activity stops.
 * Its type is deliberately `connectedDevice` and not `dataSync`: Android caps a
 * `dataSync` service at roughly six hours in twenty-four and then stops it, which on a
 * long cross-country would mean the watch quietly going dark in hour six. Talking to an
 * external device is also simply the honest description of what this does.
 *
 * The wake lock keeps the CPU available so the poll timer still fires with the display
 * off.
 *
 * The Wi-Fi lock is the one that matters most in practice. Wear OS powers its Wi-Fi
 * radio down aggressively whenever the watch is idle and a Bluetooth companion link is
 * available, and without this lock the link dropped repeatedly during development for
 * exactly that reason.
 */
class NavSessionService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    // Here rather than in a screen: a collision alarm has to reach the pilot whichever
    // page is open, and whether or not the display is on at all.
    private var alert: CollisionAlert? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Suppress("DEPRECATION")
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()
        acquireLocks()

        val settings = SettingsStore(this)
        SessionHolder.start {
            HttpNavTransport(
                host = settings.host,
                port = settings.port,
                pairingCode = settings.pairingCode,
            )
        }

        startAlarmWatch(settings)

        // Not sticky: if the system ever kills this, restarting it behind the pilot's
        // back would silently reopen a link they cannot see.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        alert = null
        SessionHolder.stop()
        releaseLocks()
        super.onDestroy()
    }

    /**
     * Buzzes the wrist when the phone's alarm level rises.
     *
     * Collected from the session rather than from a screen, and distinct from the
     * traffic list: what matters here is only the level, so this survives every change
     * to how traffic is displayed.
     */
    private fun startAlarmWatch(settings: SettingsStore) {
        if (alert != null) {
            return
        }
        val created = CollisionAlert(this)
        alert = created
        scope.launch {
            SessionHolder.state
                .map { state -> state.traffic?.warning?.alarmLevel ?: 0 }
                .distinctUntilChanged()
                .collect { level -> created.onAlarmLevel(level, settings.alarmVibration) }
        }
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.session_channel_name),
                    // Low, and silent: this notification exists because the platform
                    // requires one, not because the pilot needs to be told.
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.session_notification_title))
            .setContentText(getString(R.string.session_notification_text))
            .setSmallIcon(R.drawable.ic_session)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wakeLock == null) {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
                setReferenceCounted(false)
                acquire(MAX_SESSION_MS)
            }
        }
        if (wifiLock == null) {
            val wifi = getSystemService(Context.WIFI_SERVICE) as? WifiManager
            // WIFI_MODE_FULL_HIGH_PERF is deprecated and is still the only way to stop
            // Wear OS from powering the radio down while the display is off.
            wifiLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_TAG)?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        Log.i(TAG, "session locks held")
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.release() }
        runCatching { wifiLock?.release() }
        wakeLock = null
        wifiLock = null
    }

    companion object {
        const val ACTION_STOP = "de.akaflieg_freiburg.enroute.wear.STOP_SESSION"

        private const val TAG = "EnrouteWear"
        private const val CHANNEL_ID = "nav_session"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_TAG = "enroute:wear:navSession"
        private const val WIFI_TAG = "enroute:wear:wifi"

        // A timeout so a leaked lock cannot outlive any plausible flight. Long enough
        // that it never expires during one.
        private const val MAX_SESSION_MS = 12L * 60L * 60L * 1000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, NavSessionService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, NavSessionService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
