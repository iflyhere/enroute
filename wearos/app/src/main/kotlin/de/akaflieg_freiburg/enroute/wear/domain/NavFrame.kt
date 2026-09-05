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
 * Route status, mirroring Navigation::RemainingRouteInfo::Status on the phone.
 *
 * The phone guarantees its next-waypoint values only while [OnRoute]. Anything else and
 * the watch must show placeholders rather than the last plausible-looking number: a
 * distance to a waypoint you have drifted away from is worse than no distance at all.
 *
 * [Unknown] covers a status string this build does not recognise, so that a newer phone
 * degrades an older watch to a neutral rendering instead of crashing it.
 */
enum class RouteStatus {
    NoRoute, PositionUnknown, OffRoute, NearDestination, OnRoute, Unknown;

    /** Whether the phone's next-waypoint and final-waypoint values may be displayed. */
    val hasLegData: Boolean get() = this == OnRoute

    companion object {
        fun fromWire(value: String?): RouteStatus = when (value) {
            "noRoute" -> NoRoute
            "positionUnknown" -> PositionUnknown
            "offRoute" -> OffRoute
            "nearDestination" -> NearDestination
            "onRoute" -> OnRoute
            else -> Unknown
        }
    }
}

enum class FlightStatus {
    Ground, Flight, Unknown;

    companion object {
        fun fromWire(value: String?): FlightStatus = when (value) {
            "ground" -> Ground
            "flight" -> Flight
            else -> Unknown
        }
    }
}

/**
 * A quantity that arrives both as an SI number and as the phone's own display string.
 *
 * [text] is what gets rendered; [si] is for arithmetic and for drawing. The watch never
 * formats [si] for display, because the phone already applied the pilot's unit
 * preferences, its rounding rules and its translated suffixes -- and two independent
 * implementations of those would eventually disagree, which is exactly what a
 * navigation instrument must not do.
 */
data class Measured(val si: Double?, val text: String) {
    val isKnown: Boolean get() = si != null

    companion object {
        val Absent = Measured(null, PLACEHOLDER)
        const val PLACEHOLDER = "-"
    }
}

/** Distance, time and course to a waypoint. */
data class WaypointLeg(
    val name: String,
    val distance: Measured,
    val eteSeconds: Long?,
    val eteText: String,
    val etaEpochSeconds: Long?,
    val etaText: String,
    val trueCourseDeg: Double?,
)

data class OwnPosition(
    val point: GeoPoint?,
    val altitudeAmsl: Measured,
    val altitudeAglM: Double?,
    val groundSpeed: Measured,
    val trackDeg: Double?,
    val verticalSpeedMps: Double?,
) {
    val hasFix: Boolean get() = point != null

    companion object {
        val Unknown = OwnPosition(null, Measured.Absent, null, Measured.Absent, null, null)
    }
}

/**
 * One navigation frame from the phone.
 *
 * [generatedAtEpochSeconds] is the phone's clock, and is the only sound basis for
 * showing how old the data is. Note that [etaEpochSeconds] on a leg must not be used for
 * a countdown, because the two clocks can differ -- count down from the ETE instead.
 */
data class NavFrame(
    val sessionId: Long,
    val navRevision: Long,
    val routeRevision: Long,
    val generatedAtEpochSeconds: Long,
    val status: RouteStatus,
    val flightStatus: FlightStatus,
    val note: String,
    val legIndex: Int?,
    val position: OwnPosition,
    val next: WaypointLeg?,
    val final: WaypointLeg?,
    val statusText: String,
    /**
     * Pressure altitude as a flight level, e.g. "FL065", or the placeholder.
     *
     * On the frame rather than on the position, because a barometer reads without a
     * satellite in sight and OwnPosition.Unknown would swallow it.
     */
    val flightLevel: Measured,
    /** True when the phone has a reading and does not believe it. */
    val flightLevelImplausible: Boolean,
    /**
     * The collision alarm level, zero when there is none.
     *
     * Read from here rather than from the traffic board: this arrives every second on
     * both transports, and the traffic document does not have to.
     */
    val alarmLevel: Int = 0,
)
