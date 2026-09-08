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
import de.akaflieg_freiburg.enroute.wear.data.dto.NotamBoardDto
import de.akaflieg_freiburg.enroute.wear.data.toDomain
import de.akaflieg_freiburg.enroute.wear.domain.NotamCategory
import de.akaflieg_freiburg.enroute.wear.domain.NotamKnowledge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The NOTAM document, tested against literal JSON for the same reason as the rest of the
 * protocol: the producer is a separately built C++ implementation, so a round trip
 * through Kotlin would only prove the serializer agrees with itself.
 *
 * Most of what is checked here is the three-way distinction between NOTAMs being listed,
 * confirmed absent, and simply unknown. Collapsing those three into "the list is empty"
 * is the single mistake this feature can make that would mislead a pilot, so it gets
 * more tests than anything else.
 */
class NotamProtocolTest {

    private fun board(json: String) =
        WireJson.json.decodeFromString<NotamBoardDto>(json).toDomain()

    @Test
    fun `a retrieved group with no NOTAMs means there are none`() {
        val group = board(THREE_WAYS).groups.single { it.name == "EDTL" }

        assertTrue(group.hasData)
        assertTrue(group.notams.isEmpty())
        assertEquals(NotamKnowledge.ConfirmedNone, group.knowledge)
    }

    @Test
    fun `a group that was never retrieved means we do not know`() {
        val group = board(THREE_WAYS).groups.single { it.name == "EDSB" }

        assertFalse(group.hasData)
        assertEquals(NotamKnowledge.Unknown, group.knowledge)
    }

    @Test
    fun `a group emptied by the document cap is not reported as having none`() {
        val group = board(THREE_WAYS).groups.single { it.name == "EDNY" }

        // Everything about this group except cut says "empty and retrieved", which is
        // exactly the trap: without cut it would render as "no NOTAMs here".
        assertTrue(group.hasData)
        assertTrue(group.notams.isEmpty())
        assertEquals(2, group.cut)
        assertEquals(NotamKnowledge.Incomplete, group.knowledge)
    }

    @Test
    fun `a missing data member is read as unknown rather than as none`() {
        // A document that fails to say must not be given the benefit of the doubt.
        val group = board(
            """{"v":1,"groups":[{"wp":0,"n":"EDTF"}]}""",
        ).groups.single()

        assertFalse(group.hasData)
        assertEquals(NotamKnowledge.Unknown, group.knowledge)
    }

    @Test
    fun `a NOTAM without an end date is permanent rather than expired`() {
        val notam = board(FULL).groups.first().notams.single { it.number == "A0087/26" }

        assertNotNull(notam.fromEpochSeconds)
        assertNull(notam.toEpochSeconds)
        assertTrue(notam.isPermanent)
    }

    @Test
    fun `an unknown category falls back to Other instead of throwing`() {
        val notams = board(FULL).groups.first().notams

        assertEquals(NotamCategory.RestrictedArea, notams.single { it.number == "A1000/26" }.category)
        assertEquals(NotamCategory.Obstacle, notams.single { it.number == "A0087/26" }.category)
        // A category a future phone might add.
        assertEquals(NotamCategory.Other, notams.single { it.number == "A9999/26" }.category)
    }

    @Test
    fun `an unparsable timestamp becomes absent rather than an exception`() {
        val notam = board(FULL).groups.first().notams.single { it.number == "A9999/26" }

        assertNull(notam.fromEpochSeconds)
        assertNull(notam.toEpochSeconds)
    }

    @Test
    fun `a NOTAM with no number is dropped but counted as cut`() {
        // Dropping it silently would turn a group with one unusable entry into a group
        // that claims to be complete.
        val group = board(
            """
            {"v":1,"groups":[{"wp":0,"n":"EDTF","data":true,
              "notams":[{"n":"","txt":"no number"},{"n":"A1/26","txt":"fine"}]}]}
            """.trimIndent(),
        ).groups.single()

        assertEquals(1, group.notams.size)
        assertEquals(1, group.cut)
        // Incomplete even though one entry did arrive: what matters is that not all of
        // them did. A group reported as Listed tells the pilot they have seen the lot.
        assertEquals(NotamKnowledge.Incomplete, group.knowledge)
    }

