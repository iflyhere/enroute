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

import de.akaflieg_freiburg.enroute.wear.ui.map.zoomForHalfSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/**
 * The one piece of the map page that can be silently wrong.
 *
 * Half-span in metres and a Web Mercator zoom number do not resemble each other -- one
 * is linear, the other logarithmic in tile subdivisions, and the relation between them
 * depends on latitude. A mistake here does not throw; it just shows the wrong amount of
 * ground, which in a cockpit is worse.
 */
class ZoomForHalfSpanTest {

    private val radius = 227f   // half of a 454 pixel watch face

    /** What the renderer's own definition says the zoom must be. */
    private fun expected(halfSpanMetres: Double, latitudeDeg: Double): Double {
        val metresPerPixel = halfSpanMetres / radius
        val equatorial = 156543.03392804097 * cos(Math.toRadians(latitudeDeg))
        return kotlin.math.ln(equatorial / metresPerPixel) / kotlin.math.ln(2.0)
    }

    @Test
    fun `matches the Web Mercator definition at mid latitude`() {
        val z = zoomForHalfSpan(20_000.0, radius, 48.0)
        assertEquals(expected(20_000.0, 48.0), z, 1e-9)
    }

    @Test
    fun `halving the span adds exactly one zoom level`() {
        val wide = zoomForHalfSpan(20_000.0, radius, 48.0)
        val close = zoomForHalfSpan(10_000.0, radius, 48.0)
        assertEquals(1.0, close - wide, 1e-9)
    }

    @Test
    fun `the same span needs a lower zoom further north`() {
        // Worth stating carefully, because the intuition points the other way. A fixed
        // zoom number covers less ground the further from the equator you are, so to
        // keep covering the same 20 km you have to zoom out, not in.
        val south = zoomForHalfSpan(20_000.0, radius, 0.0)
        val north = zoomForHalfSpan(20_000.0, radius, 60.0)
        assertTrue("expected $north < $south", north < south)
        // cos(60) = 0.5, so it is exactly one zoom level.
        assertEquals(1.0, south - north, 1e-9)
    }

    @Test
    fun `a nonsense span falls back instead of returning infinity`() {
        assertEquals(9.0, zoomForHalfSpan(0.0, radius, 48.0), 1e-9)
        assertEquals(9.0, zoomForHalfSpan(-5.0, radius, 48.0), 1e-9)
        assertEquals(9.0, zoomForHalfSpan(20_000.0, 0f, 48.0), 1e-9)
    }

    @Test
    fun `the result stays inside what the renderer accepts`() {
        // A span of a metre would otherwise ask for zoom 25, and a span of half the
        // planet for a negative one.
        assertTrue(zoomForHalfSpan(1.0, radius, 48.0) <= 14.0)
        assertTrue(zoomForHalfSpan(20_000_000.0, radius, 48.0) >= 4.0)
    }
}
