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

import de.akaflieg_freiburg.enroute.wear.data.dto.NavFrameDto
import de.akaflieg_freiburg.enroute.wear.data.dto.RouteDto
import de.akaflieg_freiburg.enroute.wear.data.toDomain
import de.akaflieg_freiburg.enroute.wear.domain.FrequencyKind
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frequencies, read from documents shaped like the ones the phone actually sends.
 *
 * The samples are trimmed copies of a real exchange with a Galaxy S24 carrying the AIP
 * VFR Germany data: EDDS with four COM entries and an ATIS, FARRENBERG with a single
 * glider radio, and the Langen sector that covers the field.
 */
class FrequencyTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a waypoint's frequencies arrive split into a station and a number`() {
        val route = json.decodeFromString(
            RouteDto.serializer(),
            """
            {"v":1,"routeRev":2,"name":"EDDS - FARRENBERG","wp":[
              {"n":"EDDS","en":"STUTTGART","c":[9.22196,48.68988],"t":"AD","cat":"AD-PAVED",
               "freq":[{"k":"inf","s":"ATIS","f":"126.130"},
                       {"k":"com","s":"STUTTGART TOWER","f":"118.805"},
                       {"k":"com","s":"STUTTGART GROUND","f":"118.605"}]}
            ],"legs":[]}
            """.trimIndent(),
        ).toDomain()

        val frequencies = route.waypoints.single().frequencies
        assertEquals(3, frequencies.size)
        assertEquals(FrequencyKind.Information, frequencies[0].kind)
        assertEquals("ATIS", frequencies[0].station)
        assertEquals("126.130", frequencies[0].value)
        assertEquals(FrequencyKind.Communication, frequencies[1].kind)
        assertEquals("STUTTGART TOWER", frequencies[1].station)
        assertEquals("118.805", frequencies[1].value)
    }

    @Test
    fun `a waypoint without frequencies has an empty list, not a null`() {
        val route = json.decodeFromString(
            RouteDto.serializer(),
            """{"v":1,"wp":[{"n":"OSCAR","c":[9.33594,48.62634],"t":"WP","cat":"MRP"}],"legs":[]}""",
        ).toDomain()
        assertTrue(route.waypoints.single().frequencies.isEmpty())
    }

    @Test
    fun `a station the phone knows without a number keeps its name`() {
        // The app files some NAV entries as a bare identifier. Dropping them would hide
        // that the station exists; sending an empty string as the number would read as
        // a frequency of nothing.
        val route = json.decodeFromString(
            RouteDto.serializer(),
            """{"v":1,"wp":[{"n":"EDTF","c":[7.83,48.02],"t":"AD","cat":"AD-GRASS",
                 "freq":[{"k":"nav","s":"FREIBURG NDB"}]}],"legs":[]}""",
        ).toDomain()
        val frequency = route.waypoints.single().frequencies.single()
        assertEquals("FREIBURG NDB", frequency.station)
        assertNull(frequency.value)
    }

    @Test
    fun `an unknown group is filed as other rather than dropped`() {
        // A later phone may name a group this build has never heard of. Showing it
        // under a generic heading is better than a pilot missing a frequency.
        assertEquals(FrequencyKind.Other, FrequencyKind.fromWire("something-new"))
        assertEquals(FrequencyKind.Other, FrequencyKind.fromWire(null))
        assertEquals(FrequencyKind.Communication, FrequencyKind.fromWire("com"))
    }

    @Test
    fun `the frame carries the sector the aircraft is in`() {
        val frame = json.decodeFromString(
            NavFrameDto.serializer(),
            """
            {"v":1,"navRev":9,"status":"onRoute","own":{"c":[9.21,48.56]},
             "fis":[{"s":"LANGEN INFORMATION","f":"126.950","a":"ALPINE AREA LANGEN",
                     "bot":"GND","top":"FL 130"}]}
            """.trimIndent(),
        ).toDomain()

        val sector = frame.fis.single()
        assertEquals("LANGEN INFORMATION", sector.station)
        assertEquals("126.950", sector.value)
        assertEquals("ALPINE AREA LANGEN", sector.area)
        assertEquals("GND", sector.bottom)
        assertEquals("FL 130", sector.top)
    }

    @Test
    fun `no sector is an empty list, which is what an old phone sends`() {
        // A phone from before this feature omits the member entirely, and a phone with
        // no position omits it too. Both have to read as "nothing to show" rather than
        // crash the parser.
        val frame = json.decodeFromString(
            NavFrameDto.serializer(),
            """{"v":1,"navRev":9,"status":"onRoute"}""",
        ).toDomain()
        assertTrue(frame.fis.isEmpty())
    }
}
