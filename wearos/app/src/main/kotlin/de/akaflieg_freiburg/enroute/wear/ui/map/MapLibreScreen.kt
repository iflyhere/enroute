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

package de.akaflieg_freiburg.enroute.wear.ui.map

import android.annotation.SuppressLint
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.wear.compose.material3.LocalTextStyle
import androidx.wear.compose.material3.Text
import de.akaflieg_freiburg.enroute.wear.domain.FlightRoute
import de.akaflieg_freiburg.enroute.wear.domain.GeoPoint
import de.akaflieg_freiburg.enroute.wear.domain.OwnPosition
import de.akaflieg_freiburg.enroute.wear.domain.TrafficBoard
import de.akaflieg_freiburg.enroute.wear.domain.VacBoard
import de.akaflieg_freiburg.enroute.wear.ui.route.ZoomLevel
import de.akaflieg_freiburg.enroute.wear.ui.theme.CockpitColors
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.net.URI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The pilot's own map, rendered on the watch.
 *
 * Every byte of it comes from the phone: the style, the vector tiles, the aviation data
 * overlay, the sprite sheet and the glyph ranges. The watch needs no internet connection
 * and no map service account, and because the style is the phone's own, the map here
 * looks like the map there -- night mode and all.
 *
 * The route and the aircraft are drawn as layers on top of that style rather than as a
 * Compose overlay. A Compose overlay would have to reproduce the renderer's camera to
 * stay aligned, and it would drift the moment the two disagreed about anything.
 *
 * An approach chart is drawn the way the phone draws one: an image source on the four
 * corners the chart carries, in a raster layer above the aviation overlay and below the
 * route. That is where the phone puts it -- its own chart layer is declared after every
 * aviation layer, and it keeps a second copy of the waypoint layer above the chart, so
 * the chart covers the airspaces while the route stays visible. Which chart is shown
 * follows the app's own rule, bounding-box containment, applied to the aircraft.
 *
 * No attribution is drawn here. The notice the map data requires is on the settings
 * page instead, permanently and one swipe away, which is the same arrangement the
 * renderer's own info button makes -- and it gives a 454 pixel disc back two lines it
 * was spending on every glance. The obligation is met by showing the credit, not by
 * showing it on top of the map.
 */
