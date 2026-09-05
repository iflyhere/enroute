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
import de.akaflieg_freiburg.enroute.wear.data.dto.PrefsDto
import de.akaflieg_freiburg.enroute.wear.data.toDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preferences document as the phone writes it.
 *
 * The decoding is what makes the phone's settings page work at all, and a field name
 * that does not match is silent: the value simply arrives as its default, which for
 * `pageOrder` means "your own default" and looks exactly like nothing having been set.
 */
class PreferenceAdoptionTest {

    /** Byte for byte what Snapshot::prefs emits, so a renamed field fails here. */
    private val fromThePhone = """
        {"v":1,"sid":123456,"prefsRev":7,
         "pageOrder":"map,data,notam","hiddenPages":"log,vacs",
         "bezel":"zoom","charts":"on","alarmVibration":false,"transport":"ble"}
    """.trimIndent()

    @Test
    fun `every field the phone sends is read`() {
        val prefs = WireJson.json.decodeFromString<PrefsDto>(fromThePhone).toDomain()
        assertEquals(7L, prefs.revision)
        assertEquals("map,data,notam", prefs.pageOrder)
        assertEquals("log,vacs", prefs.hiddenPages)
        assertEquals("zoom", prefs.bezel)
        assertEquals("on", prefs.charts)
        assertEquals(false, prefs.alarmVibration)
        assertEquals("ble", prefs.transport)
    }

    @Test
    fun `a phone that sends nothing leaves the watch on its own defaults`() {
        // An older phone, or one where nothing has ever been arranged. An empty page
        // order must read as "your own default" and never as "no screens at all".
        val prefs = WireJson.json.decodeFromString<PrefsDto>("""{"prefsRev":1}""").toDomain()
        assertEquals(1L, prefs.revision)
        assertTrue(prefs.pageOrder.isEmpty())
        assertTrue(prefs.hiddenPages.isEmpty())
        assertTrue(prefs.bezel.isEmpty())
        // Vibration is the exception: absent means on, because a collision alarm that
        // is silent by accident is the wrong way for a default to be wrong.
        assertEquals(true, prefs.alarmVibration)
    }

    @Test
    fun `a restarted phone is not ignored`() {
        // The revision counts up from one within a session, so a phone that has just
        // started says "revision 1" whatever it said before. Without the session id a
        // watch that had already applied revision three would ignore everything that
        // phone ever said again, and the settings page would look broken.
        val first = WireJson.json
            .decodeFromString<PrefsDto>("""{"sid":111,"prefsRev":3,"bezel":"zoom"}""")
            .toDomain()
        val afterRestart = WireJson.json
            .decodeFromString<PrefsDto>("""{"sid":222,"prefsRev":1,"bezel":"pages"}""")
            .toDomain()
        assertEquals(111L, first.sessionId)
        assertEquals(222L, afterRestart.sessionId)
        // A lower revision, and it must still be taken, because the session differs.
        assertTrue(afterRestart.revision < first.revision)
    }

    @Test
    fun `an unknown field does not stop the rest being read`() {
        // A phone from a later version. Failing here would mean a watch that ignores
        // every preference because one of them was new.
        val prefs = WireJson.json
            .decodeFromString<PrefsDto>("""{"prefsRev":2,"bezel":"zoom","future":"x"}""")
            .toDomain()
        assertEquals(2L, prefs.revision)
        assertEquals("zoom", prefs.bezel)
    }
}
