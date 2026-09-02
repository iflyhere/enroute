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

/** A geographic position. Degrees, as they arrive on the wire. */
data class GeoPoint(val latDeg: Double, val lonDeg: Double)

/**
 * Waypoint type, from the protocol's three-letter codes.
 *
 * [Unknown] exists so that a phone which learns a new code does not break an older
 * watch: an unrecognised type renders with the neutral marker instead of throwing.
 */
enum class WaypointType {
    Aerodrome, Navaid, Waypoint, Unknown;

    companion object {
        fun fromWire(code: String?): WaypointType = when (code) {
            "AD" -> Aerodrome
            "NAV" -> Navaid
            "WP" -> Waypoint
            else -> Unknown
        }
    }
}

data class RouteWaypoint(
    val index: Int,
    val name: String,
    val extendedName: String?,
    val point: GeoPoint,
    val type: WaypointType,
    val category: String?,
    val elevationM: Double?,
)

/** Connects waypoint [from] to waypoint [from] + 1. [trueCourseDeg] is absent on very short legs. */
data class RouteLeg(
    val from: Int,
    val distanceM: Double,
    val trueCourseDeg: Double?,
)

data class FlightRoute(
    val revision: Long,
    val name: String,
    val summary: String,
    val waypoints: List<RouteWaypoint>,
    val legs: List<RouteLeg>,
)
