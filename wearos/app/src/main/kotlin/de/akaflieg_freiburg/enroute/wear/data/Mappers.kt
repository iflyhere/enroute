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

import de.akaflieg_freiburg.enroute.wear.data.dto.FlightLogDto
import de.akaflieg_freiburg.enroute.wear.data.dto.FormattedDto
import de.akaflieg_freiburg.enroute.wear.data.dto.MetarDto
import de.akaflieg_freiburg.enroute.wear.data.dto.NavFrameDto
import de.akaflieg_freiburg.enroute.wear.data.dto.NavLegDto
import de.akaflieg_freiburg.enroute.wear.data.dto.NearbyBoardDto
import de.akaflieg_freiburg.enroute.wear.data.dto.PrefsDto
import de.akaflieg_freiburg.enroute.wear.data.dto.NearbyPlaceDto
import de.akaflieg_freiburg.enroute.wear.data.dto.NotamAreaDto
import de.akaflieg_freiburg.enroute.wear.data.dto.NotamBoardDto
import de.akaflieg_freiburg.enroute.wear.data.dto.NotamDto
import de.akaflieg_freiburg.enroute.wear.data.dto.NotamFilterDto
import de.akaflieg_freiburg.enroute.wear.data.dto.NotamGroupDto
import de.akaflieg_freiburg.enroute.wear.data.dto.RouteDto
import de.akaflieg_freiburg.enroute.wear.data.dto.TafDto
import de.akaflieg_freiburg.enroute.wear.data.dto.TrafficBoardDto
import de.akaflieg_freiburg.enroute.wear.data.dto.TrafficTargetDto
import de.akaflieg_freiburg.enroute.wear.data.dto.VacBoardDto
import de.akaflieg_freiburg.enroute.wear.data.dto.WeatherBoardDto
import de.akaflieg_freiburg.enroute.wear.domain.ApproachChart
import de.akaflieg_freiburg.enroute.wear.domain.DetectionState
import de.akaflieg_freiburg.enroute.wear.domain.FlightEntry
import de.akaflieg_freiburg.enroute.wear.domain.FlightLogBoard
import de.akaflieg_freiburg.enroute.wear.domain.FlightCategory
import de.akaflieg_freiburg.enroute.wear.domain.FlightRoute
import de.akaflieg_freiburg.enroute.wear.domain.FlightStatus
import de.akaflieg_freiburg.enroute.wear.domain.GeoPoint
import de.akaflieg_freiburg.enroute.wear.domain.WatchPreferences
import de.akaflieg_freiburg.enroute.wear.domain.Measured
import de.akaflieg_freiburg.enroute.wear.domain.MetarReport
import de.akaflieg_freiburg.enroute.wear.domain.NavFrame
import de.akaflieg_freiburg.enroute.wear.domain.NearbyBoard
import de.akaflieg_freiburg.enroute.wear.domain.NearbyPlace
import de.akaflieg_freiburg.enroute.wear.domain.Notam
import de.akaflieg_freiburg.enroute.wear.domain.NotamArea
import de.akaflieg_freiburg.enroute.wear.domain.NotamBoard
import de.akaflieg_freiburg.enroute.wear.domain.NotamCategory
import de.akaflieg_freiburg.enroute.wear.domain.NotamFilter
import de.akaflieg_freiburg.enroute.wear.domain.NotamGroup
import de.akaflieg_freiburg.enroute.wear.domain.OwnPosition
import de.akaflieg_freiburg.enroute.wear.domain.RouteLeg
import de.akaflieg_freiburg.enroute.wear.domain.RouteStatus
import de.akaflieg_freiburg.enroute.wear.domain.RouteWaypoint
import de.akaflieg_freiburg.enroute.wear.domain.TafReport
import de.akaflieg_freiburg.enroute.wear.domain.TrafficBoard
import de.akaflieg_freiburg.enroute.wear.domain.TrafficTarget
import de.akaflieg_freiburg.enroute.wear.domain.TrafficWarning
import de.akaflieg_freiburg.enroute.wear.domain.WaypointLeg
import de.akaflieg_freiburg.enroute.wear.domain.VacBoard
import de.akaflieg_freiburg.enroute.wear.domain.WaypointType
import de.akaflieg_freiburg.enroute.wear.domain.WeatherBoard
import de.akaflieg_freiburg.enroute.wear.domain.WeatherStation
import java.time.Instant

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
        flightLevel = measured(pressureAltitudeM, fmt?.pressureAltitude),
        flightLevelImplausible = pressureAltitudeImplausible,
        alarmLevel = alarmLevel,
    )
}


