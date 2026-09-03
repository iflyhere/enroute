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

package de.akaflieg_freiburg.enroute.wear.ui.route

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text
import de.akaflieg_freiburg.enroute.wear.domain.FlightRoute
import de.akaflieg_freiburg.enroute.wear.domain.GeoPoint
import de.akaflieg_freiburg.enroute.wear.domain.OwnPosition
import de.akaflieg_freiburg.enroute.wear.domain.RouteWaypoint
import de.akaflieg_freiburg.enroute.wear.domain.WaypointType
import de.akaflieg_freiburg.enroute.wear.ui.theme.CockpitColors
import kotlin.math.hypot
import kotlin.math.min

/**
 * The route drawn as vectors, with the aircraft on it.
 *
 * No map tiles and no aeronautical chart data: a watch cannot show either usefully, and
 * the aviation data the phone holds is licensed for non-commercial use with share-alike
 * terms that are better left untouched. What a pilot needs here is the shape of the
 * route and where they are on it.
 *
 * @param route The route to draw, or null when the phone has none
 *
 * @param zoom How much of the world to show
 *
 * @param ownPosition Read inside the draw lambda on purpose. A position update then
 * invalidates only the draw phase; passing it as a plain parameter would recompose and
 * re-lay-out this whole subtree once a second for identical pixels.
 */
@Composable
fun RouteScreen(
    route: FlightRoute?,
    currentLeg: Int?,
    zoom: ZoomLevel,
    ownPosition: () -> OwnPosition?,
    modifier: Modifier = Modifier,
) {
    if (route == null || route.waypoints.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(CockpitColors.Background),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No route",
                color = CockpitColors.Muted,
                fontSize = 15.sp,
                modifier = Modifier.testTag(TAG_NO_ROUTE),
            )
        }
        return
    }

    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(CockpitColors.Background),
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val bezelMarginPx = with(density) { BEZEL_MARGIN.toPx() }

        // Built once per route, zoom and viewport rather than once per frame. The
        // aircraft moving must not rebuild any of this.
        // The leg index belongs in the key: it changes a handful of times in a
        // flight, not once a second, so keying on it costs nothing and colouring by
        // progress is what makes the drawing worth looking at.
        val geometry = remember(route.revision, currentLeg, zoom, widthPx, heightPx) {
            buildGeometry(
                route, currentLeg, zoom, widthPx, heightPx,
                bezelMarginPx, measurer, density.density,
            )
        }

        Canvas(modifier = Modifier.fillMaxSize().testTag(TAG_CANVAS)) {
            drawRangeRing(geometry)
            drawLegs(geometry)
            geometry.markers.forEach { drawMarker(it) }
            geometry.labels.forEach { drawHaloLabel(it) }

            // Read here, not above: this is what keeps a position update in the draw
            // phase.
            val position = ownPosition()
            val point = position?.point
            if (point != null) {
                drawOwnShip(geometry.projection.toScreen(point), position.trackDeg)
            }
        }

        Text(
            text = ZoomLevel.label(zoom),
            color = CockpitColors.Muted,
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .testTag(TAG_SCALE),
        )
    }
}

// -------------------------------------------------------------------- geometry

private class Marker(val at: Offset, val type: WaypointType, val emphasised: Boolean)

private class Label(val at: Offset, val layout: TextLayoutResult)

private class Geometry(
    val projection: LocalProjection,
    val flownPath: Path,
    val activePath: Path,
    val aheadPath: Path,
    val markers: List<Marker>,
    val labels: List<Label>,
    val ringRadiusPx: Float,
    val centreOffset: Offset,
)

/**
 * Projects the route and lays out its labels.
 *
 * Labels are placed by priority and only if they clear what is already placed:
 * a hundred waypoint names on a 226 dp disc is a grey smear, so at most a handful are
 * drawn. The first and last waypoint always get one, because those are the two a pilot
 * looks for.
 */
