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

package de.akaflieg_freiburg.enroute.wear.ui.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import de.akaflieg_freiburg.enroute.wear.data.SessionHolder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/**
 * Presents the session for display.
 *
 * The session itself belongs to SessionHolder, which the foreground service owns, so
 * that it survives this view model and the activity around it. All this class does is
 * derive how old the data is and hand the result to the screens.
 */
class DataViewModel(
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : ViewModel() {

    /**
     * Ticks once a second so that the displayed age advances even when no frame
     * arrives -- which is precisely the case where the age matters most.
     *
     * The ticker lives here rather than in a composable so that the clock can be
     * injected in tests, and because SharingStarted.WhileSubscribed stops it when no
     * screen is collecting. On a watch that is the difference between an idle app and
     * one that keeps the CPU awake.
     */
    private fun ticker(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(TICK_MS)
        }
    }

    val uiState: StateFlow<DataUiState> =
        combine(SessionHolder.state, ticker()) { session, _ ->
            val age = session.frame?.let { nowEpochSeconds() - it.generatedAtEpochSeconds }
            DataUiState(
                session = session,
                ageSeconds = age,
                freshness = freshnessOf(session, age),
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = DataUiState(),
        )

    /**
     * Hand-written wiring instead of a dependency-injection framework. At this size
     * that is a handful of lines with no annotation processor in the build graph, and a
     * reviewer who does not use Hilt can still read it.
     */
    class Factory : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = DataViewModel() as T
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
