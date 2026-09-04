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
import de.akaflieg_freiburg.enroute.wear.data.Discovery
import de.akaflieg_freiburg.enroute.wear.data.DiscoveryEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import de.akaflieg_freiburg.enroute.wear.transport.NavTransport
import de.akaflieg_freiburg.enroute.wear.transport.ble.BleNavTransport
import de.akaflieg_freiburg.enroute.wear.transport.TransportMode

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
    private var addressWatch: Job? = null
    private var handoverWatch: Job? = null
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
        SessionHolder.start(transportProviderFor(settings))

        startAlarmWatch(settings)
        startAddressWatch(settings)
        startHandoverWatch(settings)

        // Not sticky: if the system ever kills this, restarting it behind the pilot's
        // back would silently reopen a link they cannot see.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        addressWatch = null
        handoverWatch = null
        alert = null
        SessionHolder.stop()
        releaseLocks()
        super.onDestroy()
    }

    /**
     * Builds the transport for each connection attempt.
     *
     * Called once per attempt by the repository, which is what makes "automatic" mean
     * something: it hands out Wi-Fi and Bluetooth in turn, so a link that cannot be
     * made one way is tried the other without the pilot touching a setting. Wi-Fi goes
     * first because it is much faster and carries whole documents rather than windows
     * of fragments.
     */
    private fun transportProviderFor(settings: SettingsStore): () -> NavTransport {
        var attempt = 0
        return {
            val wifi = {
                HttpNavTransport(
                    host = settings.host,
                    port = settings.port,
                    pairingCode = settings.pairingCode,
                )
            }
            val bluetooth = { BleNavTransport(this) }
            // Read per attempt rather than captured: a pilot who changes the setting
            // gets the new link on the next try even without a restart.
            val mode = TransportMode.byId(settings.transportMode)
            val chosen = if (mode.usesBluetooth(attempt)) bluetooth() else wifi()
            attempt += 1
            chosen
        }
    }

    /**
     * Takes the Wi-Fi address and the pairing code the phone states over Bluetooth.
     *
     * A watch that has only ever connected over Bluetooth knows no address, so the
     * automatic mode's Wi-Fi attempt would go to whatever the settings happened to hold
     * and fail every time. This is where that address comes from, and where an address
     * that went stale with a change of network is corrected -- neither needs the pilot
     * to type anything.
     *
     * Deliberately not restarting the session: the Bluetooth link that just delivered
     * this is working, and the faster one gets its turn on the next attempt anyway.
     */
    private fun startHandoverWatch(settings: SettingsStore) {
        if (handoverWatch?.isActive == true) {
            return
        }
        handoverWatch = scope.launch {
            SessionHolder.state
                .mapNotNull { state -> state.peer }
                .distinctUntilChanged()
                .collect { peer ->
                    peer.pairingCode
                        .takeIf { code -> code.isNotBlank() && code != settings.pairingCode }
                        ?.let { code ->
                            Log.i(TAG, "took the pairing code from the phone")
                            settings.pairingCode = code
                        }

                    val address = parseWifiUrl(peer.wifiUrl) ?: return@collect
                    if (!isHandoverWorthTaking(address, settings.host, settings.port)) {
                        return@collect
                    }
                    Log.i(TAG, "the phone is on Wi-Fi at " + address.host + ":" + address.port)
                    settings.host = address.host
                    settings.port = address.port
                }
        }
    }

    /**
     * Finds the phone again when its address stops answering.
     *
     * The address is stored, and a stored address belongs to whichever network it was
     * learned on. Outside that network the phone has a different one, and without this
     * the watch retries the old number for as long as the pilot lets it -- which is
     * exactly what happened on the first test away from the house.
     *
     * Only listens in bursts, and only while the link is actually failing. A broadcast
     * listen wakes the Wi-Fi radio, and Wear OS powers that radio down for good
     * reasons.
     */
    private fun startAddressWatch(settings: SettingsStore) {
        if (addressWatch?.isActive == true) {
            return
        }
        // The same construction the activity uses: the multicast lock inside needs
        // the Wi-Fi manager, or the chip filters the beacon before the socket sees it.
        val discovery = Discovery(getSystemService(Context.WIFI_SERVICE) as? WifiManager)
        addressWatch = scope.launch {
            SessionHolder.state
                .map { state -> shouldSearchForPhone(state.connection) }
                .distinctUntilChanged()
                .collect { searching ->
                    if (!searching) {
                        return@collect
                    }
                    val found = withTimeoutOrNull(SEARCH_WINDOW_MS) {
                        discovery.events()
                            .mapNotNull { event ->
                                (event as? DiscoveryEvent.Found)?.phone
                            }
                            .firstOrNull { phone ->
                                isWorthAdopting(phone, settings.host, settings.port)
                            }
                    } ?: return@collect

                    Log.i(TAG, "phone moved to " + found.host + ":" + found.port)
                    settings.host = found.host
                    settings.port = found.port

                    // Restarted rather than reconfigured in place: the transport is
                    // built once per connection attempt from the stored settings, so a
                    // restart is what makes the new address take effect.
                    SessionHolder.stop()
                    SessionHolder.start(transportProviderFor(settings))
                }
        }
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
