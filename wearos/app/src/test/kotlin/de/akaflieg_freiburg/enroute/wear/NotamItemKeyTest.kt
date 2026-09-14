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
import de.akaflieg_freiburg.enroute.wear.ui.notam.notamItemKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards a crash that no amount of reading catches reliably.
 *
 * A lazy list throws when two items share a key, and it throws only once both are
 * measured in the same pass -- so the screen renders fine until the pilot scrolls far
 * enough. A NOTAM whose area covers two route waypoints is listed under both, which is
 * ordinary, so the keys have to be unique across the whole list and not just within a
 * group.
 */
class NotamItemKeyTest {

    @Test
    fun `the same NOTAM under two waypoints gets two distinct keys`() {
        val board = WireJson.json.decodeFromString<NotamBoardDto>(SHARED).toDomain()

        val keys = board.groups.flatMap { group ->
            group.notams.map { notamItemKey(group, it) }
        }

        assertEquals("both entries are present", 2, keys.size)
        assertEquals("and they are distinct", keys.size, keys.toSet().size)
    }

    @Test
    fun `keys stay distinct across a board with repeats and singletons`() {
        val board = WireJson.json.decodeFromString<NotamBoardDto>(MIXED).toDomain()

        val keys = board.groups.flatMap { group ->
            group.notams.map { notamItemKey(group, it) }
        }

        assertTrue("no key is reused", keys.size == keys.toSet().size)
    }

    private companion object {
        // One NOTAM, one region, two waypoints inside it. This is what the phone sends.
        val SHARED = """
            {"v":1,"groups":[
              {"wp":0,"n":"EDTF","data":true,"notams":[{"n":"W0100/26","txt":"ED-R 137 ACT"}]},
              {"wp":1,"n":"KIRCHZARTEN","data":true,"notams":[{"n":"W0100/26","txt":"ED-R 137 ACT"}]}
            ]}
        """.trimIndent()

        val MIXED = """
            {"v":1,"groups":[
              {"wp":0,"n":"EDTF","data":true,"notams":[
                {"n":"W0100/26","txt":"a"},{"n":"A1234/26","txt":"b"}]},
              {"wp":1,"n":"KIRCHZARTEN","data":true,"notams":[
                {"n":"W0100/26","txt":"a"},{"n":"A0087/26","txt":"c"}]},
              {"wp":2,"n":"EDSB","data":false},
              {"wp":3,"n":"EDTL","data":true,"notams":[{"n":"W0100/26","txt":"a"}]}
            ]}
        """.trimIndent()
    }
}
