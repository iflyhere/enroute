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

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Buzzes the wrist when the phone raises a collision alarm.
 *
 * On the wrist rather than on the screen, because the screen is the one place a pilot
 * is not looking when it matters. This lives in the session service and not in a
 * screen, so it fires whichever page is open and whether or not the display is on.
 *
 * The rule for when to buzz is deliberately conservative. A FLARM alarm level moves up
 * and down as the geometry changes, and buzzing on every frame of a level-two encounter
 * would make the watch shake continuously for half a minute -- which is worse than
 * useless, because it stops meaning anything. So: once when the level first rises, and
 * again only if it rises further.
 */
class CollisionAlert(context: Context) {

    private val vibrator: Vibrator? = resolveVibrator(context)

    private var lastLevel = 0

    /**
     * Called with every traffic frame.
     *
     * @param alarmLevel The phone's current alarm level, zero for none
     *
     * @param enabled Whether the pilot wants to be buzzed. Read per frame rather than
     * captured, so switching it off takes effect at once.
     */
    fun onAlarmLevel(alarmLevel: Int, enabled: Boolean) {
        val rising = shouldBuzz(previous = lastLevel, current = alarmLevel)
        lastLevel = alarmLevel
        if (!rising || !enabled) {
            return
        }
        buzz(alarmLevel)
    }

    /** Forgets the last level, so a reconnect can alarm again. */
    fun reset() {
        lastLevel = 0
    }

    private fun buzz(alarmLevel: Int) {
        val device = vibrator ?: return
        if (!device.hasVibrator()) {
            return
        }

        // Longer and more insistent as the level rises, so the wrist alone says how
        // urgent it is without the pilot looking at anything.
        val pattern = when {
            alarmLevel >= 3 -> longArrayOf(0, 260, 110, 260, 110, 260)
            alarmLevel == 2 -> longArrayOf(0, 220, 130, 220)
            else -> longArrayOf(0, 160)
        }
        val amplitudes = IntArray(pattern.size) { index ->
            if (index % 2 == 0) 0 else VibrationEffect.DEFAULT_AMPLITUDE
        }

        val effect = if (device.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(pattern, amplitudes, -1)
        } else {
            VibrationEffect.createWaveform(pattern, -1)
        }
        device.vibrate(effect)
    }

    private fun resolveVibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
}

/**
 * Whether a change in alarm level deserves a buzz.
 *
 * A free function so it can be tested without a device. The whole behaviour of the
 * alert is in this one line, and it is the part that is easy to get wrong.
 */
fun shouldBuzz(previous: Int, current: Int): Boolean = current > previous && current > 0
