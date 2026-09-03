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
    /** Absent when the pilot has downloaded no maps, so there is nothing to render. */
    @SerialName("mapRev") val mapRevision: Long = 0,
    /** The notice the map data carries. Shown by us, since the map widget is not. */
    @SerialName("mapAttribution") val mapAttribution: String = "",
    /** [longitude, latitude, zoom] to open on before anything better is known. */
    @SerialName("mapCentre") val mapCentre: List<Double> = emptyList(),
    /** Label and halo colours for text drawn over the map. They swap with night mode. */
    @SerialName("mapOverlay") val mapOverlay: OverlayColoursDto? = null,
    @SerialName("navPeriodMs") val navPeriodMs: Long = 1000,
    @SerialName("units") val units: UnitsDto = UnitsDto(),
)

@Serializable
data class OverlayColoursDto(
    @SerialName("label") val label: String = "",
    @SerialName("halo") val halo: String = "",
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
    /**
     * Pressure altitude in metres, from the phone's barometer. Deliberately not
     * inside "own": a barometer reads without a satellite in sight.
     */
    @SerialName("pAlt") val pressureAltitudeM: Double? = null,
    /** True when there is a reading and the phone does not believe it. */
    @SerialName("pAltImplausible") val pressureAltitudeImplausible: Boolean = false,
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
    /** The flight level as the moving map writes it: "FL065", or "-". */
    @SerialName("pAlt") val pressureAltitude: String? = null,
    @SerialName("statusText") val statusText: String = "",
)

@Serializable
data class NotamBoardDto(
    @SerialName("v") val version: Int = 0,
    @SerialName("sid") val sessionId: Long = 0,
    @SerialName("notamRev") val notamRevision: Long = 0,
    @SerialName("warning") val warning: String? = null,
    @SerialName("filter") val filter: NotamFilterDto = NotamFilterDto(),
    @SerialName("groups") val groups: List<NotamGroupDto> = emptyList(),
    @SerialName("n") val count: Int = 0,
    @SerialName("dropped") val dropped: Int = 0,
    @SerialName("retrieved") val retrieved: String? = null,
)

@Serializable
data class NotamFilterDto(
    /** Radius in metres around each waypoint within which a NOTAM is listed. */
    @SerialName("radius") val radiusM: Double? = null,
    @SerialName("horizontalOnly") val horizontalOnly: Boolean = true,
    @SerialName("flightLevelApplied") val flightLevelApplied: Boolean = false,
)

@Serializable
data class NotamGroupDto(
    @SerialName("wp") val waypointIndex: Int = -1,
    @SerialName("n") val name: String = "",
    /**
     * Whether NOTAM data for this waypoint was actually retrieved. Defaults to false,
     * which is the safe reading: a document that fails to say means we do not know.
     */
    @SerialName("data") val hasData: Boolean = false,
    @SerialName("retrieved") val retrieved: String? = null,
    @SerialName("notams") val notams: List<NotamDto> = emptyList(),
    @SerialName("cut") val cut: Int = 0,
)

@Serializable
data class NotamDto(
    @SerialName("n") val number: String = "",
    @SerialName("icao") val icaoLocation: String? = null,
    @SerialName("txt") val text: String = "",
    @SerialName("cat") val category: String? = null,
    @SerialName("sect") val section: String? = null,
    @SerialName("traffic") val traffic: String? = null,
    @SerialName("read") val read: Boolean = false,
    /** ISO 8601 UTC. Either bound may be absent: a permanent NOTAM has no end. */
    @SerialName("from") val from: String? = null,
    @SerialName("to") val to: String? = null,
    @SerialName("area") val area: NotamAreaDto? = null,
)

@Serializable
data class NotamAreaDto(
    /** [longitude, latitude], GeoJSON axis order. */
    @SerialName("c") val centre: List<Double> = emptyList(),
    @SerialName("r") val radiusM: Double = 0.0,
)
