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

import de.akaflieg_freiburg.enroute.wear.domain.ApproachChart
import de.akaflieg_freiburg.enroute.wear.domain.GeoPoint
import de.akaflieg_freiburg.enroute.wear.domain.VacBoard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which approach chart is shown, and when.
 *
 * Worth testing rather than watching, because the alternative is waiting for a
 * simulated aircraft to fly into a box: the rule is a pure function, and the case that
 * matters -- two charts overlapping -- is the one a bench flight is least likely to
 * produce.
 */
class ApproachChartSelectionTest {

    private fun chart(
        name: String,
        west: Double,
        south: Double,
        east: Double,
        north: Double,
    ) = ApproachChart(
        name = name,
        description = null,
        section = null,
        quad = listOf(
            GeoPoint(north, west), GeoPoint(north, east),
            GeoPoint(south, east), GeoPoint(south, west),
        ),
        west = west,
        south = south,
        east = east,
        north = north,
    )

    private val freiburg = chart("EDTF", 7.70, 47.95, 7.95, 48.10)
    private val lahr = chart("EDTL", 7.75, 48.30, 7.95, 48.45)

    @Test
    fun `a position inside the box selects the chart`() {
        val board = VacBoard(revision = 1, available = true, charts = listOf(freiburg, lahr))
        val covering = board.coveringSortedByName(GeoPoint(48.02, 7.83))
        assertEquals(listOf("EDTF"), covering.map { it.name })
    }

    @Test
    fun `a position outside every box selects nothing`() {
        val board = VacBoard(revision = 1, available = true, charts = listOf(freiburg, lahr))
        assertTrue(board.coveringSortedByName(GeoPoint(48.20, 9.20)).isEmpty())
    }

    @Test
    fun `the edge of a box counts as inside`() {
        // The app's own test is QGeoRectangle::contains, which includes the boundary.
        // A chart that switched off exactly on its own edge would flicker along it.
        assertTrue(freiburg.contains(GeoPoint(47.95, 7.70)))
        assertTrue(freiburg.contains(GeoPoint(48.10, 7.95)))
        assertFalse(freiburg.contains(GeoPoint(48.10001, 7.95)))
    }

    @Test
    fun `overlapping charts are ordered by name so two devices agree`() {
        // Both boxes contain the point. The phone sorts its library by name, so the
        // watch has to as well, or the two show different charts for the same place.
        val overlapping = chart("EDTB", 7.60, 47.90, 8.10, 48.20)
        val board = VacBoard(
            revision = 1,
            available = true,
            charts = listOf(freiburg, overlapping),
        )
        val covering = board.coveringSortedByName(GeoPoint(48.02, 7.83))
        assertEquals(listOf("EDTB", "EDTF"), covering.map { it.name })
    }

    @Test
    fun `a chart name becomes a legal path segment`() {
        // Every name in a real library has spaces in it, and a raw space does not
        // merely fail to fetch: java.net.URI refuses to parse the URL and the map
        // page went down with it.
        assertEquals(
            "/map/vac/EDDS%20Stuttgart%203",
            chart("EDDS Stuttgart 3", 9.0, 48.5, 9.5, 48.8).imagePath,
        )
        assertEquals("/map/vac/EDTF", chart("EDTF", 7.6, 47.9, 8.1, 48.2).imagePath)
    }

    @Test
    fun `a chart name keeps its accents through the encoding`() {
        // The library is not only German. Percent encoding works on UTF-8 bytes, so
        // one non-ASCII character becomes two escapes, not one.
        assertEquals(
            "/map/vac/LF%C3%96%20Test",
            chart("LFÖ Test", 1.0, 2.0, 3.0, 4.0).imagePath,
        )
    }

    @Test
    fun `an unreachable library is not the same as an empty one`() {
        val unreachable = VacBoard(revision = 1, available = false, charts = emptyList())
        val empty = VacBoard(revision = 1, available = true, charts = emptyList())
        assertFalse(unreachable.available)
        assertTrue(empty.available)
        assertTrue(unreachable.coveringSortedByName(GeoPoint(48.02, 7.83)).isEmpty())
        assertTrue(empty.coveringSortedByName(GeoPoint(48.02, 7.83)).isEmpty())
    }
}
