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

import de.akaflieg_freiburg.enroute.wear.domain.GeoPoint
import de.akaflieg_freiburg.enroute.wear.domain.TrafficBoard
import de.akaflieg_freiburg.enroute.wear.domain.TrafficTarget
import de.akaflieg_freiburg.enroute.wear.service.shouldBuzz
import de.akaflieg_freiburg.enroute.wear.ui.traffic.bearingDeg
import de.akaflieg_freiburg.enroute.wear.ui.traffic.clockPosition
import de.akaflieg_freiburg.enroute.wear.ui.traffic.distanceM
import de.akaflieg_freiburg.enroute.wear.ui.traffic.labelledFixes
import de.akaflieg_freiburg.enroute.wear.ui.traffic.radarFixes
import de.akaflieg_freiburg.enroute.wear.ui.traffic.radarRangeM
import de.akaflieg_freiburg.enroute.wear.ui.traffic.relativeAltitudeLabel
import de.akaflieg_freiburg.enroute.wear.ui.traffic.steppedRangeM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The traffic display's arithmetic.
 *
 * Worth testing because every one of these is wrong in a way that looks plausible on
 * screen: a bearing off by the aircraft's track points at the wrong piece of sky, and
 * nothing about the picture says so.
 */
class RadarGeometryTest {

    private val freiburg = GeoPoint(48.0227, 7.8326)

    private fun targetAt(
        point: GeoPoint?,
        trackDeg: Double? = null,
        alarmLevel: Int = 0,
        rangeM: Double? = null,
        verticalM: Double? = null,
    ) = TrafficTarget(
        id = "TEST",
        callSign = null,
        alarmLevel = alarmLevel,
        colour = null,
        type = null,
        horizontalDistanceM = rangeM,
        verticalDistanceM = verticalM,
        description = null,
        relevant = true,
        point = point,
        trackDeg = trackDeg,
        uncertaintyRadiusM = null,
    )

    @Test
    fun `due north is zero degrees and due east is ninety`() {
        val north = GeoPoint(freiburg.latDeg + 0.1, freiburg.lonDeg)
        val east = GeoPoint(freiburg.latDeg, freiburg.lonDeg + 0.1)
        assertEquals(0.0, bearingDeg(freiburg, north), 0.5)
        assertEquals(90.0, bearingDeg(freiburg, east), 0.5)
    }

    @Test
    fun `a tenth of a degree of latitude is about eleven kilometres`() {
        val north = GeoPoint(freiburg.latDeg + 0.1, freiburg.lonDeg)
        assertEquals(11_130.0, distanceM(freiburg, north), 60.0)
    }

    @Test
    fun `the display is track-up when the track is known`() {
        // A target due north, with the aircraft heading east, is on the left of the
        // screen -- at nine o'clock, not twelve. Getting this backwards is the failure
        // that would send a pilot looking the wrong way.
        val north = GeoPoint(freiburg.latDeg + 0.05, freiburg.lonDeg)
        val fixes = radarFixes(listOf(targetAt(north)), freiburg, ownTrackDeg = 90.0)
        assertEquals(1, fixes.size)
        assertEquals(270.0, fixes[0].screenBearingDeg, 1.0)
        assertEquals(9, clockPosition(fixes[0].screenBearingDeg))
    }

    @Test
    fun `the display is north-up when the track is unknown`() {
        val north = GeoPoint(freiburg.latDeg + 0.05, freiburg.lonDeg)
        val fixes = radarFixes(listOf(targetAt(north)), freiburg, ownTrackDeg = null)
        assertEquals(0.0, fixes[0].screenBearingDeg, 1.0)
    }

    @Test
    fun `a target's track is rotated into the display's frame too`() {
        // A target flying north while the aircraft heads east appears to be flying
        // towards the top-left of the screen, not towards the top.
        val north = GeoPoint(freiburg.latDeg + 0.05, freiburg.lonDeg)
        val fixes = radarFixes(
            listOf(targetAt(north, trackDeg = 0.0)),
            freiburg,
            ownTrackDeg = 90.0,
        )
        assertEquals(270.0, fixes[0].screenTrackDeg!!, 0.001)
    }

    @Test
    fun `a target with no position is left out rather than placed at the centre`() {
        val fixes = radarFixes(listOf(targetAt(null)), freiburg, ownTrackDeg = 0.0)
        assertTrue(fixes.isEmpty())
    }

    @Test
    fun `nothing can be placed without an own position`() {
        val north = GeoPoint(freiburg.latDeg + 0.05, freiburg.lonDeg)
        assertTrue(radarFixes(listOf(targetAt(north)), null, 0.0).isEmpty())
    }

    @Test
    fun `the receiver's own range wins over the computed one`() {
        // The coordinates are extrapolated; the range is what the receiver measured.
        val north = GeoPoint(freiburg.latDeg + 0.05, freiburg.lonDeg)
        val fixes = radarFixes(listOf(targetAt(north, rangeM = 1234.0)), freiburg, 0.0)
        assertEquals(1234.0, fixes[0].rangeM, 0.001)
    }

