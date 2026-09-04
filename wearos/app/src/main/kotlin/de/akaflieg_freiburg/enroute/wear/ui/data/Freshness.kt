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

import de.akaflieg_freiburg.enroute.wear.data.ConnectionState
import de.akaflieg_freiburg.enroute.wear.data.SessionState

/**
 * How much the displayed numbers can be trusted.
 *
 * This is an overlay, not a sixth route status: any status can be fresh or stale. It is
 * derived from the frame's own timestamp rather than from arrival time, so a phone that
 * has stopped updating while the link stays open is still detected.
 */
enum class Freshness {
    /** Never received anything. */
    NoData,

    /** Current. */
    Live,

    /** A few seconds old. Values stay at full brightness; the age becomes visible. */
    Stale,

    /** Old enough that the values must be visibly dimmed. */
    Old,

    /** The link is down. Last known values remain on screen, dimmed, with their age. */
    Disconnected,
}

data class DataUiState(
    val session: SessionState = SessionState(),
    val ageSeconds: Long? = null,
    val freshness: Freshness = Freshness.NoData,
) {
    val frame get() = session.frame
}

/** Age thresholds. Public so tests state the contract rather than restating magic numbers. */
object FreshnessThresholds {
    const val STALE_SECONDS = 3L
    const val OLD_SECONDS = 10L
}

fun freshnessOf(session: SessionState, ageSeconds: Long?): Freshness = when {
    session.frame == null -> Freshness.NoData
    // Anything that is not Connected, rather than Retrying alone. Enumerating the
    // failure states meant a refused pairing code -- which is a state of its own, and
    // terminal -- left the indicator claiming a live link over a dead one. Past the
    // frame check above there is always a frame, so a state other than Connected can
    // only mean the link went away underneath it.
    session.connection != ConnectionState.Connected -> Freshness.Disconnected
    ageSeconds == null -> Freshness.NoData
    ageSeconds < FreshnessThresholds.STALE_SECONDS -> Freshness.Live
    ageSeconds < FreshnessThresholds.OLD_SECONDS -> Freshness.Stale
    else -> Freshness.Old
}

/** "0:07" or "12:03", for an age shown next to the values. */
fun formatAge(seconds: Long): String {
    val clamped = seconds.coerceAtLeast(0)
    return "${clamped / 60}:${(clamped % 60).toString().padStart(2, '0')}"
}
