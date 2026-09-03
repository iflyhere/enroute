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

package de.akaflieg_freiburg.enroute.wear.data

import android.content.Context
import android.content.Intent
import de.akaflieg_freiburg.enroute.wear.Config

/**
 * Where to reach the phone, and the pairing code to present.
 *
 * Plain SharedPreferences rather than DataStore: three scalars need no coroutine
 * API, and it keeps the dependency list short, which matters for a project that has
 * to justify every library it links.
 *
 * The values can also be set from the command line, which is what makes testing
 * against a real phone bearable before the on-watch connection screen exists:
 *
 *     adb -s <serial> shell am start -n <pkg>/<activity> \
 *         --es host 192.168.1.42 --ei port 8973 --es code 418302
 *
 * Text entry on a watch is unpleasant enough that this stays useful afterwards.
 */
class SettingsStore(context: Context) {

    private val preferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var host: String
        get() = preferences.getString(KEY_HOST, Config.DEFAULT_HOST) ?: Config.DEFAULT_HOST
        set(value) = preferences.edit()
            .putString(KEY_HOST, value)
            .putBoolean(KEY_CONFIGURED, true)
            .apply()

    /**
     * Whether a phone has ever been chosen, by discovery, by hand or from a launch
     * intent.
     *
     * Its own stored flag rather than "host differs from the default", which was the
     * previous test and was wrong in the one configuration this app is developed in:
     * the default host is 127.0.0.1, so pointing the watch at the mock server through
     * adb reverse looked exactly like never having configured anything, and the session
     * never started.
     */
    val isConfigured: Boolean
        get() = preferences.getBoolean(KEY_CONFIGURED, false)

    var port: Int
        get() = preferences.getInt(KEY_PORT, Config.DEFAULT_PORT)
        set(value) = preferences.edit().putInt(KEY_PORT, value).apply()

    var pairingCode: String
        get() = preferences.getString(KEY_CODE, Config.DEFAULT_PAIRING_CODE) ?: Config.DEFAULT_PAIRING_CODE
        set(value) = preferences.edit().putString(KEY_CODE, value).apply()

    /** Applies any of host, port and code that the launch intent carries. */
    fun applyOverrides(intent: Intent?) {
        intent?.getStringExtra("host")?.let { host = it }
        intent?.getStringExtra("code")?.let { pairingCode = it }
        val overriddenPort = intent?.getIntExtra("port", 0) ?: 0
        if (overriddenPort > 0) {
            port = overriddenPort
        }
    }

    private companion object {
        const val NAME = "companion"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_CODE = "pairingCode"
        const val KEY_CONFIGURED = "configured"
    }
}
