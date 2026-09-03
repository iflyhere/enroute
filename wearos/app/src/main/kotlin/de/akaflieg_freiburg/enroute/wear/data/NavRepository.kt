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

package de.akaflieg_freiburg.enroute.wear.data

import de.akaflieg_freiburg.enroute.wear.domain.FlightRoute
import de.akaflieg_freiburg.enroute.wear.domain.NavFrame
import de.akaflieg_freiburg.enroute.wear.domain.NotamBoard
import de.akaflieg_freiburg.enroute.wear.domain.WeatherBoard
import de.akaflieg_freiburg.enroute.wear.transport.Backoff
import de.akaflieg_freiburg.enroute.wear.transport.FailureReason
import de.akaflieg_freiburg.enroute.wear.transport.NavTransport
import de.akaflieg_freiburg.enroute.wear.transport.PeerInfo
import de.akaflieg_freiburg.enroute.wear.transport.TransportEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data class Retrying(val reason: FailureReason, val attempt: Int) : ConnectionState
}

/**
 * Everything a screen needs, in one value.
 *
 * [frame] and [route] survive a disconnect on purpose. A pilot glancing down during a
 * two-second radio hiccup wants the last reading with its age, not a blank screen; the
 * age is derived from the frame's own timestamp, so an old value can always be shown as
 * old rather than silently passed off as current.
 */
data class SessionState(
    val connection: ConnectionState = ConnectionState.Idle,
    val peer: PeerInfo? = null,
    val frame: NavFrame? = null,
    val route: FlightRoute? = null,
    /**
     * Last NOTAM board received, kept across a reconnect on purpose. NOTAMs age in
     * hours, so the ones from a minute ago are still the right answer while the link
     * is down -- and a blank list would read as "nothing to report".
     */
    val notams: NotamBoard? = null,
    /**
     * Last weather board received, kept across a reconnect for the same reason. A
     * METAR is valid for an hour and a half, so the last one is still the best
     * answer available while the link is down; the summary states its age.
     */
    val weather: WeatherBoard? = null,
)

class NavRepository(
    private val transportProvider: () -> NavTransport,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return

        job = scope.launch {
            val backoff = Backoff()
            var lastReason = FailureReason.Unreachable

            while (isActive) {
                var receivedFrame = false

                transportProvider().session()
                    .catch { throwable ->
                        lastReason = FailureReason.Timeout
                        _state.update { it.copy(connection = ConnectionState.Retrying(lastReason, backoff.attempt)) }
                        if (throwable is kotlinx.coroutines.CancellationException) throw throwable
                    }
                    .collect { event ->
                        if (event is TransportEvent.Nav) receivedFrame = true
                        if (event is TransportEvent.Failed) lastReason = event.reason
                        reduce(event)
                    }

                // Reset only when data actually arrived. A peer that accepts a
                // connection and immediately closes it must not spin.
                if (receivedFrame) backoff.reset()

                val waitMs = backoff.nextMs()
                _state.update {
                    it.copy(connection = ConnectionState.Retrying(lastReason, backoff.attempt))
                }
                delay(waitMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.update { it.copy(connection = ConnectionState.Idle) }
    }

    private fun reduce(event: TransportEvent) {
        _state.update { current ->
            when (event) {
                TransportEvent.Connecting ->
                    current.copy(connection = ConnectionState.Connecting)

                is TransportEvent.Connected ->
                    current.copy(connection = ConnectionState.Connected, peer = event.peer)

                is TransportEvent.Nav ->
                    current.copy(connection = ConnectionState.Connected, frame = event.frame)

                is TransportEvent.RouteUpdate ->
                    current.copy(route = event.route)

                is TransportEvent.NotamUpdate ->
                    current.copy(notams = event.notams)

                is TransportEvent.WeatherUpdate ->
                    current.copy(weather = event.weather)

                is TransportEvent.Failed ->
                    current.copy(connection = ConnectionState.Retrying(event.reason, 0))
            }
        }
    }
}