/**
 * ISO 8601 to epoch seconds, or null if it cannot be parsed.
 *
 * java.time.Instant rather than SimpleDateFormat: it is available from API 26, this app
 * requires 30, and it is the only one of the two that is thread-safe. An unparsable
 * timestamp becomes null and the field is simply not shown, which is the same treatment
 * an omitted one gets.
 */
private fun String?.toEpochSeconds(): Long? {
    if (this == null) return null
    return runCatching { Instant.parse(this).epochSecond }.getOrNull()
}

private fun NotamAreaDto.toDomain(): NotamArea? {
    val point = centre.toGeoPoint() ?: return null
    if (radiusM <= 0.0) return null
    return NotamArea(centre = point, radiusM = radiusM)
}

private fun NotamFilterDto.toDomain(): NotamFilter = NotamFilter(
    radiusM = radiusM,
    horizontalOnly = horizontalOnly,
    flightLevelApplied = flightLevelApplied,
)

private fun NotamDto.toDomain(): Notam? {
    // A NOTAM without a number cannot be keyed in a list, marked read, or referred to
    // in a radio call. Dropping it is better than showing an entry that cannot be
    // acted on.
    if (number.isBlank()) return null
    return Notam(
        number = number,
        icaoLocation = icaoLocation?.takeIf { it.isNotBlank() },
        text = text,
        category = NotamCategory.fromWire(category),
        section = section?.takeIf { it.isNotBlank() },
        traffic = traffic?.takeIf { it.isNotBlank() },
        read = read,
        fromEpochSeconds = from.toEpochSeconds(),
        toEpochSeconds = to.toEpochSeconds(),
        area = area?.toDomain(),
    )
}

private fun NotamGroupDto.toDomain(): NotamGroup {
    val mapped = notams.mapNotNull { it.toDomain() }

    // A NOTAM dropped by the mapping above has to be counted as cut, not silently
    // lost: the whole point of the cut member is that an incomplete group is never
    // presentable as an empty one.
    val lostInMapping = notams.size - mapped.size

    return NotamGroup(
        waypointIndex = waypointIndex,
        name = name,
        hasData = hasData,
        retrievedEpochSeconds = retrieved.toEpochSeconds(),
        notams = mapped,
        cut = cut + lostInMapping,
    )
}

/**
 * A colour as the style files write it, turned into an opaque ARGB value.
 *
 * The style is CSS-flavoured, so both "#e0e0e0" and "black" occur. An unrecognised
 * value returns null rather than a guess: the caller then keeps its own colour, which
 * is wrong in a readable way instead of wrong in an invisible one -- and invisible is
 * exactly the failure this parser exists to prevent.
 */
fun parseStyleColour(text: String?): Long? {
    if (text == null) return null
    val trimmed = text.trim().lowercase()
    if (trimmed.isEmpty()) return null

    NAMED_COLOURS[trimmed]?.let { return it }

    if (!trimmed.startsWith("#")) return null
    val digits = trimmed.substring(1)
    val value = digits.toLongOrNull(16) ?: return null
    return when (digits.length) {
        3 -> {
            // "#abc" is shorthand for "#aabbcc".
            val r = (value shr 8) and 0xF
            val g = (value shr 4) and 0xF
            val b = value and 0xF
            0xFF000000L or (r * 0x11 shl 16) or (g * 0x11 shl 8) or (b * 0x11)
        }
        6 -> 0xFF000000L or value
        8 -> value
        else -> null
    }
}

