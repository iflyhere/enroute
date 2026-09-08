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

package de.akaflieg_freiburg.enroute.wear.ui.freq

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.material3.Text
import de.akaflieg_freiburg.enroute.wear.domain.FisStation
import de.akaflieg_freiburg.enroute.wear.domain.Frequency
import de.akaflieg_freiburg.enroute.wear.domain.FrequencyKind
import de.akaflieg_freiburg.enroute.wear.domain.RouteWaypoint
import androidx.compose.ui.res.stringResource
import de.akaflieg_freiburg.enroute.wear.R
import de.akaflieg_freiburg.enroute.wear.ui.theme.CockpitColors

/**
 * Every radio frequency a pilot is likely to want next, without the phone.
 *
 * The flight information service for wherever the aircraft is now comes first: it is
 * the one a pilot cannot look up, because it depends on which of a dozen identically
 * named sectors they happen to be in. Then every waypoint on the route that carries a
 * frequency, in route order, with the next one marked.
 *
 * All of them, not just the next: on the ground at the departure aerodrome there is no
 * next waypoint -- the frame omits it unless the phone says onRoute -- and that is
 * exactly when someone wants the tower. Only aerodromes carry radio, so a route's worth
 * is a short scroll.
 *
 * The number is the largest thing on the screen and the station is small above it,
 * because the station is what a pilot already knows and the number is what they came
 * here for. Recorded information is dimmed: ATIS is listened to, not called, and at a
 * glance the difference between "dial this" and "listen to this" has to survive.
 *
 * Nothing here is computed. The phone splits the station from the number and decides
 * which sector applies; this screen only lays it out.
 */
@Composable
fun FrequencyScreen(
    fis: List<FisStation>,
    waypoints: List<RouteWaypoint>,
    nextName: String?,
    listState: ScalingLazyListState,
    modifier: Modifier = Modifier,
) {
    val withRadio = waypoints.filter { waypoint -> waypoint.frequencies.isNotEmpty() }
    val hasAnything = fis.isNotEmpty() || withRadio.isNotEmpty()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CockpitColors.Background),
        contentAlignment = Alignment.Center,
    ) {
        if (!hasAnything) {
            // One message for several causes -- no position, no airspace data, a route
            // whose waypoints carry no radio -- because none of them is actionable in
            // the air and a pilot only needs to know not to keep looking.
            Text(
                text = stringResource(R.string.freq_none),
                color = CockpitColors.Muted,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 26.dp),
            )
            return@Box
        }

        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text(
                    text = stringResource(R.string.freq_title),
                    color = CockpitColors.OnBackground,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }

            if (fis.isNotEmpty()) {
                item(key = "fis-head") { SectionTitle(stringResource(R.string.freq_information_service)) }
                fis.forEachIndexed { index, station ->
                    item(key = "fis-" + index) { FisCard(station) }
                }
            }

            withRadio.forEach { waypoint ->
                val isNext = waypoint.name == nextName
                item(key = "head-" + waypoint.index) {
                    SectionTitle(
                        text = if (isNext) waypoint.name + "  <" else waypoint.name,
                        colour = if (isNext) CockpitColors.Caution else CockpitColors.Primary,
                    )
                }
                waypoint.frequencies.forEachIndexed { index, frequency ->
                    item(key = waypoint.index.toString() + "-" + index) {
                        FrequencyCard(frequency)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, colour: Color = CockpitColors.Primary) {
    Text(
        text = text,
        color = colour,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun FisCard(station: FisStation) {
    Card {
        Label(station.station.ifBlank { stringResource(R.string.freq_information) }, CockpitColors.Muted)
        Number(station.value, CockpitColors.Good)

        val band = listOfNotNull(station.area, verticalBand(station))
            .filter { it.isNotBlank() }
            .joinToString("  ")
        if (band.isNotBlank()) {
            Text(
                text = band,
                color = CockpitColors.Muted,
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun FrequencyCard(frequency: Frequency) {
    // Recorded information is dimmed rather than coloured: it is the one entry here a
    // pilot must not key the microphone on.
    val listenOnly = frequency.kind == FrequencyKind.Information
    Card {
        Label(
            text = if (listenOnly) {
                frequency.kind.label + "  " + frequency.station
            } else {
                frequency.station
            },
            colour = CockpitColors.Muted,
        )
        Number(frequency.value, if (listenOnly) CockpitColors.Muted else CockpitColors.OnBackground)
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF16181C))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
    }
}

@Composable
private fun Label(text: String, colour: Color) {
    Text(
        text = text,
        color = colour,
        fontSize = 11.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Number(value: String?, colour: Color) {
    Text(
        // A station the app knows without a number is still worth showing: it tells a
        // pilot the service exists and that the phone has no frequency for it, which
        // is different from the app not knowing the place at all.
        text = value ?: stringResource(R.string.freq_unknown),
        color = if (value == null) CockpitColors.Muted else colour,
        fontSize = 22.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** "GND - FL 100", as the phone writes both halves, or nothing when it wrote neither. */
private fun verticalBand(station: FisStation): String? {
    val bottom = station.bottom?.takeIf { it.isNotBlank() }
    val top = station.top?.takeIf { it.isNotBlank() }
    return when {
        bottom != null && top != null -> bottom + " - " + top
        bottom != null -> bottom
        else -> top
    }
}
