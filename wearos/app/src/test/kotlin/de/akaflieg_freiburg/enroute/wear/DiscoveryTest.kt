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

import de.akaflieg_freiburg.enroute.wear.data.Discovery
import de.akaflieg_freiburg.enroute.wear.ui.data.firstSentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoveryTest {

    @Test
    fun `parses the datagram the phone actually sends`() {
        // Byte for byte what Companion::HttpTransport::broadcast() writes. This is a
        // contract with a separately built C++ implementation, so the literal matters
        // more than a round trip through the Kotlin serializer would.
        val datagram =
            """{"App":"Enroute Flight Navigation","companion":{"port":8973,"v":1,"sid":3673448867}}"""

        val phone = Discovery.parseBeacon(datagram, "192.168.1.42")

        assertEquals("192.168.1.42", phone?.host)
        assertEquals(8973, phone?.port)
        assertEquals(1, phone?.protocolVersion)
        assertEquals(3673448867L, phone?.sessionId)
    }

    @Test
    fun `a session id above Int range survives`() {
        // The phone sends a quint32, so anything above 2^31 must not overflow.
        val datagram =
            """{"App":"Enroute Flight Navigation","companion":{"port":8973,"v":1,"sid":4294967295}}"""
        assertEquals(4294967295L, Discovery.parseBeacon(datagram, "10.0.0.1")?.sessionId)
    }

    @Test
    fun `the ForeFlight beacon on the same network is ignored`() {
        // TrafficDataProvider broadcasts this one, and it has no companion member.
        val foreFlight =
            """{"App":"Enroute Flight Navigation","GDL90":{"port":4000}}"""
        assertNull(Discovery.parseBeacon(foreFlight, "192.168.1.42"))
    }

    @Test
    fun `datagrams from other applications are ignored`() {
        val other = """{"App":"Some Other App","companion":{"port":8973,"v":1,"sid":1}}"""
        assertNull(Discovery.parseBeacon(other, "192.168.1.42"))
    }

    @Test
    fun `malformed datagrams do not throw`() {
        assertNull(Discovery.parseBeacon("not json at all", "192.168.1.42"))
        assertNull(Discovery.parseBeacon("", "192.168.1.42"))
        assertNull(Discovery.parseBeacon("{}", "192.168.1.42"))
    }

    @Test
    fun `unknown members in a future datagram are tolerated`() {
        val future =
            """{"App":"Enroute Flight Navigation","companion":{"port":8973,"v":1,"sid":7,"ble":true},"extra":1}"""
        assertEquals(7L, Discovery.parseBeacon(future, "10.0.0.1")?.sessionId)
    }
}

class BannerTextTest {

    @Test
    fun `a multi-sentence note is cut after the first sentence`() {
        // The phone sends three sentences when wind and aircraft data are missing,
        // and two lines on a round watch face truncate that mid-word.
        val note = "Berechnung unvollständig. Reisegeschwindigkeit nicht angegeben. " +
            "Windgeschwindigkeit nicht angegeben."
        assertEquals("Berechnung unvollständig.", firstSentence(note))
    }

    @Test
    fun `a note without a full stop is left alone`() {
        assertEquals("Something short", firstSentence("Something short"))
        assertEquals("", firstSentence(""))
    }
}
