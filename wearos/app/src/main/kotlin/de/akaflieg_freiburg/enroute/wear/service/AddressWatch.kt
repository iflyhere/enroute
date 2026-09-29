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

package de.akaflieg_freiburg.enroute.wear.service

import de.akaflieg_freiburg.enroute.wear.data.ConnectionState
import de.akaflieg_freiburg.enroute.wear.data.DiscoveredPhone
import de.akaflieg_freiburg.enroute.wear.transport.FailureReason

/**
 * Whether the watch should go looking for the phone's address again.
 *
 * The address is stored, and a stored address is a home network's address. Walk out of
 * the house and the phone gets a different one -- or a hotspot gives it another subnet
 * entirely -- and the watch sits there retrying a number that will never answer again.
 * That is what happened on the first flight-bag test outside the house.
 *
 * Only after a couple of failed attempts, not the first: a Wi-Fi radio that has just
 * woken up fails once as a matter of routine, and waking the radio for a broadcast
 * listen every time that happened would cost battery for nothing.
 *
 * Never for a refused pairing code. That address is answering perfectly well; it is
 * the code that is wrong, and re-discovering would find the same phone and change
 * nothing.
 */
fun shouldSearchForPhone(connection: ConnectionState): Boolean = when (connection) {
    is ConnectionState.Retrying ->
        connection.reason != FailureReason.Unauthorized &&
            connection.attempt >= ATTEMPTS_BEFORE_SEARCH

    else -> false
}

/**
 * Whether a discovered phone is worth switching to.
 *
 * A beacon from the address already configured is not news -- it means the phone is
 * there and something else is wrong, and adopting it would restart the session for
 * nothing, which on a bad network would become a loop. Only a different address is a
 * reason to move.
 */
fun isWorthAdopting(found: DiscoveredPhone, currentHost: String, currentPort: Int): Boolean =
    found.host != currentHost || found.port != currentPort

/**
 * Failed attempts before the watch listens for a beacon.
 *
 * Two, which with the exponential backoff means roughly three to seven seconds after
 * the link goes down -- soon enough that a pilot who has just changed network does not
 * notice, late enough that a single dropped packet does not wake the radio.
 */
const val ATTEMPTS_BEFORE_SEARCH = 2

/**
 * How long to listen before giving up and letting the ordinary retry carry on.
 *
 * Bounded rather than continuous: many networks block broadcast, and a watch listening
 * forever on a network that will never deliver one is a watch with a flat battery.
 */
const val SEARCH_WINDOW_MS = 20_000L

/**
 * A phone's Wi-Fi address as it stated it over Bluetooth.
 *
 * @property host the address, without scheme or port
 * @property port the port it is listening on
 */
data class WifiAddress(val host: String, val port: Int)

/**
 * Reads `http://<address>:<port>` as the phone writes it into its info document.
 *
 * Deliberately strict: anything that is not that shape returns null rather than a
 * half-parsed address, because a wrong address is indistinguishable from a phone that
 * is switched off and would send the watch retrying somewhere that will never answer.
 */
fun parseWifiUrl(url: String): WifiAddress? {
    val withoutScheme = url.trim().removePrefix("http://").removePrefix("https://")
    if (withoutScheme.isEmpty() || withoutScheme.contains('/')) {
        return null
    }
    val separator = withoutScheme.lastIndexOf(':')
    if (separator <= 0 || separator == withoutScheme.length - 1) {
        return null
    }
    val port = withoutScheme.substring(separator + 1).toIntOrNull() ?: return null
    if (port !in 1..65535) {
        return null
    }
    return WifiAddress(withoutScheme.substring(0, separator), port)
}

/**
 * Whether what the phone said over Bluetooth is worth storing.
 *
 * Only when it differs from what is stored: writing the same values back would be a
 * file write on every connection, and it would restart a session that is working. The
 * same reasoning as [isWorthAdopting] for a discovery beacon, on a different source.
 */
fun isHandoverWorthTaking(found: WifiAddress, host: String, port: Int): Boolean =
    found.host != host || found.port != port