    @Test
    fun `a group is complete only when nothing was cut from it`() {
        val groups = board(FULL).groups

        assertEquals(NotamKnowledge.Listed, groups.single().knowledge)
        assertEquals(0, groups.single().cut)
    }

    @Test
    fun `an area is dropped when its radius is not positive`() {
        val notams = board(FULL).groups.first().notams

        assertNotNull(notams.single { it.number == "A1000/26" }.area)
        // r = 0 is not a circle, and drawing it would put a dot on the map that means
        // nothing.
        assertNull(notams.single { it.number == "A9999/26" }.area)
    }

    @Test
    fun `filter limits travel with the document`() {
        val filter = board(FULL).filter

        assertEquals(37040.0, filter.radiusM!!, 0.001)
        assertTrue(filter.horizontalOnly)
        assertFalse(filter.flightLevelApplied)
    }

    @Test
    fun `an empty warning is treated as no warning`() {
        assertNull(board("""{"v":1,"warning":"","groups":[]}""").warning)
        assertEquals(
            "NOTAMs not current around waypoint, requesting update",
            board(THREE_WAYS).warning,
        )
    }

    @Test
    fun `unknown members do not break decoding`() {
        // Adding a member is not a breaking change in this protocol, so an older watch
        // has to survive a newer phone.
        val decoded = board(
            """{"v":1,"groups":[{"wp":0,"n":"EDTF","data":true,"futureThing":42}],"newTopLevel":"x"}""",
        )

        assertEquals(1, decoded.groups.size)
        assertEquals(NotamKnowledge.ConfirmedNone, decoded.groups.single().knowledge)
    }

    private companion object {
        val THREE_WAYS = """
            {
              "v": 1, "sid": 2748219411, "notamRev": 4,
              "warning": "NOTAMs not current around waypoint, requesting update",
              "filter": { "radius": 37040, "horizontalOnly": true, "flightLevelApplied": false },
              "groups": [
                { "wp": 0, "n": "EDNY", "data": true, "retrieved": "2026-09-03T06:12:44Z", "cut": 2 },
                { "wp": 1, "n": "EDTL", "data": true, "retrieved": "2026-09-03T06:12:51Z" },
                { "wp": 2, "n": "EDSB", "data": false }
              ],
              "n": 0, "dropped": 2, "retrieved": "2026-09-03T06:12:44Z"
            }
        """.trimIndent()

        val FULL = """
            {
              "v": 1, "sid": 2748219411, "notamRev": 9,
              "filter": { "radius": 37040, "horizontalOnly": true, "flightLevelApplied": false },
              "groups": [
                { "wp": 0, "n": "EDNY", "data": true, "retrieved": "2026-09-03T06:12:44Z",
                  "notams": [
                    { "n": "A1000/26", "icao": "EDNY", "txt": "AREA ACT", "cat": "NOTAM-RA",
                      "sect": "Current", "traffic": "IV", "read": false,
                      "from": "2026-09-01T06:00:00Z", "to": "2026-09-30T16:00:00Z",
                      "area": { "c": [9.51139, 47.67139], "r": 9260 } },
                    { "n": "A0087/26", "icao": "EDNY", "txt": "CRANE ERECTED", "cat": "NOTAM-OBST",
                      "sect": "Current", "read": false,
                      "from": "2026-08-14T00:00:00Z",
                      "area": { "c": [9.495, 47.665], "r": 3704 } },
                    { "n": "A9999/26", "txt": "SOMETHING NEW", "cat": "NOTAM-FUTURE",
                      "read": false, "from": "not a date", "to": "also not",
                      "area": { "c": [9.5, 47.6], "r": 0 } }
                  ] }
              ],
              "n": 3, "retrieved": "2026-09-03T06:12:44Z"
            }
        """.trimIndent()
    }
}
