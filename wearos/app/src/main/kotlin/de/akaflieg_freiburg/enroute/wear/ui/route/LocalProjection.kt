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

import androidx.compose.ui.geometry.Offset
import de.akaflieg_freiburg.enroute.wear.domain.GeoPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Maps geographic positions onto the display.
 *
 * A local equidistant-cylindrical projection: longitude scaled by the cosine of one
 * reference latitude for the whole view, latitude straight through. Chosen over Web
 * Mercator because the scale is then uniform, so a given length on the display means
 * the same distance everywhere and a drawn course is the real course. Over the tens to
 * few hundred nautical miles a flight route spans, the difference from a conformal
 * projection is far below what a 226 dp display can resolve.
 *
 * @param centre The position at the middle of the view; also the reference latitude
 * for the horizontal scale
 *
 * @param metresPerPixel Scale, equal on both axes
 *
 * @param centreOffset Middle of the view, in pixels
 *
 * @param rotationRad Zero for north up. Pass the negated track for track up.
 */
class LocalProjection(
    private val centre: GeoPoint,
    private val metresPerPixel: Double,
    private val centreOffset: Offset,
    rotationRad: Double = 0.0,
) {
    private val metresPerDegreeLon = metresPerDegreeLonAt(centre.latDeg)
    private val cosRotation = cos(rotationRad)
    private val sinRotation = sin(rotationRad)

    fun toScreen(point: GeoPoint): Offset {
        val east = metresPerDegreeLon * normaliseLon(point.lonDeg - centre.lonDeg) / metresPerPixel
        val south = -METRES_PER_DEGREE_LAT * (point.latDeg - centre.latDeg) / metresPerPixel
        return Offset(
            (centreOffset.x + east * cosRotation - south * sinRotation).toFloat(),
            (centreOffset.y + east * sinRotation + south * cosRotation).toFloat(),
        )
    }

    /** The inverse, so that a tap can be turned back into a position. */
    fun toGeo(screen: Offset): GeoPoint {
        val dx = (screen.x - centreOffset.x).toDouble()
        val dy = (screen.y - centreOffset.y).toDouble()
        val east = dx * cosRotation + dy * sinRotation
        val south = -dx * sinRotation + dy * cosRotation
        return GeoPoint(
            latDeg = centre.latDeg - south * metresPerPixel / METRES_PER_DEGREE_LAT,
            lonDeg = centre.lonDeg + east * metresPerPixel / metresPerDegreeLon,
        )
    }

    companion object {
        const val EARTH_RADIUS_M = 6_371_008.8
        const val DEG_TO_RAD = PI / 180.0
        const val METRES_PER_DEGREE_LAT = EARTH_RADIUS_M * DEG_TO_RAD

        /** Never zoom in tighter than this half-span, or a single point fills the disc. */
        const val MIN_HALF_SPAN_M = 1852.0

        fun metresPerDegreeLonAt(latDeg: Double): Double =
            EARTH_RADIUS_M * DEG_TO_RAD * cos(latDeg * DEG_TO_RAD)

        /**
         * Scale that fits every point inside a circle, not a rectangle.
         *
         * Fitting a bounding box to the display would push the corners of the route
         * under the bezel of a round watch. What matters is the largest distance from
         * the centre, so that is what gets scaled to the usable radius.
         *
         * @param points The positions that have to be visible
         *
         * @param centre The position at the middle of the view
         *
         * @param usableRadiusPixels Display radius minus the margin kept clear
         *
         * @returns Metres per pixel
         */
        fun fitToDisc(
            points: List<GeoPoint>,
            centre: GeoPoint,
            usableRadiusPixels: Float,
        ): Double {
            val radius = max(usableRadiusPixels.toDouble(), 1.0)
            val perDegreeLon = metresPerDegreeLonAt(centre.latDeg)
            val maxDistance = points.maxOfOrNull { point ->
                hypot(
                    perDegreeLon * normaliseLon(point.lonDeg - centre.lonDeg),
                    METRES_PER_DEGREE_LAT * (point.latDeg - centre.latDeg),
                )
            } ?: 0.0
            return max(maxDistance, MIN_HALF_SPAN_M) / radius
        }

        /**
         * Centre of a set of positions, as the middle of their extent.
         *
         * Deliberately not the mean: on an unevenly spaced route the mean drifts
         * towards wherever the waypoints are dense, and the view then no longer holds
         * the whole route.
         */
        fun centreOf(points: List<GeoPoint>): GeoPoint? {
            if (points.isEmpty()) {
                return null
            }
            val minLat = points.minOf { it.latDeg }
            val maxLat = points.maxOf { it.latDeg }
            // Longitudes are normalised against the first point, so a route crossing
            // the antimeridian stays contiguous instead of spanning the globe.
            val referenceLon = points.first().lonDeg
            val offsets = points.map { normaliseLon(it.lonDeg - referenceLon) }
            return GeoPoint(
                latDeg = (minLat + maxLat) / 2.0,
                lonDeg = referenceLon + (offsets.min() + offsets.max()) / 2.0,
            )
        }

        /** Brings a longitude difference into -180..180. */
        fun normaliseLon(differenceDeg: Double): Double {
            var value = differenceDeg
            while (value > 180.0) {
                value -= 360.0
            }
            while (value < -180.0) {
                value += 360.0
            }
            return value
        }

        /** Whether two positions are far enough apart to be worth drawing separately. */
        fun isDistinct(a: GeoPoint, b: GeoPoint, toleranceDeg: Double = 1e-7): Boolean =
            abs(a.latDeg - b.latDeg) > toleranceDeg ||
                abs(normaliseLon(a.lonDeg - b.lonDeg)) > toleranceDeg
    }
}
