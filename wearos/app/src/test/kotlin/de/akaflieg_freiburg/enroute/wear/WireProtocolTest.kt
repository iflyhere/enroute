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

import de.akaflieg_freiburg.enroute.wear.data.WireJson
import de.akaflieg_freiburg.enroute.wear.data.dto.NavFrameDto
import de.akaflieg_freiburg.enroute.wear.data.dto.RouteDto
import de.akaflieg_freiburg.enroute.wear.data.toDomain
import de.akaflieg_freiburg.enroute.wear.domain.Measured
import de.akaflieg_freiburg.enroute.wear.domain.RouteStatus
import de.akaflieg_freiburg.enroute.wear.domain.WaypointType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire protocol is a contract with a separately built C++ implementation, so these
 * tests are written against literal JSON rather than against round-tripped Kotlin
 * objects. A round trip would only prove the serializer agrees with itself.
 */
class WireProtocolTest {

    private fun nav(json: String) = WireJson.json.decodeFromString<NavFrameDto>(json).toDomain()

    @Test
    fun `onRoute frame carries next and final legs with the phone's display strings`() {
        val frame = nav(ON_ROUTE)

        assertEquals(RouteStatus.OnRoute, frame.status)
        assertTrue(frame.status.hasLegData)

        val next = assertNotNull(frame.next).let { frame.next!! }
        assertEquals("KIRCHZARTEN", next.name)
        // The rendered text comes from the phone; the SI value is for drawing and maths.
        assertEquals("3.8 nm", next.distance.text)
        assertEquals(7014.0, next.distance.si!!, 0.001)
        assertEquals("0:03", next.eteText)
        assertEquals("9:47", next.etaText)
        assertEquals(121.8, next.trueCourseDeg!!, 0.001)

        val final = frame.final!!
        assertEquals("EDTL", final.name)
        assertEquals("28.4 nm", final.distance.text)

        assertEquals("90 kn", frame.position.groundSpeed.text)
        assertEquals("3,750 ft", frame.position.altitudeAmsl.text)
        assertTrue(frame.position.hasFix)
    }

    @Test
    fun `wire coordinates are longitude first`() {
        // GeoJSON axis order. Getting this backwards would put the aircraft in the sea
        // off Somalia rather than in the Black Forest, so it is worth pinning.
        val frame = nav(ON_ROUTE)
        val point = frame.position.point!!
        assertEquals(48.0, point.latDeg, 0.001)
        assertEquals(7.87, point.lonDeg, 0.001)
    }

    @Test
    fun `offRoute frame has no leg data and must not be displayed as if it did`() {
        val frame = nav(OFF_ROUTE)

        assertEquals(RouteStatus.OffRoute, frame.status)
        assertFalse(frame.status.hasLegData)
        assertNull(frame.next)
        assertNull(frame.final)
        // Ground speed and altitude remain valid in every status.
        assertEquals("90 kn", frame.position.groundSpeed.text)
        assertEquals("More than 5.0 nm off route.", frame.statusText)
    }

    @Test
    fun `a frame with every optional key absent decodes to placeholders`() {
        val frame = nav(ALL_ABSENT)

        assertEquals(RouteStatus.PositionUnknown, frame.status)
        assertNull(frame.next)
        assertNull(frame.final)
        assertNull(frame.legIndex)
        assertFalse(frame.position.hasFix)
        assertNull(frame.position.groundSpeed.si)
        assertEquals(Measured.PLACEHOLDER, frame.position.groundSpeed.text)
    }

    @Test
    fun `an unrecognised status falls back to Unknown instead of throwing`() {
        // Forward compatibility: a newer phone must not break an older watch.
        val frame = nav(UNKNOWN_STATUS)
        assertEquals(RouteStatus.Unknown, frame.status)
        assertFalse(frame.status.hasLegData)
    }

    @Test
    fun `unknown keys are ignored`() {
        val frame = nav(WITH_FUTURE_KEYS)
        assertEquals(RouteStatus.OnRoute, frame.status)
        assertEquals("KIRCHZARTEN", frame.next!!.name)
    }

    @Test
    fun `route decodes waypoints, types and legs`() {
        val route = WireJson.json.decodeFromString<RouteDto>(ROUTE).toDomain()

        assertEquals(7L, route.revision)
        assertEquals(3, route.waypoints.size)
        assertEquals(2, route.legs.size)

        assertEquals("EDTF", route.waypoints[0].name)
        assertEquals("EDTF (FREIBURG)", route.waypoints[0].extendedName)
        assertEquals(WaypointType.Aerodrome, route.waypoints[0].type)
        assertEquals(244.0, route.waypoints[0].elevationM!!, 0.001)

        // A plain waypoint sends no extended name and no elevation.
        assertEquals(WaypointType.Waypoint, route.waypoints[1].type)
        assertNull(route.waypoints[1].extendedName)
        assertNull(route.waypoints[1].elevationM)

        assertEquals(0, route.legs[0].from)
        assertEquals(10730.0, route.legs[0].distanceM, 0.001)
        assertEquals(125.4, route.legs[0].trueCourseDeg!!, 0.001)
    }

