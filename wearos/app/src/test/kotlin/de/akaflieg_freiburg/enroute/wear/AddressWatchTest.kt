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

import de.akaflieg_freiburg.enroute.wear.data.ConnectionState
import de.akaflieg_freiburg.enroute.wear.data.DiscoveredPhone
import de.akaflieg_freiburg.enroute.wear.service.ATTEMPTS_BEFORE_SEARCH
import de.akaflieg_freiburg.enroute.wear.service.WifiAddress
import de.akaflieg_freiburg.enroute.wear.service.isHandoverWorthTaking
import de.akaflieg_freiburg.enroute.wear.service.isWorthAdopting
import de.akaflieg_freiburg.enroute.wear.service.parseWifiUrl
import de.akaflieg_freiburg.enroute.wear.service.shouldSearchForPhone
import de.akaflieg_freiburg.enroute.wear.transport.FailureReason
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When the watch goes looking for the phone's address again.
 *
 * The case this exists for was found on the wrist, outside the house: the watch had a
 * home-network address stored and sat retrying it, because discovery only ever ran on
 * the connection screen.
 */
class AddressWatchTest {

    private fun phone(host: String, port: Int = 8973) = DiscoveredPhone(
        host = host,
        port = port,
        protocolVersion = 1,
        sessionId = 1,
    )

    @Test
    fun `a single failure is not enough to wake the radio`() {
        // A Wi-Fi radio that has just woken fails once as a matter of routine.
        assertFalse(
            shouldSearchForPhone(ConnectionState.Retrying(FailureReason.Unreachable, 0)),
        )
        assertFalse(
            shouldSearchForPhone(ConnectionState.Retrying(FailureReason.Unreachable, 1)),
        )
    }

    @Test
    fun `repeated failures start a search`() {
        assertTrue(
            shouldSearchForPhone(
                ConnectionState.Retrying(FailureReason.Unreachable, ATTEMPTS_BEFORE_SEARCH),
            ),
        )
        assertTrue(
            shouldSearchForPhone(ConnectionState.Retrying(FailureReason.Timeout, 5)),
        )
    }

    @Test
    fun `a refused pairing code never starts a search`() {
        // That address is answering perfectly well. It is the code that is wrong, and
        // discovery would find the same phone and change nothing.
        assertFalse(
            shouldSearchForPhone(ConnectionState.Retrying(FailureReason.Unauthorized, 9)),
        )
        assertFalse(shouldSearchForPhone(ConnectionState.Rejected))
    }

    @Test
    fun `a working or idle link never starts a search`() {
        assertFalse(shouldSearchForPhone(ConnectionState.Connected))
        assertFalse(shouldSearchForPhone(ConnectionState.Connecting))
        assertFalse(shouldSearchForPhone(ConnectionState.Idle))
    }

    @Test
    fun `a beacon from the address already in use is not adopted`() {
        // Adopting it would restart the session for nothing, and on a bad network that
        // becomes a loop: fail, discover the same address, restart, fail.
        assertFalse(isWorthAdopting(phone("192.168.1.20"), "192.168.1.20", 8973))
    }

    @Test
    fun `a different address or port is adopted`() {
        // The case from the wrist: the phone moved from the home subnet to a hotspot.
        assertTrue(isWorthAdopting(phone("192.168.43.1"), "192.168.123.108", 8973))
        assertTrue(isWorthAdopting(phone("192.168.1.20", 9000), "192.168.1.20", 8973))
    }

    @Test
    fun `the address the phone states over Bluetooth is read back`() {
        assertEquals(
            WifiAddress("192.168.1.42", 8080),
            parseWifiUrl("http://192.168.1.42:8080"),
        )
        // Surrounding whitespace is the phone's business, not a reason to fail.
        assertEquals(WifiAddress("10.0.0.7", 80), parseWifiUrl("  http://10.0.0.7:80  "))
    }

    @Test
    fun `anything that is not an address and a port is refused outright`() {
        // A half-parsed address is worse than none: the watch would retry forever
        // against somewhere that will never answer, and that looks like a dead phone.
        assertNull(parseWifiUrl(""))
        assertNull(parseWifiUrl("192.168.1.42"))
        assertNull(parseWifiUrl("http://192.168.1.42"))
        assertNull(parseWifiUrl("http://192.168.1.42:"))
        assertNull(parseWifiUrl("http://192.168.1.42:port"))
        assertNull(parseWifiUrl("http://192.168.1.42:70000"))
        assertNull(parseWifiUrl("http://192.168.1.42:8080/route"))
        assertNull(parseWifiUrl(":8080"))
    }

    @Test
    fun `a handover that changes nothing is not taken`() {
        val stored = WifiAddress("192.168.1.42", 8080)
        // Writing the same values back would touch the settings file on every
        // connection for nothing.
        assertFalse(isHandoverWorthTaking(stored, "192.168.1.42", 8080))
        assertTrue(isHandoverWorthTaking(stored, "192.168.1.9", 8080))
        assertTrue(isHandoverWorthTaking(stored, "192.168.1.42", 8081))
    }
}
