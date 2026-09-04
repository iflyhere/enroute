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

package de.akaflieg_freiburg.enroute.wear.ui.instruments

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The arithmetic behind the round instruments, kept out of the drawing so it can be
 * tested.
 *
 * The dials here are read at a glance and a needle in the wrong place is not obviously
 * wrong, which is the whole reason these are separate functions.
 */

/** Which instrument the face is showing. */
enum class Instrument(val id: String, val title: String) {
    Altimeter("alt", "ALT"),
    Speed("gs", "GS"),
    Variometer("vario", "VS"),
    ;

    fun next(): Instrument = entries[(ordinal + 1) % entries.size]

    fun previous(): Instrument = entries[(ordinal + entries.size - 1) % entries.size]
}

/**
 * The needle angle for a dial that shows a value once per revolution.
 *
 * Zero at the top and clockwise, like every instrument in a cockpit. [perRevolution] is
 * how much of the quantity one full turn covers, so an altimeter's hundreds-hand and
 * its thousands-hand are the same function with different arguments.
 */
fun dialAngleDeg(value: Double, perRevolution: Double): Double {
    if (perRevolution == 0.0) {
        return 0.0
    }
    val turns = value / perRevolution
    return ((turns - kotlin.math.floor(turns)) * 360.0)
}

/**
 * The needle angle for a dial with a fixed span, clamped at both ends.
 *
 * Clamped rather than wrapped: a needle that has run off the end of the scale must stay
 * against the end, not reappear at the other side reading something completely
 * different.
 */
fun spanAngleDeg(
    value: Double,
    minValue: Double,
    maxValue: Double,
    startAngleDeg: Double,
    sweepDeg: Double,
): Double {
    if (maxValue <= minValue) {
        return startAngleDeg
    }
    val fraction = ((value - minValue) / (maxValue - minValue)).coerceIn(0.0, 1.0)
    return startAngleDeg + fraction * sweepDeg
}

/**
 * The scale for a speed dial, in the pilot's own unit.
 *
 * Chosen from a ladder rather than fitted, for the reason a fitted one fails: a dial
 * whose full-scale value changes as the aircraft accelerates is not a dial, it is a
 * number with decoration. Picked once from the aircraft's own speed and then kept until
 * it is exceeded.
 */
fun speedFullScale(currentSpeed: Double, unit: String): Double {
    val ladder = if (unit == "kmh") {
        listOf(120.0, 200.0, 300.0, 500.0)
    } else {
        listOf(60.0, 120.0, 200.0, 300.0)
    }
    return ladder.firstOrNull { step -> currentSpeed <= step * 0.95 } ?: ladder.last()
}

/**
 * A vertical speed rounded to what the source can actually support.
 *
 * The number comes from successive GPS fixes, not from a pressure capsule, so it is
 * good to a few tenths at best. Showing two decimals would be inventing precision that
 * would then wobble on screen and invite a pilot to fly it.
 */
fun roundedVerticalSpeed(value: Double): Double = (value * 10.0).roundToInt() / 10.0

/**
 * The digits an altimeter shows in its window, in the pilot's own unit.
 *
 * Rounded to ten feet or one metre. A GPS altitude is not better than that, and a
 * digit that flickers is a digit a pilot stops reading.
 */
fun altitudeDigits(altitude: Double, unit: String): Int =
    if (unit == "m") {
        altitude.roundToInt()
    } else {
        ((altitude / 10.0).roundToInt() * 10)
    }

/** Whether a value is worth drawing a needle for at all. */
fun isUsable(value: Double?): Boolean = value != null && value.isFinite() && abs(value) < 1.0e7
