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

import kotlin.random.Random

/**
 * Exponential backoff with jitter, capped.
 *
 * Reset only after data has actually arrived, never merely on a successful connect: a
 * peer that accepts a connection and then closes it must not produce a hot loop. The cap
 * is deliberately short so that a phone which comes back is picked up promptly without
 * anything having to poll.
 */
class Backoff(
    private val baseMs: Long = 1_000,
    private val maxMs: Long = 30_000,
    private val jitter: Double = 0.2,
    private val random: Random = Random.Default,
) {
    var attempt: Int = 0
        private set

    fun nextMs(): Long {
        val exponential = (baseMs shl attempt.coerceAtMost(MAX_SHIFT)).coerceAtMost(maxMs)
        attempt++
        val factor = 1.0 + (random.nextDouble() * 2.0 - 1.0) * jitter
        return (exponential * factor).toLong().coerceAtLeast(1)
    }

    fun reset() {
        attempt = 0
    }

    private companion object {
        // 1, 2, 4, 8, 16, then the cap. Guards against shifting a Long off its end.
        const val MAX_SHIFT = 5
    }
}