@Composable
fun MapLibreScreen(
    styleUrl: String,
    host: String,
    pairingCode: String,
    route: FlightRoute?,
    ownPosition: OwnPosition?,
    charts: VacBoard?,
    traffic: TrafficBoard?,
    port: Int,
    zoom: ZoomLevel,
    isActive: Boolean,
    fallbackCentre: GeoPoint?,
    fallbackZoom: Double,
    labelColour: Long?,
    haloColour: Long?,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val radiusPixels = with(density) { (configuration.screenWidthDp.dp.toPx()) / 2f }

    // Held across recompositions so a position update touches the existing map instead
    // of building a new one.
    val holder = remember { MapHolder() }

    Box(modifier = modifier.fillMaxSize().background(CockpitColors.Background)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                // Here rather than in the composable body: the library has to be
                // initialised and given its HTTP client before the first MapView
                // exists, and this is the moment that is true.
                MapLibreSetup.ensure(context, host, pairingCode)

                // Rendered at one map pixel per screen pixel instead of at the
                // screen's own density. The style is the phone's, drawn for a hand-held
                // display, and at density 2 on a 480 pixel watch a town name fills half
                // the face. Dropping the ratio shows four times the ground for the same
                // label size, which is what a map on a wrist is for. The cost is a
                // softer picture, and on this screen that is the cheaper half of the
                // trade.
                val options = MapLibreMapOptions.createFromAttributes(context)
                    .pixelRatio(MAP_PIXEL_RATIO)
                    .attributionEnabled(false)
                    .logoEnabled(false)
                    .compassEnabled(false)

                MapView(context, options).also { view ->
                    view.onCreate(null)

                    // A View inside an AndroidView takes focus when it is attached,
                    // and rotary events go to whatever holds focus. The bezel then
                    // reaches the renderer -- which has every gesture switched off and
                    // drops them -- instead of the handler that turns them into zoom.
                    // This is the trap that made the bezel look dead on the map page
                    // while it worked everywhere else.
                    view.isFocusable = false
                    view.isFocusableInTouchMode = false
                    view.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    holder.view = view
                    view.getMapAsync { map ->
                        holder.map = map
                        configure(map)
                        map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                            holder.style = style
                            addOverlayLayers(style)
                            holder.applyRoute(route)
                            holder.applyOwnPosition(ownPosition)
                            holder.applyChart(charts, ownPosition, host, port)
                holder.applyTraffic(traffic)
                            holder.applyTraffic(traffic)
                            holder.applyCamera(
                                ownPosition, route, zoom, radiusPixels,
                                fallbackCentre, fallbackZoom,
                            )
                        }
                    }
                }
            },
            update = {
                holder.applyRoute(route)
                holder.applyOwnPosition(ownPosition)
                holder.applyChart(charts, ownPosition, host, port)
                // Only while this page is the one being looked at. Moving the camera
                // costs a redraw, and a redraw on a watch costs battery for pixels
                // nobody is seeing.
                if (isActive) {
                    holder.applyCamera(
                        ownPosition, route, zoom, radiusPixels,
                        fallbackCentre, fallbackZoom,
                    )
                }
            },
        )

        // Feedback for the bezel and the drag. Without it a zoom step on a map with
        // few features looks like nothing happened, and the pilot keeps turning.
        MapText(
            text = ZoomLevel.label(zoom),
            labelColour = labelColour,
            haloColour = haloColour,
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
        )

        if (route == null || route.waypoints.isEmpty()) {
            MapText(
                text = "No route",
                labelColour = labelColour,
                haloColour = haloColour,
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 30.dp),
            )
        }

    }

    // A pager keeps its neighbouring pages composed, so without this the renderer
    // keeps drawing while the pilot is looking at the data screen. Pausing it is the
    // single largest thing this screen does for battery life.
    LaunchedEffect(isActive) {
        val view = holder.view ?: return@LaunchedEffect
        if (isActive) {
            view.onResume()
        } else {
            view.onPause()
        }
    }

    // The renderer holds an OpenGL surface and a worker thread, and it will keep both
    // alive through a stopped activity unless it is told. On a watch that is the
    // difference between a map page and a flat battery.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val view = holder.view ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                Lifecycle.Event.ON_DESTROY -> view.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            holder.view?.onStop()
            holder.view?.onDestroy()
            holder.view = null
            holder.map = null
            holder.style = null
        }
    }
}

/**
 * Text drawn over the map, which is the one place the cockpit palette does not apply.
 *
 * That palette assumes a black background and the map's is a daylight base map: white
 * on white, which is how this page shipped and was rightly called out. The phone knows
 * which pair reads on the map it is serving and swaps them with night mode, so the
 * colours come from there. The halo is what carries the text over whatever the map puts
 * underneath it -- a road casing, a lake, a shaded hillside.
 *
 * Falls back to the muted palette colour when the phone said nothing, which is legible
 * on the black fallback screen.
 */
@Composable
private fun MapText(
    text: String,
    labelColour: Long?,
    haloColour: Long?,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    lineHeight: TextUnit = TextUnit.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
) {
    val label = labelColour?.let { Color(it) } ?: CockpitColors.Muted
    val halo = haloColour?.let { Color(it) } ?: CockpitColors.Background

    Text(
        text = text,
        color = label,
        fontSize = fontSize,
        lineHeight = lineHeight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        style = LocalTextStyle.current.copy(
            shadow = Shadow(color = halo, offset = Offset.Zero, blurRadius = HALO_BLUR),
        ),
        modifier = modifier,
    )
}

