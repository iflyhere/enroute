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

package de.akaflieg_freiburg.enroute.wear.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire documents from doc/companion-protocol.md.
//
// Two rules run through all of these. Every optional value is nullable, because the
// phone omits a key rather than encoding an invalid one -- the Units classes on that
// side use NaN as their invalid marker and JSON cannot carry NaN. And enumerations
// arrive as Strings, not Kotlin enums, so that a value this build has never heard of
// maps to a neutral fallback instead of throwing mid-flight.

@Serializable
data class HelloDto(
    @SerialName("v") val version: Int = 0,
    @SerialName("app") val appVersion: String = "",
    @SerialName("sid") val sessionId: Long = 0,
    @SerialName("routeRev") val routeRevision: Long = 0,
    @SerialName("navRev") val navRevision: Long = 0,
    @SerialName("navPeriodMs") val navPeriodMs: Long = 1000,
    @SerialName("units") val units: UnitsDto = UnitsDto(),
)

@Serializable
data class UnitsDto(
    @SerialName("hDist") val horizontalDistance: String = "nm",
    @SerialName("vDist") val verticalDistance: String = "ft",
)

@Serializable
data class RouteDto(
    @SerialName("v") val version: Int = 0,
    @SerialName("sid") val sessionId: Long = 0,
    @SerialName("routeRev") val routeRevision: Long = 0,
    @SerialName("name") val name: String = "",
    @SerialName("summary") val summary: String = "",
    @SerialName("units") val units: UnitsDto = UnitsDto(),
    @SerialName("wp") val waypoints: List<WaypointDto> = emptyList(),
    @SerialName("legs") val legs: List<LegDto> = emptyList(),
)

@Serializable
data class WaypointDto(
    @SerialName("n") val name: String = "",
    @SerialName("en") val extendedName: String? = null,
    /** [longitude, latitude], GeoJSON axis order. */
    @SerialName("c") val coordinate: List<Double> = emptyList(),
    @SerialName("e") val elevationM: Double? = null,
    @SerialName("t") val type: String? = null,
    @SerialName("cat") val category: String? = null,
)

@Serializable
data class LegDto(
    @SerialName("d") val distanceM: Double? = null,
    @SerialName("tc") val trueCourseDeg: Double? = null,
)

@Serializable
data class NavFrameDto(
    @SerialName("v") val version: Int = 0,
    @SerialName("sid") val sessionId: Long = 0,
    @SerialName("navRev") val navRevision: Long = 0,
    @SerialName("routeRev") val routeRevision: Long = 0,
    @SerialName("t") val generatedAtEpochSeconds: Long = 0,
    @SerialName("status") val status: String? = null,
    @SerialName("flightStatus") val flightStatus: String? = null,
    @SerialName("note") val note: String = "",
    @SerialName("leg") val legIndex: Int? = null,
    @SerialName("own") val own: OwnPositionDto? = null,
    @SerialName("next") val next: NavLegDto? = null,
    @SerialName("final") val final: NavLegDto? = null,
    @SerialName("fmt") val formatted: FormattedDto? = null,
)

@Serializable
data class OwnPositionDto(
    @SerialName("c") val coordinate: List<Double>? = null,
    @SerialName("alt") val altitudeAmslM: Double? = null,
    @SerialName("agl") val altitudeAglM: Double? = null,
    @SerialName("gs") val groundSpeedMps: Double? = null,
    @SerialName("tt") val trueTrackDeg: Double? = null,
    @SerialName("vs") val verticalSpeedMps: Double? = null,
)

@Serializable
data class NavLegDto(
    @SerialName("n") val name: String = "",
    @SerialName("dist") val distanceM: Double? = null,
    @SerialName("ete") val eteSeconds: Long? = null,
    @SerialName("eta") val etaEpochSeconds: Long? = null,
    @SerialName("tc") val trueCourseDeg: Double? = null,
)

/** Display strings produced by Navigation::Aircraft on the phone. */
@Serializable
data class FormattedDto(
    @SerialName("nextName") val nextName: String? = null,
    @SerialName("nextDist") val nextDistance: String? = null,
    @SerialName("nextETE") val nextEte: String? = null,
    @SerialName("nextETA") val nextEta: String? = null,
    @SerialName("nextTC") val nextTrueCourse: String? = null,
    @SerialName("finalName") val finalName: String? = null,
    @SerialName("finalDist") val finalDistance: String? = null,
    @SerialName("finalETE") val finalEte: String? = null,
    @SerialName("finalETA") val finalEta: String? = null,
    @SerialName("alt") val altitude: String? = null,
    @SerialName("gs") val groundSpeed: String? = null,
    @SerialName("statusText") val statusText: String = "",
)