// Only the names the app's own style files actually use. A wider table would suggest
// this understands CSS, which it does not.
private val NAMED_COLOURS = mapOf(
    "black" to 0xFF000000L,
    "white" to 0xFFFFFFFFL,
    "red" to 0xFFFF0000L,
    "green" to 0xFF008000L,
    "blue" to 0xFF0000FFL,
    "yellow" to 0xFFFFFF00L,
)

fun NotamBoardDto.toDomain(): NotamBoard = NotamBoard(
    revision = notamRevision,
    groups = groups.filter { it.waypointIndex >= 0 }.map { it.toDomain() },
    warning = warning?.takeIf { it.isNotBlank() },
    filter = filter.toDomain(),
    retrievedEpochSeconds = retrieved.toEpochSeconds(),
    dropped = dropped,
)

fun WeatherBoardDto.toDomain(): WeatherBoard = WeatherBoard(
    revision = weatherRevision,
    qnh = qnh?.takeIf { it.isNotBlank() },
    sun = sun?.takeIf { it.isNotBlank() },
    downloading = downloading,
    // The phone sends the list already sorted by distance, so it is kept in wire
    // order. Re-sorting here would need a position the watch does not have.
    stations = stations.mapNotNull { dto ->
        val metar = dto.metar?.toDomain()
        val taf = dto.taf?.toDomain()
        // A station with neither report has nothing to show. The phone filters these
        // out too, so this only guards against a document from an older version.
        if (metar == null && taf == null) return@mapNotNull null
        WeatherStation(
            name = dto.waypoint.name,
            extendedName = dto.waypoint.extendedName,
            point = dto.waypoint.coordinate.toGeoPoint(),
            type = WaypointType.fromWire(dto.waypoint.type),
            way = dto.way?.takeIf { it.isNotBlank() },
            metar = metar,
            taf = taf,
        )
    },
)

private fun MetarDto.toDomain(): MetarReport? {
    if (raw.isBlank()) return null
    return MetarReport(
        raw = raw,
        summary = summary?.takeIf { it.isNotBlank() },
        category = FlightCategory.fromWire(category),
        colour = parseStyleColour(colour),
        observedEpochSeconds = observed.toEpochSeconds(),
    )
}

private fun TafDto.toDomain(): TafReport? {
    if (raw.isBlank()) return null
    return TafReport(raw = raw, issuedEpochSeconds = issued.toEpochSeconds())
}

fun VacBoardDto.toDomain(): VacBoard = VacBoard(
    revision = vacRevision,
    available = available,
    charts = charts.mapNotNull { dto ->
        if (dto.name.isBlank()) return@mapNotNull null

        // Four corners or nothing. A chart drawn on three is not a degraded chart,
        // it is a chart in the wrong place, and on an approach that is worse than no
        // chart at all.
        val corners = dto.quad.mapNotNull { corner -> corner.toGeoPoint() }
        if (corners.size != 4) return@mapNotNull null
        if (dto.bounds.size != 4) return@mapNotNull null

        ApproachChart(
            name = dto.name,
            description = dto.description?.takeIf { text -> text.isNotBlank() },
            section = dto.section?.takeIf { text -> text.isNotBlank() },
            quad = corners,
            west = dto.bounds[0],
            south = dto.bounds[1],
            east = dto.bounds[2],
            north = dto.bounds[3],
        )
    },
)

