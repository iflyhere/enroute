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

import de.akaflieg_freiburg.enroute.wear.domain.FlightLogBoard
import de.akaflieg_freiburg.enroute.wear.domain.FlightRoute
import de.akaflieg_freiburg.enroute.wear.domain.GeoPoint
import de.akaflieg_freiburg.enroute.wear.domain.NavFrame
import de.akaflieg_freiburg.enroute.wear.domain.NearbyBoard
import de.akaflieg_freiburg.enroute.wear.domain.NotamBoard
import de.akaflieg_freiburg.enroute.wear.domain.TrafficBoard
import de.akaflieg_freiburg.enroute.wear.domain.VacBoard
import de.akaflieg_freiburg.enroute.wear.domain.WeatherBoard
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
    data class NotamUpdate(val notams: NotamBoard) : TransportEvent
    data class WeatherUpdate(val weather: WeatherBoard) : TransportEvent
    data class VacUpdate(val vacs: VacBoard) : TransportEvent
    data class FlightLogUpdate(val log: FlightLogBoard) : TransportEvent
    data class TrafficUpdate(val traffic: TrafficBoard) : TransportEvent
    data class NearbyUpdate(val nearby: NearbyBoard) : TransportEvent
    data class Failed(val reason: FailureReason, val detail: String? = null) : TransportEvent
}

data class PeerInfo(
    val appVersion: String,
    val protocolVersion: Int,
    val sessionId: Long,
    val navPeriodMs: Long,
    /**
     * Non-zero when the phone can serve a map. Also the cache key for it: when this
     * moves, the style a client holds names tile URLs that no longer resolve.
     */
    val mapRevision: Long = 0,
    /** Attribution for the map data, to be displayed wherever the map is. */
    val mapAttribution: String = "",
    /**
     * Where to point the camera before a position or a route is known.
     *
     * Sent by the phone rather than read out of the style, because the Android map
     * renderer ignores a style's own centre and opens on the Gulf of Guinea, where
     * it then requests no tiles at all and shows a blank screen.
     */
    val mapCentre: GeoPoint? = null,
    val mapCentreZoom: Double = 0.0,
    /**
     * Colours for text a client draws over the map, as the phone chose them.
     *
     * The cockpit palette here assumes a black background, which the data screen has
     * and the map does not: white on a daylight base map is white on white. The phone
     * already solves this for its own overlays and swaps the pair with night mode, so
     * these come from there rather than being guessed at again.
     */
    /**
     * The pilot's own unit for a height, "ft" or "m".
     *
     * Carried so the traffic display can label a height difference the way the rest of
     * the app does. Every other quantity arrives already formatted; this one does not,
     * because the phone composes no separation line of its own.
     */
    val verticalUnit: String = "ft",

    /**
     * The pilot's own unit for a distance, "nm", "km" or "mil".
     *
     * The speed dial's scale follows it, which is the mapping the app itself makes:
     * `Aircraft::horizontalSpeedToString` reads the distance preference and gives
     * knots, km/h or mph accordingly.
     */
    val horizontalUnit: String = "nm",

    val mapLabelColour: Long? = null,
    val mapHaloColour: Long? = null,
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
