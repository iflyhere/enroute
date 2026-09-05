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

package de.akaflieg_freiburg.enroute.wear

import androidx.compose.ui.geometry.Offset
import de.akaflieg_freiburg.enroute.wear.domain.GeoPoint
import de.akaflieg_freiburg.enroute.wear.ui.route.LocalProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class LocalProjectionTest {

    private val centre = GeoPoint(latDeg = 48.5, lonDeg = 9.2)
    private val viewCentre = Offset(227f, 227f)

    private fun projection(metresPerPixel: Double = 500.0, rotationRad: Double = 0.0) =
        LocalProjection(centre, metresPerPixel, viewCentre, rotationRad)

    @Test
    fun `the centre lands in the middle of the view`() {
        val screen = projection().toScreen(centre)
        assertEquals(227f, screen.x, 0.01f)
        assertEquals(227f, screen.y, 0.01f)
    }

    @Test
    fun `north is up and east is right`() {
        val north = GeoPoint(centre.latDeg + 0.1, centre.lonDeg)
        val east = GeoPoint(centre.latDeg, centre.lonDeg + 0.1)

        // Screen y grows downwards, so north must be a smaller y.
        assertTrue(projection().toScreen(north).y < viewCentre.y)
        assertTrue(projection().toScreen(east).x > viewCentre.x)
    }

    @Test
    fun `the scale is the same on both axes`() {
        // This is the whole reason for not using Mercator: a drawn course has to be
        // the real course, which only holds if a metre north and a metre east are the
        // same number of pixels.
        val metresPerPixel = 500.0
        val distanceM = 20_000.0

        val north = GeoPoint(
            centre.latDeg + distanceM / LocalProjection.METRES_PER_DEGREE_LAT,
            centre.lonDeg,
        )
        val east = GeoPoint(
            centre.latDeg,
            centre.lonDeg + distanceM / LocalProjection.metresPerDegreeLonAt(centre.latDeg),
        )

        val p = projection(metresPerPixel)
        val northPixels = abs(p.toScreen(north).y - viewCentre.y)
        val eastPixels = abs(p.toScreen(east).x - viewCentre.x)

        assertEquals(distanceM / metresPerPixel, northPixels.toDouble(), 0.5)
        assertEquals(northPixels, eastPixels, 0.5f)
    }

    @Test
    fun `screen and geographic coordinates round trip`() {
        val p = projection()
        val point = GeoPoint(48.62, 9.41)
        val back = p.toGeo(p.toScreen(point))
        assertEquals(point.latDeg, back.latDeg, 1e-6)
        assertEquals(point.lonDeg, back.lonDeg, 1e-6)
    }

    @Test
    fun `rotation puts the given direction at the top`() {
        // Track up means rotating by the negated track: flying east, what is ahead
        // has to appear above the aircraft.
        val ahead = GeoPoint(centre.latDeg, centre.lonDeg + 0.1)
        val trackUp = projection(rotationRad = -90.0 * LocalProjection.DEG_TO_RAD)
        val screen = trackUp.toScreen(ahead)

        assertTrue("expected east to be above the centre, was ${screen.y}", screen.y < viewCentre.y)
        assertEquals(viewCentre.x, screen.x, 1.0f)
    }

    @Test
    fun `rotation still round trips`() {
        val p = projection(rotationRad = 1.1)
        val point = GeoPoint(48.4, 9.05)
        val back = p.toGeo(p.toScreen(point))
        assertEquals(point.latDeg, back.latDeg, 1e-6)
        assertEquals(point.lonDeg, back.lonDeg, 1e-6)
    }

    @Test
    fun `every point of a route fits inside the disc`() {
        val route = listOf(
            GeoPoint(48.6899, 9.2220),
            GeoPoint(47.6713, 9.5115),
            GeoPoint(48.7794, 8.0805),
            GeoPoint(48.0227, 7.8326),
        )
        val usableRadius = 200f
        val fitCentre = LocalProjection.centreOf(route)!!
        val scale = LocalProjection.fitToDisc(route, fitCentre, usableRadius)
        val p = LocalProjection(fitCentre, scale, viewCentre)

        route.forEach { point ->
            val screen = p.toScreen(point)
            val radius = hypot(
                (screen.x - viewCentre.x).toDouble(),
                (screen.y - viewCentre.y).toDouble(),
            )
            assertTrue(
                "point at radius $radius exceeded the usable $usableRadius",
                radius <= usableRadius + 0.5,
            )
        }
    }

    @Test
    fun `a single waypoint does not zoom in indefinitely`() {
        val single = listOf(GeoPoint(48.5, 9.2))
        val scale = LocalProjection.fitToDisc(single, single.first(), 200f)
        assertEquals(LocalProjection.MIN_HALF_SPAN_M / 200.0, scale, 1e-9)
    }

    @Test
    fun `the centre is the middle of the extent, not the mean`() {
        // Three waypoints clustered in the west and one far east. The mean would sit
        // among the cluster and push the eastern one off the display.
        val lopsided = listOf(
            GeoPoint(48.0, 8.0),
            GeoPoint(48.1, 8.05),
            GeoPoint(48.05, 8.1),
            GeoPoint(48.0, 10.0),
        )
        val centreOfExtent = LocalProjection.centreOf(lopsided)!!
        assertEquals(9.0, centreOfExtent.lonDeg, 1e-9)
        assertEquals(48.05, centreOfExtent.latDeg, 1e-9)
    }

    @Test
    fun `a route across the antimeridian stays contiguous`() {
        val across = listOf(GeoPoint(-17.0, 179.0), GeoPoint(-17.5, -179.0))
        val fitCentre = LocalProjection.centreOf(across)!!

        // The midpoint is at 180, not somewhere near the prime meridian.
        val normalised = LocalProjection.normaliseLon(fitCentre.lonDeg - 180.0)
        assertEquals(0.0, normalised, 1e-9)

        // And the two points end up a short distance apart, not half a world.
        val scale = LocalProjection.fitToDisc(across, fitCentre, 200f)
        val p = LocalProjection(fitCentre, scale, viewCentre)
        val separation = hypot(
            (p.toScreen(across[0]).x - p.toScreen(across[1]).x).toDouble(),
            (p.toScreen(across[0]).y - p.toScreen(across[1]).y).toDouble(),
        )
        assertTrue("points were $separation px apart", separation <= 401.0)
    }

    @Test
    fun `longitude differences normalise into a half turn`() {
        assertEquals(0.0, LocalProjection.normaliseLon(360.0), 1e-9)
        assertEquals(-2.0, LocalProjection.normaliseLon(358.0), 1e-9)
        assertEquals(2.0, LocalProjection.normaliseLon(-358.0), 1e-9)
        assertEquals(180.0, LocalProjection.normaliseLon(180.0), 1e-9)
    }
}
