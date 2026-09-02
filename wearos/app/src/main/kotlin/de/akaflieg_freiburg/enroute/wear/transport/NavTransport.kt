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

package de.akaflieg_freiburg.enroute.wear.transport

import de.akaflieg_freiburg.enroute.wear.domain.FlightRoute
import de.akaflieg_freiburg.enroute.wear.domain.NavFrame
import kotlinx.coroutines.flow.Flow

/**
 * A source of navigation data from an Enroute phone.
 *
 * Exactly one method, on purpose. Fetching the route is not part of the interface: the
 * HTTP transport does a second request, the Bluetooth transport reads a chunked
 * characteristic, and both simply emit [TransportEvent.RouteUpdate]. Nothing above this
 * layer can tell which transport it is talking to, so adding Bluetooth later is a new
 * file rather than a change to the repository or the UI.
 *
 * Implementations are cold. Collecting [session] connects; cancelling the collection
 * disconnects and releases everything, so lifecycle is structured concurrency and there
 * is no start/stop pair to get out of step.
 */
interface NavTransport {

    /** Short description of the peer, for the connection UI. */
    val displayName: String

    fun session(): Flow<TransportEvent>
}

sealed interface TransportEvent {
    data object Connecting : TransportEvent
    data class Connected(val peer: PeerInfo) : TransportEvent
    data class Nav(val frame: NavFrame) : TransportEvent
    data class RouteUpdate(val route: FlightRoute) : TransportEvent
    data class Failed(val reason: FailureReason, val detail: String? = null) : TransportEvent
}

data class PeerInfo(
    val appVersion: String,
    val protocolVersion: Int,
    val sessionId: Long,
    val navPeriodMs: Long,
)

enum class FailureReason {
    /** Nothing answered at the configured address. */
    Unreachable,

    /** Something answered, but not with a protocol version this build speaks. */
    ProtocolMismatch,

    /** The pairing code was rejected. */
    Unauthorized,

    /** The peer accepted the connection and then stopped answering. */
    PeerClosed,

    Timeout,
}
