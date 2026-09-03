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

package de.akaflieg_freiburg.enroute.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.MaterialTheme

/**
 * Cockpit palette.
 *
 * A watch in a cockpit is read at arm's length, often in direct sunlight and often
 * through polarised sunglasses, so the palette is high-contrast and sparing: white for
 * data, cyan for whatever the pilot is currently flying towards, amber and red for
 * conditions, and nothing else. Colour never carries information on its own -- shape
 * and position do -- because glare and colour vision deficiency both defeat it.
 */
object CockpitColors {
    val Background = Color(0xFF000000)
    val OnBackground = Color(0xFFFFFFFF)

    /** The active leg, the next waypoint, own position. */
    val Primary = Color(0xFF4FD8FF)

    /** A condition the pilot should notice but that is not an error. */
    val Caution = Color(0xFFFFB300)

    /** Data that is old, a link that is down, or a restricted area ahead. */
    val Warning = Color(0xFFFF5252)

    /** A good state, used sparingly. */
    val Good = Color(0xFF69F0AE)

    /** Secondary rows and unit captions. */
    val Muted = Color(0xB3FFFFFF)
}

@Composable
fun EnrouteWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            background = CockpitColors.Background,
            onBackground = CockpitColors.OnBackground,
            primary = CockpitColors.Primary,
            error = CockpitColors.Warning,
        ),
        content = content,
    )
}
