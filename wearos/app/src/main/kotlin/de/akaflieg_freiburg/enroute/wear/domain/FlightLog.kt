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
 * The pilot's logbook, as the phone keeps it.
 *
 * Read only. A flight is started, ended and corrected on the phone, which owns the
 * record; nothing on the watch can change one. The watch is a window, and saying so
 * plainly is better than offering an edit that would have to be reconciled later.
 *
 * Only the most recent entries travel. [total] is the whole log, [entries] is what
 * arrived, and [dropped] is the difference -- carried so the list can say it is not
 * the whole logbook instead of quietly looking like it.
 */
data class FlightLogBoard(
    val revision: Long,
    val state: DetectionState,
    /** Whether the phone is recording a GPS track. */
    val recording: Boolean,
    val total: Int,
    val entries: List<FlightEntry>,
    val dropped: Int,
) {
    companion object {
        val EMPTY = FlightLogBoard(
            revision = 0,
            state = DetectionState.Idle,
            recording = false,
            total = 0,
            entries = emptyList(),
            dropped = 0,
        )
    }
}

/**
 * What the phone's flight detector currently believes.
 *
 * [Unknown] exists for the same reason every other wire enum here has one: a phone
 * that learns a new state must not break an older watch.
 */
enum class DetectionState {
    Idle, TakeoffPhase, InFlight, LandingPhase, Unknown;

    companion object {
        fun fromWire(code: String?): DetectionState = when (code) {
            "Idle" -> Idle
            "TakeoffPhase" -> TakeoffPhase
            "InFlight" -> InFlight
            "LandingPhase" -> LandingPhase
            else -> Unknown
        }
    }
}

/**
 * One logbook entry.
 *
 * Both durations are the phone's own H:MM strings rather than a count of seconds. They
 * are what a logbook column contains, and the phone leaves them empty when the recorded
 * times do not permit one -- a distinction worth carrying rather than rediscovering.
 */
data class FlightEntry(
    val id: String,
    val departure: String?,
    val arrival: String?,
    /** Epoch seconds, or null when the phone never recorded that moment. */
    val startEpochSeconds: Long?,
    val landingEpochSeconds: Long?,
    val offBlockEpochSeconds: Long?,
    val onBlockEpochSeconds: Long?,
    val flightTime: String?,
    val blockTime: String?,
    val callsign: String?,
    val landings: Int,
    val hasTrack: Boolean,
)
