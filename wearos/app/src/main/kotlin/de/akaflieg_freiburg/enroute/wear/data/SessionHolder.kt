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

import de.akaflieg_freiburg.enroute.wear.transport.NavTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the link to the phone for as long as the process lives.
 *
 * The session deliberately does not belong to a view model. A view model dies with its
 * activity, and on a watch the activity is stopped the moment the pilot glances away or
 * a tile comes up — which would drop the link exactly when it is wanted. The foreground
 * service starts and stops this holder instead, and the user interface only observes it.
 *
 * A process-level object rather than a bound service with a Binder: this app is a single
 * process, and the plumbing a Binder would add buys nothing here.
 */
object SessionHolder {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(SessionState())

    /** What the phone is currently reporting. Safe to collect before the link is up. */
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _running = MutableStateFlow(false)

    /** Whether a session is currently wanted. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var repository: NavRepository? = null
    private var mirror: Job? = null

    /**
     * Starts the link, or does nothing if one is already running.
     *
     * @param transportProvider Called for every connection attempt, so that a changed
     * address or pairing code takes effect on the next try.
     */
    fun start(transportProvider: () -> NavTransport) {
        if (repository != null) {
            return
        }
        val created = NavRepository(transportProvider = transportProvider, scope = scope)
        repository = created
        mirror = scope.launch {
            created.state.collect { _state.value = it }
        }
        created.start()
        _running.value = true
    }

    fun stop() {
        repository?.stop()
        repository = null
        mirror?.cancel()
        mirror = null
        _running.value = false
    }

    /** Drops the current attempt so that a changed address takes effect at once. */
    fun restart(transportProvider: () -> NavTransport) {
        stop()
        start(transportProvider)
    }
}