    @Test
    fun `only the traffic the phone draws reaches the display`() {
        // Measured against a real Open Glider Network feed: nineteen contacts,
        // including airliners at FL320. The phone gates its own marker on this flag,
        // and without the same gate the scale went to 50 km and the gliders ended up
        // on the centre spot.
        val board = TrafficBoard.EMPTY.copy(
            targets = listOf(
                targetAt(freiburg, rangeM = 900.0),
                targetAt(freiburg, rangeM = 34_000.0, verticalM = 9200.0)
                    .copy(relevant = false),
            ),
        )
        assertEquals(1, board.drawable.size)
        assertEquals(900.0, board.drawable[0].horizontalDistanceM!!, 0.001)
        assertEquals(1_000.0, radarRangeM(radarFixes(board.drawable, freiburg, 0.0)), 0.001)
    }

    @Test
    fun `contacts outside the band are still in the list`() {
        // The user asked for the list and it carries what the radar does not. Losing
        // a contact entirely would be a different thing from not drawing it.
        val board = TrafficBoard.EMPTY.copy(
            targets = listOf(
                targetAt(freiburg, rangeM = 900.0),
                targetAt(freiburg, rangeM = 34_000.0).copy(relevant = false),
            ),
        )
        assertEquals(2, board.listed.size)
        assertEquals(1, board.drawable.size)
        assertFalse(board.hasDrawable && board.drawable.size == board.targets.size)
    }

    @Test
    fun `a receiver with nothing drawable is not the same as an empty sky`() {
        val nothingAtAll = TrafficBoard.EMPTY.copy(receiving = true)
        val allTooFar = TrafficBoard.EMPTY.copy(
            receiving = true,
            targets = listOf(targetAt(freiburg, rangeM = 34_000.0).copy(relevant = false)),
        )
        assertFalse(nothingAtAll.hasDrawable)
        assertFalse(allTooFar.hasDrawable)
        assertTrue(nothingAtAll.targets.isEmpty())
        assertTrue(allTooFar.targets.isNotEmpty())
    }

    @Test
    fun `only the nearest few are labelled`() {
        val many = (1..10).map { step ->
            targetAt(freiburg, rangeM = step * 500.0)
        }
        val fixes = radarFixes(many, freiburg, 0.0)
        val labelled = labelledFixes(fixes)
        assertEquals(6, labelled.size)
        // And they are the nearest ones, not the first six the receiver happened to
        // report.
        assertEquals(3_000.0, labelled.maxOf { fix -> fix.rangeM }, 0.001)
    }

    @Test
    fun `the range ladder steps rather than fitting the farthest target`() {
        val near = radarFixes(listOf(targetAt(freiburg, rangeM = 400.0)), freiburg, 0.0)
        val mid = radarFixes(listOf(targetAt(freiburg, rangeM = 3000.0)), freiburg, 0.0)
        val far = radarFixes(listOf(targetAt(freiburg, rangeM = 44_000.0)), freiburg, 0.0)
        assertEquals(500.0, radarRangeM(near), 0.001)
        assertEquals(5_000.0, radarRangeM(mid), 0.001)
        assertEquals(50_000.0, radarRangeM(far), 0.001)
        // No targets at all still gives a usable scale rather than zero.
        assertEquals(500.0, radarRangeM(emptyList()), 0.001)
    }

    @Test
    fun `dead ahead is twelve o'clock and not zero`() {
        assertEquals(12, clockPosition(0.0))
        assertEquals(12, clockPosition(359.0))
        assertEquals(1, clockPosition(30.0))
        assertEquals(3, clockPosition(90.0))
        assertEquals(6, clockPosition(180.0))
        assertEquals(11, clockPosition(-30.0))
    }

    @Test
    fun `height differences round to what the instrument can honestly show`() {
        assertEquals("+700 ft", relativeAltitudeLabel(213.4, "ft"))
        assertEquals("-1400 ft", relativeAltitudeLabel(-430.0, "ft"))
        // Within one step of level reads as level rather than as a false precision.
        assertEquals("0 ft", relativeAltitudeLabel(9.0, "ft"))
        assertEquals("+200 m", relativeAltitudeLabel(213.4, "m"))
        assertEquals("0 m", relativeAltitudeLabel(12.0, "m"))
        assertEquals(null, relativeAltitudeLabel(null, "ft"))
        assertEquals(null, relativeAltitudeLabel(Double.NaN, "ft"))
    }

    @Test
    fun `the wrist buzzes when the alarm rises and not while it holds`() {
        // The behaviour that keeps a level-two encounter from shaking the watch for
        // half a minute, which would stop it meaning anything.
        assertTrue(shouldBuzz(previous = 0, current = 1))
        assertTrue(shouldBuzz(previous = 1, current = 3))
        assertFalse(shouldBuzz(previous = 2, current = 2))
        assertFalse(shouldBuzz(previous = 3, current = 1))
        assertFalse(shouldBuzz(previous = 1, current = 0))
        assertFalse(shouldBuzz(previous = 0, current = 0))
    }

    @Test
    fun `the range control steps the ladder and stops at both ends`() {
        assertEquals(500.0, steppedRangeM(1_000.0, -1), 0.001)
        assertEquals(2_000.0, steppedRangeM(1_000.0, 1), 0.001)
        // Pushing past the closest range gives the closest range, not the widest one.
        assertEquals(500.0, steppedRangeM(500.0, -3), 0.001)
        assertEquals(50_000.0, steppedRangeM(50_000.0, 3), 0.001)
    }
}