/** Kept out of the composable so that Compose never has to reason about its identity. */
private class MapHolder {
    var view: MapView? = null
    var map: MapLibreMap? = null
    var style: Style? = null

    private var lastRouteRevision: Long = -1
    private var shownChart: String? = null

    /**
     * Shows the chart covering the aircraft, or none.
     *
     * Exactly one chart at a time. Adding every installed chart and toggling
     * visibility would be less code, but an image source holds its whole image, and a
     * pilot with a country's worth of charts installed would be asking a watch to keep
     * all of them in memory at once.
     *
     * The image is named by URL rather than fetched here: the renderer's own HTTP
     * client already carries the pairing code, so it can fetch and scale the chart
     * itself and this code never owns a bitmap.
     */
    fun applyChart(charts: VacBoard?, ownPosition: OwnPosition?, host: String, port: Int) {
        val currentStyle = style ?: return

        val here = ownPosition?.point
        val wanted = if (charts == null || here == null) {
            null
        } else {
            charts.coveringSortedByName(here).firstOrNull()
        }

        if (wanted?.name == shownChart) {
            return
        }

        // Layer first, then source: a source still referenced by a layer cannot be
        // removed, and the renderer logs that rather than throwing, which would leave
        // a stale chart on screen with no clue why.
        if (shownChart != null) {
            currentStyle.removeLayer(CHART_LAYER)
            currentStyle.removeSource(CHART_SOURCE)
        }
        shownChart = wanted?.name
        if (wanted == null) {
            return
        }

        val quad = LatLngQuad(
            LatLng(wanted.quad[0].latDeg, wanted.quad[0].lonDeg),
            LatLng(wanted.quad[1].latDeg, wanted.quad[1].lonDeg),
            LatLng(wanted.quad[2].latDeg, wanted.quad[2].lonDeg),
            LatLng(wanted.quad[3].latDeg, wanted.quad[3].lonDeg),
        )
        val url = "http://" + host + ":" + port + PATH_PREFIX + wanted.imagePath
        currentStyle.addSource(ImageSource(CHART_SOURCE, quad, URI(url)))

        // Below the route rather than on top of everything: the phone keeps its
        // waypoint layer above its chart layer for the same reason, and a chart that
        // hides the aircraft is worse than no chart.
        currentStyle.addLayerBelow(
            RasterLayer(CHART_LAYER, CHART_SOURCE).withProperties(
                PropertyFactory.rasterResampling(Property.RASTER_RESAMPLING_LINEAR),
            ),
            ROUTE_LAYER,
        )
    }

    /**
     * Draws the traffic the receiver reports.
     *
     * A filled circle in the phone's own colour for the target's alarm level, and a
     * short line along its extrapolated track. Not the aircraft symbol the phone
     * draws: that is a QML item with its own rotated icon, and the sprite sheet this
     * map is served has no equivalent, so a dot with a direction is the honest
     * translation rather than a worse imitation.
     *
     * Rebuilt on every frame with no comparison. Traffic is the one overlay where a
     * cached picture is a wrong picture, and a feature collection of a dozen points
     * costs nothing next to a tile.
     */
    fun applyTraffic(traffic: TrafficBoard?) {
        val currentStyle = style ?: return
        val source = currentStyle.getSourceAs<GeoJsonSource>(TRAFFIC_SOURCE) ?: return
        val trackSource = currentStyle.getSourceAs<GeoJsonSource>(TRAFFIC_TRACK_SOURCE) ?: return

        val dots = mutableListOf<Feature>()
        val tracks = mutableListOf<Feature>()

        traffic?.targets.orEmpty().forEach { target ->
            val point = target.point ?: return@forEach
            val colour = target.colour
                ?.let { value -> String.format("#%06X", (value and 0xFFFFFFL)) }
                ?: TRAFFIC_FALLBACK_COLOUR

            val feature = Feature.fromGeometry(
                Point.fromLngLat(point.lonDeg, point.latDeg),
            )
            feature.addStringProperty("colour", colour)
            feature.addStringProperty("label", target.label)
            dots.add(feature)

            // A fixed length in metres rather than one scaled by ground speed: the
            // document carries no speed, and a line whose length implied one would be
            // saying something the data does not.
            val track = target.trackDeg ?: return@forEach
            val end = offset(point, track, TRACK_LENGTH_M)
            val line = Feature.fromGeometry(
                LineString.fromLngLats(
                    listOf(
                        Point.fromLngLat(point.lonDeg, point.latDeg),
                        Point.fromLngLat(end.lonDeg, end.latDeg),
                    ),
                ),
            )
            line.addStringProperty("colour", colour)
            tracks.add(line)
        }

        source.setGeoJson(FeatureCollection.fromFeatures(dots))
        trackSource.setGeoJson(FeatureCollection.fromFeatures(tracks))
    }

