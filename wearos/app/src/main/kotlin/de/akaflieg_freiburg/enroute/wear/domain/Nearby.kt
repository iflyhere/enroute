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
 * What is around the aircraft, as the phone's own nearby page lists it.
 *
 * [positionKnown] is separate from an empty list for the usual reason: "nothing near
 * here" and "we do not know where here is" are different answers, and only one of them
 * means the pilot can stop looking.
 *
 * The three groups are the three the app offers, in the app's own order -- nearest
 * first, twenty of each.
 */
data class NearbyBoard(
    val revision: Long,
    val positionKnown: Boolean,
    val aerodromes: List<NearbyPlace>,
    val navaids: List<NearbyPlace>,
    val waypoints: List<NearbyPlace>,
) {
    companion object {
        val EMPTY = NearbyBoard(
            revision = 0,
            positionKnown = false,
            aerodromes = emptyList(),
            navaids = emptyList(),
            waypoints = emptyList(),
        )
    }
}

data class NearbyPlace(
    val name: String,
    val extendedName: String?,
    val point: GeoPoint?,
    val type: WaypointType,
    val category: String?,
    val elevationM: Double?,
    /** Distance and bearing as the phone words it, with the pilot's units. */
    val way: String?,
    val distanceM: Double?,
    val bearingDeg: Double?,
)
