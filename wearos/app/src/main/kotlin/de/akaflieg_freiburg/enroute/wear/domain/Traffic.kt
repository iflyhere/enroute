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

package de.akaflieg_freiburg.enroute.wear.domain

/**
 * What the pilot's traffic receiver is reporting.
 *
 * [receiving] is the field that carries the most weight here. An empty [targets] list
 * means one of two completely different things -- nothing is flying nearby, or nothing
 * is listening -- and a display that shows an empty sky without saying which is lying
 * by omission. Everything on this screen is built around keeping those apart.
 *
 * No relevance judgement is added on this side. Each target carries the phone's own
 * alarm level and the phone's own colour for it.
 */
data class TrafficBoard(
    val revision: Long,
    /** Whether a receiver's heartbeat is reaching the phone. */
    val receiving: Boolean,
    /** The app's own sentence about the receiver's state. */
    val status: String?,
    val runtimeError: String?,
    val selfTestError: String?,
    val warning: TrafficWarning?,
    val targets: List<TrafficTarget>,
    /**
     * A target whose range the receiver knows but whose bearing it does not.
     *
     * Kept apart from [targets] rather than mixed in with a guessed bearing: FLARM
     * reports this often enough that dropping it would hide traffic, and drawing it
     * somewhere it might not be would be worse than not drawing it at all.
     */
    val withoutBearing: TrafficTarget?,
) {
    /**
     * Targets ordered for a list, most alarming first and then nearest.
     *
     * This ordering is the watch's own. The phone keeps its targets in a fixed pool
     * and draws them on a map, where order does not exist; a list has to choose one,
     * and alarm before distance is the only choice that puts the thing a pilot needs
     * to see at the top.
     */
    val listed: List<TrafficTarget>
        get() = targets.sortedWith(
            compareByDescending<TrafficTarget> { target -> target.alarmLevel }
                .thenBy { target -> target.horizontalDistanceM ?: Double.MAX_VALUE },
        )

    companion object {
        val EMPTY = TrafficBoard(
            revision = 0,
            receiving = false,
            status = null,
            runtimeError = null,
            selfTestError = null,
            warning = null,
            targets = emptyList(),
            withoutBearing = null,
        )
    }
}

data class TrafficWarning(
    val alarmLevel: Int,
    val alarmType: Int,
    val description: String?,
    val horizontalDistanceM: Double?,
    val verticalDistanceM: Double?,
)

data class TrafficTarget(
    /** The receiver's identifier. Stable between frames, so it keys a list. */
    val id: String?,
    val callSign: String?,
    val alarmLevel: Int,
    /** The app's own colour for that alarm level, parsed, or null if unusable. */
    val colour: Long?,
    val type: String?,
    val horizontalDistanceM: Double?,
    /** Positive above own aircraft, negative below. */
    val verticalDistanceM: Double?,
    /** The app's own composed line about this target. */
    val description: String?,
    val relevant: Boolean,
    /** Absent for a target whose bearing the receiver does not know. */
    val point: GeoPoint?,
    val trackDeg: Double?,
    val uncertaintyRadiusM: Double?,
) {
    /** What to call it on screen, in the order a pilot would recognise it. */
    val label: String get() = callSign ?: id ?: "?"
}