    fun applyRoute(route: FlightRoute?) {
        val currentStyle = style ?: return
        // A route changes a handful of times in a flight; rebuilding its geometry on
        // every position update would be a waste at one hertz.
        if (route != null && route.revision == lastRouteRevision) {
            return
        }
        lastRouteRevision = route?.revision ?: -1

        val points = route?.waypoints?.map { Point.fromLngLat(it.point.lonDeg, it.point.latDeg) }
            .orEmpty()
        val features = if (points.size >= 2) {
            listOf(Feature.fromGeometry(LineString.fromLngLats(points)))
        } else {
            emptyList()
        }
        val source = currentStyle.getSourceAs<GeoJsonSource>(ROUTE_SOURCE) ?: return
        source.setGeoJson(FeatureCollection.fromFeatures(features))

        val markers = points.map { Feature.fromGeometry(it) }
        currentStyle.getSourceAs<GeoJsonSource>(WAYPOINT_SOURCE)
            ?.setGeoJson(FeatureCollection.fromFeatures(markers))
    }

    fun applyOwnPosition(position: OwnPosition?) {
        val currentStyle = style ?: return
        val source = currentStyle.getSourceAs<GeoJsonSource>(OWNSHIP_SOURCE) ?: return
        val point = position?.point
        val features = if (point == null) {
            emptyList()
        } else {
            listOf(Feature.fromGeometry(Point.fromLngLat(point.lonDeg, point.latDeg)))
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    fun applyCamera(
        position: OwnPosition?,
        route: FlightRoute?,
        zoom: ZoomLevel,
        radiusPixels: Float,
        fallbackCentre: GeoPoint?,
        fallbackZoom: Double,
    ) {
        val currentMap = map ?: return

        // With neither a position nor a route there is still something better to do
        // than nothing: the phone says where its maps are. Doing nothing leaves the
        // renderer at its own default of zero north, zero east, at zoom zero, which
        // is a grey screen because no aviation map covers it.
        val centre = position?.point
            ?: route?.waypoints?.firstOrNull()?.point
            ?: fallbackCentre
            ?: return

        val useFallbackZoom = position?.point == null &&
            route?.waypoints.isNullOrEmpty() &&
            fallbackZoom > 0.0

        val target = LatLng(centre.latDeg, centre.lonDeg)
        val level = if (useFallbackZoom) {
            fallbackZoom
        } else {
            val halfSpan = when (zoom) {
                ZoomLevel.Automatic -> automaticHalfSpan(route, centre)
                is ZoomLevel.Fixed -> zoom.halfSpanMetres
            }
            zoomForHalfSpan(halfSpan, radiusPixels, centre.latDeg)
        }

        currentMap.cameraPosition = CameraPosition.Builder()
            .target(target)
            .zoom(level)
            .build()
    }

    private fun automaticHalfSpan(route: FlightRoute?, centre: GeoPoint): Double {
        val points = route?.waypoints?.map { it.point }.orEmpty()
        if (points.isEmpty()) {
            return DEFAULT_HALF_SPAN_M
        }
        val furthest = points.maxOf { distanceMetres(centre, it) }
        return maxOf(furthest * AUTOMATIC_MARGIN, MIN_HALF_SPAN_M)
    }
}

private fun distanceMetres(a: GeoPoint, b: GeoPoint): Double {
    val metresPerDegreeLat = 111_195.0
    val dLat = (b.latDeg - a.latDeg) * metresPerDegreeLat
    val dLon = (b.lonDeg - a.lonDeg) * metresPerDegreeLat *
        kotlin.math.cos(Math.toRadians((a.latDeg + b.latDeg) / 2.0))
    return kotlin.math.hypot(dLat, dLon)
}

@SuppressLint("MissingPermission")
private fun configure(map: MapLibreMap) {
    map.uiSettings.apply {
        isCompassEnabled = false
        isRotateGesturesEnabled = false
        isTiltGesturesEnabled = false

        // Every gesture off, including pan and pinch. The camera follows the aircraft
        // and is reset on every frame, so a drag would snap back a second later and
        // leave the pilot fighting the display. Zoom is the bezel's job, which is the
        // control you can use without looking.
        isScrollGesturesEnabled = false
        isZoomGesturesEnabled = false
        isDoubleTapGesturesEnabled = false
        isQuickZoomGesturesEnabled = false
        // The renderer's own logo and info button take a corner of a screen that is
        // 454 pixels across and round, so most of that corner is not even visible.
        // The attribution obligation does not go away with them: the notice the data
        // carries is fetched from the phone and drawn below instead, which costs one
        // line instead of two widgets.
        isAttributionEnabled = false
        isLogoEnabled = false
    }
}

private fun addOverlayLayers(style: Style) {
    style.addSource(GeoJsonSource(ROUTE_SOURCE))
    style.addSource(GeoJsonSource(WAYPOINT_SOURCE))
    style.addSource(GeoJsonSource(OWNSHIP_SOURCE))
    style.addSource(GeoJsonSource(TRAFFIC_SOURCE))
    style.addSource(GeoJsonSource(TRAFFIC_TRACK_SOURCE))

    style.addLayer(
        LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
            PropertyFactory.lineColor(ROUTE_COLOUR),
            PropertyFactory.lineWidth(ROUTE_WIDTH_DP),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    style.addLayer(
        CircleLayer(WAYPOINT_LAYER, WAYPOINT_SOURCE).withProperties(
            PropertyFactory.circleRadius(WAYPOINT_RADIUS_DP),
            PropertyFactory.circleColor(WAYPOINT_FILL),
            PropertyFactory.circleStrokeWidth(WAYPOINT_STROKE_DP),
            PropertyFactory.circleStrokeColor(ROUTE_COLOUR),
        ),
    )
    // Above the route and below the aircraft: traffic must not be hidden by a course
    // line, and must not hide where the pilot is.
    style.addLayer(
        LineLayer(TRAFFIC_TRACK_LAYER, TRAFFIC_TRACK_SOURCE).withProperties(
            PropertyFactory.lineColor(Expression.get("colour")),
            PropertyFactory.lineWidth(TRAFFIC_TRACK_WIDTH_DP),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        ),
    )
    style.addLayer(
        CircleLayer(TRAFFIC_LAYER, TRAFFIC_SOURCE).withProperties(
            PropertyFactory.circleRadius(TRAFFIC_RADIUS_DP),
            // The colour travels per feature, because it is the phone's answer for
            // that target's alarm level and not a property of the layer.
            PropertyFactory.circleColor(Expression.get("colour")),
            PropertyFactory.circleStrokeWidth(WAYPOINT_STROKE_DP),
            PropertyFactory.circleStrokeColor(TRAFFIC_STROKE),
        ),
    )
    style.addLayer(
        CircleLayer(OWNSHIP_LAYER, OWNSHIP_SOURCE).withProperties(
            PropertyFactory.circleRadius(OWNSHIP_RADIUS_DP),
            PropertyFactory.circleColor(OWNSHIP_COLOUR),
            PropertyFactory.circleStrokeWidth(WAYPOINT_STROKE_DP),
            PropertyFactory.circleStrokeColor(OWNSHIP_STROKE),
        ),
    )
}

private const val ROUTE_SOURCE = "enroute-route"
private const val WAYPOINT_SOURCE = "enroute-waypoints"
private const val OWNSHIP_SOURCE = "enroute-ownship"
private const val ROUTE_LAYER = "enroute-route-line"
private const val WAYPOINT_LAYER = "enroute-waypoint-dots"
private const val OWNSHIP_LAYER = "enroute-ownship-dot"
private const val TRAFFIC_SOURCE = "enroute-traffic"
private const val TRAFFIC_TRACK_SOURCE = "enroute-traffic-tracks"
private const val TRAFFIC_LAYER = "enroute-traffic-dots"
private const val TRAFFIC_TRACK_LAYER = "enroute-traffic-tracks"
private const val CHART_SOURCE = "enroute-vac"
private const val CHART_LAYER = "enroute-vac-raster"

// The protocol prefix. Named here because the chart URL is the one URL this file
// builds itself rather than receiving ready-made.
private const val PATH_PREFIX = "/enroute/v1"

// Colours as ints, because the renderer's property factory takes Android colours and
// not Compose ones. Kept in step with CockpitColors by hand, which is a small enough
// surface to be worth the loss of a dependency in the other direction.
private const val ROUTE_COLOUR = 0xFF4FD8FF.toInt()
private const val WAYPOINT_FILL = 0xFF000000.toInt()
private const val OWNSHIP_COLOUR = 0xFF4FD8FF.toInt()
private const val OWNSHIP_STROKE = 0xFF000000.toInt()

private const val ROUTE_WIDTH_DP = 3.0f
private const val WAYPOINT_RADIUS_DP = 4.0f
private const val WAYPOINT_STROKE_DP = 2.0f
private const val OWNSHIP_RADIUS_DP = 6.0f
private const val TRAFFIC_RADIUS_DP = 5.0f
private const val TRAFFIC_TRACK_WIDTH_DP = 2.0f
private const val TRAFFIC_STROKE = 0xFF000000.toInt()

// Used when the phone sends a colour this client cannot parse. Grey rather than a
// guessed alarm colour: an unreadable colour must not become a reassuring one.
private const val TRAFFIC_FALLBACK_COLOUR = "#B3B3B3"

// How long the direction line is, in metres. Long enough to read the heading at the
// zoom levels a watch uses, short enough not to look like a predicted path.
private const val TRACK_LENGTH_M = 900.0

// One map pixel per screen pixel. See the note where the options are built.
private const val MAP_PIXEL_RATIO = 1.0f

// Enough to lift a glyph off a road casing without turning it into a smudge.
private const val HALO_BLUR = 4.0f

private const val DEFAULT_HALF_SPAN_M = 20_000.0
private const val MIN_HALF_SPAN_M = 1852.0
private const val AUTOMATIC_MARGIN = 1.25

/**
 * A point the given distance from another along a bearing.
 *
 * Flat-earth arithmetic, which is exact enough for a line under a kilometre long and
 * avoids pulling in a geodesy dependency for one direction tick.
 */
private fun offset(from: GeoPoint, bearingDeg: Double, distanceM: Double): GeoPoint {
    val bearing = Math.toRadians(bearingDeg)
    val northM = distanceM * cos(bearing)
    val eastM = distanceM * sin(bearing)
    val latDeg = from.latDeg + northM / METRES_PER_DEGREE
    val lonDeg = from.lonDeg +
        eastM / (METRES_PER_DEGREE * cos(Math.toRadians(from.latDeg)).coerceAtLeast(0.01))
    return GeoPoint(latDeg = latDeg, lonDeg = lonDeg)
}

private const val METRES_PER_DEGREE = 111_320.0
