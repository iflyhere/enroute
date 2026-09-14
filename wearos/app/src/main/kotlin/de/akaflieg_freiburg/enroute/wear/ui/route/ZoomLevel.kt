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

package de.akaflieg_freiburg.enroute.wear.ui.route

private const val METRES_PER_NM = 1852.0

/**
 * How much of the world the map shows.
 *
 * Discrete steps rather than continuous zoom, so that a detent of the crown produces a
 * scale a pilot can name — "I am on the ten mile scale" — and so the range ring can be
 * labelled exactly. [Automatic] frames whatever needs to be visible.
 */
sealed interface ZoomLevel {

    /** Fits the route and the aircraft, whatever that takes. */
    data object Automatic : ZoomLevel

    /** A fixed half-span, in nautical miles. */
    data class Fixed(val halfSpanNm: Int) : ZoomLevel {
        val halfSpanMetres: Double get() = halfSpanNm * METRES_PER_NM
    }

    companion object {
        private val fixedSteps = listOf(1, 2, 5, 10, 20, 50, 100, 200)

        /** Automatic first, then the fixed scales, coarsest last. */
        val steps: List<ZoomLevel> = listOf(Automatic) + fixedSteps.map { Fixed(it) }

        fun stepped(from: ZoomLevel, by: Int): ZoomLevel {
            val index = steps.indexOf(from).coerceAtLeast(0)
            return steps[(index + by).coerceIn(0, steps.lastIndex)]
        }

        fun label(level: ZoomLevel): String = when (level) {
            Automatic -> "auto"
            is Fixed -> "${level.halfSpanNm} nm"
        }
    }
}
