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

package de.akaflieg_freiburg.enroute;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Keeps the companion link alive while this app is not in the foreground.
 *
 * Without it the link works only while a pilot is looking at the phone. The manifest
 * already sets background_running, so Qt's event loop survives the app being sent to the
 * background — but Doze and App Standby then throttle the network and the timers, and a
 * watch that was reading a navigation frame a second starts reading one every few
 * minutes and eventually none at all. A phone that has gone to sleep in a cockpit mount
 * is exactly the case this feature exists for.
 *
 * Deliberately a service of its own rather than a second job for FlightLogService. That
 * one is a location service started by the flight log, and its type says so; this one
 * neither reads a position nor belongs to the flight log, and joining them would mean
 * either the companion holding a location service open or the flight log claiming to be
 * a connected device.
 */
public class CompanionService extends Service {

    private static final String CHANNEL_ID = "companion_link";
    private static final int NOTIFICATION_ID = 4711;
    private static final String WAKE_TAG = "enroute:companion";

    private PowerManager.WakeLock wakeLock;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        startInForeground();
        acquireWakeLock();

        // Not sticky: if the system kills this, bringing it back behind the pilot's back
        // would silently reopen a link they can no longer see they have open.
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
        super.onDestroy();
    }

    private void acquireWakeLock() {
        if (wakeLock != null) {
            return;
        }
        PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (power == null) {
            return;
        }
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG);
        wakeLock.setReferenceCounted(false);

        // A timeout so a leaked lock cannot outlive any plausible flight. Long enough
        // that it never expires during one.
        wakeLock.acquire(12L * 60L * 60L * 1000L);
    }

    private void startInForeground() {
        Intent open = new Intent(this, MobileAdaptor.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
            this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.companion_notification_title))
            .setContentText(getString(R.string.companion_notification_text))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(contentIntent)
            .setOngoing(true);

        Notification notification = builder.build();

        // connectedDevice, never dataSync: the platform caps a dataSync foreground
        // service at roughly six hours in twenty-four and then stops it, which on a long
        // cross-country would mean the watch going dark mid-leg.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }

        // Low and silent: this notification exists because the platform requires one for
        // a foreground service, not because a pilot needs to be told.
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            getString(R.string.companion_channel_name),
            NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    /**
     * Start the service. Called from C++ via JNI when publishing is switched on.
     */
    public static void start(Context context) {
        // On Android 13 and later this permission defaults to denied, and without it the
        // notification is silently suppressed — which for a foreground service means the
        // pilot has no way to see that the link is open.
        MobileAdaptor.requestNotificationPermission();

        Intent intent = new Intent(context, CompanionService.class);
        context.startForegroundService(intent);
    }

    /**
     * Stop the service. Called from C++ via JNI when publishing is switched off.
     */
    public static void stop(Context context) {
        Intent intent = new Intent(context, CompanionService.class);
        context.stopService(intent);
    }
}
