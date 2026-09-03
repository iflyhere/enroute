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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.wear.compose.material3.Text
import de.akaflieg_freiburg.enroute.wear.domain.FlightRoute
import de.akaflieg_freiburg.enroute.wear.domain.GeoPoint
import de.akaflieg_freiburg.enroute.wear.domain.OwnPosition
import de.akaflieg_freiburg.enroute.wear.ui.route.ZoomLevel
import de.akaflieg_freiburg.enroute.wear.ui.theme.CockpitColors
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

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
 */
@Composable
fun MapLibreScreen(
    styleUrl: String,
    host: String,
    pairingCode: String,
    route: FlightRoute?,
    ownPosition: OwnPosition?,
    zoom: ZoomLevel,
    isActive: Boolean,
    attribution: String,
    fallbackCentre: GeoPoint?,
    fallbackZoom: Double,
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

                MapView(context).also { view ->
                    view.onCreate(null)
                    holder.view = view
                    view.getMapAsync { map ->
                        holder.map = map
                        configure(map)
                        map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                            holder.style = style
                            addOverlayLayers(style)
                            holder.applyRoute(route)
                            holder.applyOwnPosition(ownPosition)
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

        if (route == null || route.waypoints.isEmpty()) {
            Text(
                text = "No route",
                color = CockpitColors.Muted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp),
            )
        }

        if (attribution.isNotBlank()) {
            Text(
                text = attribution,
                color = CockpitColors.Muted,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 40.dp, vertical = 6.dp),
            )
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

/** Kept out of the composable so that Compose never has to reason about its identity. */
private class MapHolder {
    var view: MapView? = null
    var map: MapLibreMap? = null
    var style: Style? = null

    private var lastRouteRevision: Long = -1

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

private const val DEFAULT_HALF_SPAN_M = 20_000.0
private const val MIN_HALF_SPAN_M = 1852.0
private const val AUTOMATIC_MARGIN = 1.25
