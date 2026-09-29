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

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
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
 * A location service as well as a connected-device one. A link that stays up is worth
 * nothing if what travels over it stops changing: Android gives an app that is not on
 * screen no position at all unless a foreground service of type location runs, so with
 * the phone in a pocket the frames kept reaching the watch while the aircraft in them
 * stood still, and twenty seconds later the phone stopped sending a position at all.
 * That was found after a real flight, as a map that did not follow the aircraft.
 *
 * Deliberately a service of its own rather than a second job for FlightLogService. That
 * one belongs to the flight log, which starts and stops it and posts notifications of its
 * own through it; the companion has to run while the flight log is off, and it is the one
 * with a connected device to keep.
 */
public class CompanionService extends Service {

    private static final String CHANNEL_ID = "companion_link";
    private static final int NOTIFICATION_ID = 4711;
    private static final String WAKE_TAG = "enroute:companion";

    // Whether the service runs, and whether it runs with the location type. Written by
    // the service on the main thread and read by refresh() on Qt's, hence volatile.
    private static volatile boolean running = false;
    private static volatile boolean withLocation = false;

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
        running = true;

        // Not sticky: if the system kills this, bringing it back behind the pilot's back
        // would silently reopen a link they can no longer see they have open.
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        withLocation = false;
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
        //
        // location as well once the pilot has granted a location permission, and only
        // then: on Android 14 and later the location type without the permission throws
        // rather than degrading.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            boolean location = hasLocationPermission(this);
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            if (location) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            }
            try {
                startForeground(NOTIFICATION_ID, notification, type);
                withLocation = location;
            } catch (SecurityException e) {
                // Android 14 and later also refuse the location type to an app that is
                // not in the foreground at this moment, whatever it has been granted.
                // The link must not be lost over it: an app whose service was started as
                // a foreground service and never became one is killed by the platform.
                // refresh() asks again the next time the app is on screen.
                startForeground(NOTIFICATION_ID, notification,
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
                withLocation = false;
            }
        } else {
            // Before Android 10 a foreground service has no type, and any foreground
            // service keeps the positions coming.
            startForeground(NOTIFICATION_ID, notification);
            withLocation = true;
        }
    }

    private static boolean hasLocationPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                   == PackageManager.PERMISSION_GRANTED
               || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                   == PackageManager.PERMISSION_GRANTED;
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
     * Give a running service the location type if it started without it.
     *
     * Called from C++ via JNI whenever the app comes back to the foreground. The case it
     * exists for is a service that started while the permission dialog was still open,
     * as on the first start of an app whose settings came back from a backup with
     * publishing on. Without this that service would run without positions in the
     * background until the app was restarted, and nothing would say so. The dialog
     * closing brings the app back to the foreground, which is also the only moment
     * Android 14 and later let the type be taken.
     *
     * Not a grant made later in the system settings: after one of those the app itself
     * does not start reading positions again until it is restarted, service or not.
     *
     * Does nothing in every other case, so it is cheap to call on every return. Unlike
     * start() it does not ask for the notification permission: asked on every return,
     * that would be a dialog loop for a pilot who has said no.
     */
    public static void refresh(Context context) {
        if (!running || withLocation || !hasLocationPermission(context)) {
            return;
        }
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