private fun buildGeometry(
    route: FlightRoute,
    currentLeg: Int?,
    zoom: ZoomLevel,
    widthPx: Float,
    heightPx: Float,
    bezelMarginPx: Float,
    measurer: TextMeasurer,
    densityScale: Float,
): Geometry {
    val centreOffset = Offset(widthPx / 2f, heightPx / 2f)
    val usableRadius = min(widthPx, heightPx) / 2f - bezelMarginPx

    val points = route.waypoints.map { it.point }
    val centre = LocalProjection.centreOf(points) ?: points.first()

    val metresPerPixel = when (zoom) {
        // Fit to a little less than the usable radius. At the full radius the first
        // and last waypoint land exactly on the edge, leaving nowhere to put their
        // markers or their names.
        ZoomLevel.Automatic ->
            LocalProjection.fitToDisc(points, centre, usableRadius * AUTO_FIT_FRACTION)

        is ZoomLevel.Fixed -> zoom.halfSpanMetres / usableRadius
    }
    val projection = LocalProjection(centre, metresPerPixel, centreOffset)

    val screen = route.waypoints.map { projection.toScreen(it.point) }

    val flownPath = Path()
    val activePath = Path()
    val aheadPath = Path()
    route.legs.forEach { leg ->
        val from = screen.getOrNull(leg.from) ?: return@forEach
        val to = screen.getOrNull(leg.from + 1) ?: return@forEach
        val target = when {
            currentLeg == null -> aheadPath
            leg.from < currentLeg -> flownPath
            leg.from == currentLeg -> activePath
            else -> aheadPath
        }
        target.moveTo(from.x, from.y)
        target.lineTo(to.x, to.y)
    }

    val markers = route.waypoints.mapIndexed { index, waypoint ->
        Marker(
            at = screen[index],
            type = waypoint.type,
            emphasised = index == 0 ||
                index == route.waypoints.lastIndex ||
                (currentLeg != null && index == currentLeg + 1),
        )
    }

    val labels = layOutLabels(route.waypoints, screen, measurer, densityScale, centreOffset, usableRadius)

    return Geometry(
        projection = projection,
        flownPath = flownPath,
        activePath = activePath,
        aheadPath = aheadPath,
        markers = markers,
        labels = labels,
        ringRadiusPx = usableRadius,
        centreOffset = centreOffset,
    )
}

private fun layOutLabels(
    waypoints: List<RouteWaypoint>,
    screen: List<Offset>,
    measurer: TextMeasurer,
    densityScale: Float,
    centreOffset: Offset,
    usableRadius: Float,
): List<Label> {
    val style = TextStyle(fontSize = LABEL_SP.sp, fontWeight = FontWeight.Bold)
    val gap = LABEL_GAP_DP * densityScale
    val placed = mutableListOf<Label>()

    // First and last, then aerodromes, then navaids, then the rest.
    val order = waypoints.indices.sortedBy { index ->
        when {
            index == 0 || index == waypoints.lastIndex -> 0
            waypoints[index].type == WaypointType.Aerodrome -> 1
            waypoints[index].type == WaypointType.Navaid -> 2
            else -> 3
        }
    }

    for (index in order) {
        if (placed.size >= MAX_LABELS) {
            break
        }
        val anchor = screen[index]
        val layout = measurer.measure(waypoints[index].name, style)
        val gapPx = LABEL_OFFSET_DP * densityScale

        // Put the name on the side of the marker that faces the centre of the disc,
        // because that is where there is room. Anchoring outwards fails for exactly
        // the waypoints that matter most, the ones at the ends of the route.
        val towardsCentre = if (anchor.x > centreOffset.x) -1f else 1f
        val left = if (towardsCentre < 0f) {
            anchor.x - gapPx - layout.size.width
        } else {
            anchor.x + gapPx
        }
        val at = Offset(left, anchor.y - layout.size.height / 2f)

        // Both far corners have to sit inside the disc.
        val fits = listOf(
            Offset(at.x, at.y),
            Offset(at.x + layout.size.width, at.y),
            Offset(at.x, at.y + layout.size.height),
            Offset(at.x + layout.size.width, at.y + layout.size.height),
        ).all { corner ->
            hypot(
                (corner.x - centreOffset.x).toDouble(),
                (corner.y - centreOffset.y).toDouble(),
            ) <= usableRadius
        }
        if (!fits) {
            continue
        }
        val collides = placed.any { other ->
            at.x < other.at.x + other.layout.size.width + gap &&
                at.x + layout.size.width + gap > other.at.x &&
                at.y < other.at.y + other.layout.size.height + gap &&
                at.y + layout.size.height + gap > other.at.y
        }
        if (!collides) {
            placed += Label(at, layout)
        }
    }
    return placed
}