fun FlightLogDto.toDomain(): FlightLogBoard = FlightLogBoard(
    revision = logRevision,
    state = DetectionState.fromWire(state),
    recording = recording,
    total = total,
    dropped = dropped,
    // Kept in wire order, which is the app's own: newest first. Re-sorting here would
    // be a second opinion about a list the phone already ordered.
    entries = flights.mapNotNull { dto ->
        if (dto.id.isBlank()) return@mapNotNull null
        FlightEntry(
            id = dto.id,
            departure = dto.departure?.takeIf { code -> code.isNotBlank() },
            arrival = dto.arrival?.takeIf { code -> code.isNotBlank() },
            startEpochSeconds = dto.start.toEpochSeconds(),
            landingEpochSeconds = dto.landing.toEpochSeconds(),
            offBlockEpochSeconds = dto.offBlock.toEpochSeconds(),
            onBlockEpochSeconds = dto.onBlock.toEpochSeconds(),
            flightTime = dto.flightTime?.takeIf { text -> text.isNotBlank() },
            blockTime = dto.blockTime?.takeIf { text -> text.isNotBlank() },
            callsign = dto.callsign?.takeIf { text -> text.isNotBlank() },
            landings = dto.landings,
            hasTrack = dto.hasTrack,
        )
    },
)

fun TrafficBoardDto.toDomain(): TrafficBoard = TrafficBoard(
    revision = trafficRevision,
    receiving = receiving,
    status = status?.takeIf { text -> text.isNotBlank() },
    runtimeError = runtimeError?.takeIf { text -> text.isNotBlank() },
    selfTestError = selfTestError?.takeIf { text -> text.isNotBlank() },
    warning = warning?.takeIf { entry -> entry.alarmLevel > 0 }?.let { entry ->
        TrafficWarning(
            alarmLevel = entry.alarmLevel,
            alarmType = entry.alarmType,
            description = entry.description?.takeIf { text -> text.isNotBlank() },
            horizontalDistanceM = entry.horizontalDistanceM,
            verticalDistanceM = entry.verticalDistanceM,
        )
    },
    targets = targets.map { dto -> dto.toDomain() },
    withoutBearing = withoutBearing?.toDomain(),
)

private fun TrafficTargetDto.toDomain(): TrafficTarget = TrafficTarget(
    id = id?.takeIf { text -> text.isNotBlank() },
    callSign = callSign?.takeIf { text -> text.isNotBlank() },
    alarmLevel = alarmLevel,
    colour = parseStyleColour(colour),
    type = type?.takeIf { text -> text.isNotBlank() },
    horizontalDistanceM = horizontalDistanceM,
    verticalDistanceM = verticalDistanceM,
    description = description?.takeIf { text -> text.isNotBlank() },
    relevant = relevant,
    point = coordinate.toGeoPoint(),
    trackDeg = trackDeg,
    uncertaintyRadiusM = uncertaintyRadiusM,
)

fun NearbyBoardDto.toDomain(): NearbyBoard = NearbyBoard(
    revision = nearbyRevision,
    positionKnown = positionKnown,
    // Kept in wire order, which is the app's: nearest first.
    aerodromes = groups["AD"].orEmpty().map { dto -> dto.toDomain() },
    navaids = groups["NAV"].orEmpty().map { dto -> dto.toDomain() },
    waypoints = groups["WP"].orEmpty().map { dto -> dto.toDomain() },
)

private fun NearbyPlaceDto.toDomain(): NearbyPlace = NearbyPlace(
    name = name,
    extendedName = extendedName?.takeIf { text -> text.isNotBlank() },
    point = coordinate.toGeoPoint(),
    type = WaypointType.fromWire(type),
    category = category?.takeIf { text -> text.isNotBlank() },
    elevationM = elevationM,
    way = way?.takeIf { text -> text.isNotBlank() },
    distanceM = distanceM,
    bearingDeg = bearingDeg,
)

fun PrefsDto.toDomain(): WatchPreferences = WatchPreferences(
    sessionId = sessionId,
    revision = revision,
    pageOrder = pageOrder,
    hiddenPages = hiddenPages,
    bezel = bezel,
    charts = charts,
    alarmVibration = alarmVibration,
    transport = transport,
)