    @Test
    fun `a leg shorter than the course threshold omits its true course`() {
        val route = WireJson.json.decodeFromString<RouteDto>(ROUTE_SHORT_LEG).toDomain()
        assertNull(route.legs[0].trueCourseDeg)
    }

    @Test
    fun `an unknown waypoint type maps to Unknown rather than failing`() {
        val route = WireJson.json.decodeFromString<RouteDto>(ROUTE_FUTURE_TYPE).toDomain()
        assertEquals(WaypointType.Unknown, route.waypoints[0].type)
    }

    private companion object {
        const val ON_ROUTE = """
        { "v": 1, "sid": 2748393211, "navRev": 1043, "routeRev": 7, "t": 1788255900,
          "status": "onRoute", "flightStatus": "flight", "note": "", "leg": 1,
          "own": {"c":[7.87,48.0],"alt":1143,"agl":812,"gs":46.3,"tt":122.4,"vs":-0.5},
          "next": {"n":"KIRCHZARTEN","dist":7014,"ete":152,"eta":1788256052,"tc":121.8},
          "final": {"n":"EDTL","dist":52664,"ete":1137,"eta":1788257037},
          "fmt": {"nextName":"KIRCHZARTEN","nextDist":"3.8 nm","nextETE":"0:03",
                  "nextETA":"9:47","nextTC":"122°","finalName":"EDTL",
                  "finalDist":"28.4 nm","finalETE":"0:19","finalETA":"10:03",
                  "alt":"3,750 ft","gs":"90 kn","statusText":""} }
        """

        const val OFF_ROUTE = """
        { "v": 1, "sid": 1, "navRev": 5, "routeRev": 7, "t": 1788255900,
          "status": "offRoute", "flightStatus": "flight", "note": "",
          "own": {"c":[7.6,48.2],"alt":1143,"gs":46.3,"tt":122.4},
          "fmt": {"alt":"3,750 ft","gs":"90 kn",
                  "statusText":"More than 5.0 nm off route."} }
        """

        const val ALL_ABSENT = """
        { "v": 1, "sid": 1, "navRev": 96, "routeRev": 1, "t": 1788370033,
          "status": "positionUnknown", "flightStatus": "unknown", "note": "",
          "fmt": {"nextName":"-","nextDist":"-","nextETE":"-:--","nextETA":"-:--",
                  "nextTC":"-","alt":"-","gs":"-","statusText":"Position unknown."} }
        """

        const val UNKNOWN_STATUS = """
        { "v": 1, "sid": 1, "navRev": 1, "routeRev": 1, "t": 1788370033,
          "status": "somethingNewInAFutureRelease", "flightStatus": "flight" }
        """

        const val WITH_FUTURE_KEYS = """
        { "v": 1, "sid": 1, "navRev": 1, "routeRev": 7, "t": 1788255900,
          "status": "onRoute", "flightStatus": "flight",
          "xtkM": -430.5, "wind": {"dir": 270, "speed": 8.0},
          "next": {"n":"KIRCHZARTEN","dist":7014,"ete":152,"unheardOf":true},
          "fmt": {"nextName":"KIRCHZARTEN","nextDist":"3.8 nm","statusText":""} }
        """

        const val ROUTE = """
        { "v": 1, "sid": 2748393211, "routeRev": 7,
          "name": "EDTF (FREIBURG) - EDTL (LAHR)",
          "summary": "Total: 30.6 nm • ETE 0:20 h",
          "units": {"hDist":"nm","vDist":"ft"},
          "wp": [ {"n":"EDTF","en":"EDTF (FREIBURG)","c":[7.83258,48.02265],"e":244,"t":"AD","cat":"AD-GLD"},
                  {"n":"KIRCHZARTEN","c":[7.95,47.96667],"t":"WP","cat":"WP"},
                  {"n":"EDTL","en":"EDTL (LAHR)","c":[7.82778,48.36917],"e":156,"t":"AD","cat":"AD"} ],
          "legs": [ {"d":10730,"tc":125.4}, {"d":45650,"tc":348.5} ] }
        """

        const val ROUTE_SHORT_LEG = """
        { "v": 1, "sid": 1, "routeRev": 1, "name": "x", "summary": "y",
          "wp": [ {"n":"A","c":[7.0,48.0],"t":"WP"}, {"n":"B","c":[7.0001,48.0],"t":"WP"} ],
          "legs": [ {"d":8} ] }
        """

        const val ROUTE_FUTURE_TYPE = """
        { "v": 1, "sid": 1, "routeRev": 1, "name": "x", "summary": "y",
          "wp": [ {"n":"A","c":[7.0,48.0],"t":"HELIPAD"} ], "legs": [] }
        """
    }
}