// --------------------------------------------------------------------- drawing

private fun DrawScope.drawRangeRing(geometry: Geometry) {
    drawCircle(
        color = CockpitColors.Muted.copy(alpha = 0.25f),
        radius = geometry.ringRadiusPx,
        center = geometry.centreOffset,
        style = Stroke(width = 1.dp.toPx()),
    )
}

private fun DrawScope.drawLegs(geometry: Geometry) {
    drawPath(
        path = geometry.flownPath,
        color = CockpitColors.OnBackground.copy(alpha = 0.4f),
        style = Stroke(width = 1.dp.toPx()),
    )
    drawPath(
        path = geometry.aheadPath,
        color = CockpitColors.OnBackground,
        style = Stroke(width = 2.dp.toPx()),
    )
    drawPath(
        path = geometry.activePath,
        color = CockpitColors.Primary,
        style = Stroke(width = 3.dp.toPx()),
    )
}

/**
 * Waypoints are told apart by shape, never by colour alone: glare and colour vision
 * deficiency both defeat colour, and this display has to work in direct sunlight.
 */
private fun DrawScope.drawMarker(marker: Marker) {
    val radius = (if (marker.emphasised) MARKER_LARGE_DP else MARKER_DP).dp.toPx()
    val colour = CockpitColors.OnBackground

    when (marker.type) {
        WaypointType.Aerodrome -> {
            drawCircle(colour, radius, marker.at, style = Stroke(width = 1.5.dp.toPx()))
            drawCircle(colour, radius / 3f, marker.at)
        }

        WaypointType.Navaid -> {
            val triangle = Path().apply {
                moveTo(marker.at.x, marker.at.y - radius)
                lineTo(marker.at.x + radius, marker.at.y + radius)
                lineTo(marker.at.x - radius, marker.at.y + radius)
                close()
            }
            drawPath(triangle, colour, style = Stroke(width = 1.5.dp.toPx()))
        }

        WaypointType.Waypoint, WaypointType.Unknown -> {
            val side = radius * 1.4f
            drawRect(
                color = colour,
                topLeft = Offset(marker.at.x - side / 2f, marker.at.y - side / 2f),
                size = androidx.compose.ui.geometry.Size(side, side),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
    }
}

/**
 * Text drawn twice, once as a black outline and once filled.
 *
 * Without the halo a name crossing a leg line is unreadable, and in sunlight it is
 * unreadable over anything at all.
 */
private fun DrawScope.drawHaloLabel(label: Label) {
    drawText(
        textLayoutResult = label.layout,
        topLeft = label.at,
        color = CockpitColors.Background,
        drawStyle = Stroke(width = 3f),
    )
    drawText(
        textLayoutResult = label.layout,
        topLeft = label.at,
        color = CockpitColors.OnBackground,
    )
}

/** The aircraft: a chevron pointing along the track, or a circle when there is none. */
private fun DrawScope.drawOwnShip(at: Offset, trackDeg: Double?) {
    val size = OWN_SHIP_DP.dp.toPx()
    if (trackDeg == null) {
        drawCircle(CockpitColors.Primary, size / 2f, at)
        return
    }
    rotate(degrees = trackDeg.toFloat(), pivot = at) {
        val chevron = Path().apply {
            moveTo(at.x, at.y - size)
            lineTo(at.x + size * 0.6f, at.y + size * 0.7f)
            lineTo(at.x, at.y + size * 0.3f)
            lineTo(at.x - size * 0.6f, at.y + size * 0.7f)
            close()
        }
        drawPath(chevron, CockpitColors.Primary)
    }
}

private val BEZEL_MARGIN = 14.dp
private const val MARKER_DP = 4f
private const val MARKER_LARGE_DP = 6f
private const val OWN_SHIP_DP = 7f
private const val LABEL_SP = 12f
private const val LABEL_GAP_DP = 6f
private const val LABEL_OFFSET_DP = 10f
private const val MAX_LABELS = 5
private const val AUTO_FIT_FRACTION = 0.82f

const val TAG_CANVAS = "route.canvas"
const val TAG_SCALE = "route.scale"
const val TAG_NO_ROUTE = "route.none"
