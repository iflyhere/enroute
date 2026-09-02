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

import de.akaflieg_freiburg.enroute.wear.data.dto.FormattedDto
import de.akaflieg_freiburg.enroute.wear.data.dto.NavFrameDto
import de.akaflieg_freiburg.enroute.wear.data.dto.NavLegDto
import de.akaflieg_freiburg.enroute.wear.data.dto.RouteDto
import de.akaflieg_freiburg.enroute.wear.domain.FlightRoute
import de.akaflieg_freiburg.enroute.wear.domain.FlightStatus
import de.akaflieg_freiburg.enroute.wear.domain.GeoPoint
import de.akaflieg_freiburg.enroute.wear.domain.Measured
import de.akaflieg_freiburg.enroute.wear.domain.NavFrame
import de.akaflieg_freiburg.enroute.wear.domain.OwnPosition
import de.akaflieg_freiburg.enroute.wear.domain.RouteLeg
import de.akaflieg_freiburg.enroute.wear.domain.RouteStatus
import de.akaflieg_freiburg.enroute.wear.domain.RouteWaypoint
import de.akaflieg_freiburg.enroute.wear.domain.WaypointLeg
import de.akaflieg_freiburg.enroute.wear.domain.WaypointType

// Wire to domain. The mapping is explicit rather than automatic so that an unknown
// enumeration value or a missing key becomes a defined fallback here, in one readable
// place, instead of an exception somewhere in the UI.

private fun List<Double>?.toGeoPoint(): GeoPoint? {
    // The wire uses GeoJSON axis order: longitude first.
    if (this == null || size < 2) return null
    return GeoPoint(latDeg = this[1], lonDeg = this[0])
}

private fun measured(si: Double?, text: String?): Measured =
    Measured(si, text ?: Measured.PLACEHOLDER)

fun RouteDto.toDomain(): FlightRoute = FlightRoute(
    revision = routeRevision,
    name = name,
    summary = summary,
    waypoints = waypoints.mapIndexedNotNull { index, dto ->
        val point = dto.coordinate.toGeoPoint() ?: return@mapIndexedNotNull null
        RouteWaypoint(
            index = index,
            name = dto.name,
            extendedName = dto.extendedName,
            point = point,
            type = WaypointType.fromWire(dto.type),
            category = dto.category,
            elevationM = dto.elevationM,
        )
    },
    legs = legs.mapIndexedNotNull { index, dto ->
        val distance = dto.distanceM ?: return@mapIndexedNotNull null
        RouteLeg(from = index, distanceM = distance, trueCourseDeg = dto.trueCourseDeg)
    },
)

private fun NavLegDto.toDomain(
    displayName: String?,
    distanceText: String?,
    eteText: String?,
    etaText: String?,
): WaypointLeg = WaypointLeg(
    name = displayName ?: name,
    distance = measured(distanceM, distanceText),
    eteSeconds = eteSeconds,
    eteText = eteText ?: DEFAULT_TIME_TEXT,
    etaEpochSeconds = etaEpochSeconds,
    etaText = etaText ?: DEFAULT_TIME_TEXT,
    trueCourseDeg = trueCourseDeg,
)

/** The phone's sentinel for an unknown time, from Units::Timespan::toHoursAndMinutes(). */
const val DEFAULT_TIME_TEXT = "-:--"

fun NavFrameDto.toDomain(): NavFrame {
    val fmt: FormattedDto? = formatted
    val status = RouteStatus.fromWire(status)

    return NavFrame(
        sessionId = sessionId,
        navRevision = navRevision,
        routeRevision = routeRevision,
        generatedAtEpochSeconds = generatedAtEpochSeconds,
        status = status,
        flightStatus = FlightStatus.fromWire(flightStatus),
        note = note,
        legIndex = legIndex,
        position = own?.let {
            OwnPosition(
                point = it.coordinate.toGeoPoint(),
                altitudeAmsl = measured(it.altitudeAmslM, fmt?.altitude),
                altitudeAglM = it.altitudeAglM,
                groundSpeed = measured(it.groundSpeedMps, fmt?.groundSpeed),
                trackDeg = it.trueTrackDeg,
                verticalSpeedMps = it.verticalSpeedMps,
            )
        } ?: OwnPosition.Unknown,
        // The phone omits these unless the status is onRoute; honour that rather than
        // second-guessing it, so a stale leg can never be rendered as current.
        next = next?.toDomain(fmt?.nextName, fmt?.nextDistance, fmt?.nextEte, fmt?.nextEta),
        final = final?.toDomain(fmt?.finalName, fmt?.finalDistance, fmt?.finalEte, fmt?.finalEta),
        statusText = fmt?.statusText ?: "",
    )
}
