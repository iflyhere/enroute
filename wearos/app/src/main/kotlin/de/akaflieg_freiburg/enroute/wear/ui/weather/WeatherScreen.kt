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

package de.akaflieg_freiburg.enroute.wear.ui.weather

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.Text
import de.akaflieg_freiburg.enroute.wear.domain.WeatherBoard
import de.akaflieg_freiburg.enroute.wear.domain.WeatherStation
import de.akaflieg_freiburg.enroute.wear.ui.theme.CockpitColors

/**
 * METAR and TAF for the stations near the aircraft, nearest first.
 *
 * The order is the phone's, not this screen's: Weather::ObserverList sorts by distance
 * to the last known position, and re-sorting here would need a position the watch does
 * not have. Every string shown was written by the phone, so the watch and the phone
 * cannot end up saying different things about the same weather -- including the units,
 * which follow the pilot's own setting.
 *
 * Collapsed, a station shows its category, the distance line and the phone's one-line
 * summary. Tapping reveals the raw METAR and TAF, because that is what a pilot reads
 * out loud on the radio and no summary replaces it.
 */
@Composable
fun WeatherScreen(
    board: WeatherBoard?,
    listState: ScalingLazyListState,
    modifier: Modifier = Modifier,
) {
    // Keyed on the station name, so an opened report stays open when the board is
    // refetched two minutes later, and so the same station cannot be open in one
    // place and closed in another.
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CockpitColors.Background),
        contentAlignment = Alignment.Center,
    ) {
        if (board == null) {
            Text(
                text = "Waiting for weather",
                color = CockpitColors.Muted,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            return@Box
        }

        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { Header(board) }

            board.qnh?.let { qnh -> item { InfoRow(qnh, CockpitColors.Primary) } }
            board.sun?.let { sun -> item { InfoRow(sun, CockpitColors.Muted) } }

            if (board.stations.isEmpty()) {
                item { EmptyRow(board.downloading) }
            }

            // The position is part of the key as well as the name. Two entries with
            // the same key crash a lazy list outright once both are measured
            // together, and a document is not a place to assume uniqueness.
            items(
                board.stations.withIndex().toList(),
                key = { indexed -> indexed.value.name + "@" + indexed.index },
            ) { indexed ->
                val station = indexed.value
                StationCard(
                    station = station,
                    expanded = expanded[station.name] == true,
                    onToggle = {
                        expanded[station.name] = expanded[station.name] != true
                    },
                )
            }
        }
    }
}

@Composable
private fun Header(board: WeatherBoard) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "WEATHER",
            color = CockpitColors.OnBackground,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            // "downloading" is the phone's own flag. Saying so keeps a short list
            // from reading as the final answer while more is still on its way.
            text = if (board.downloading) {
                "loading"
            } else {
                board.stations.size.toString() + " stations"
            },
            color = CockpitColors.Muted,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun InfoRow(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 1.dp),
    )
}

@Composable
private fun EmptyRow(downloading: Boolean) {
    Text(
        text = if (downloading) {
            "Fetching reports"
        } else {
            // Not "no weather": the phone reports what it has downloaded, and an
            // empty list means nothing was downloaded, which is a different claim.
            "The phone has no reports."
        },
        color = CockpitColors.Muted,
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp),
    )
}

/**
 * One station.
 *
 * The category dot uses the colour the phone sent for it. A watch deriving its own
 * colour from the category would eventually disagree with the phone about the same
 * weather, and a colour is exactly the part of this a pilot reads without reading.
 */
@Composable
private fun StationCard(station: WeatherStation, expanded: Boolean, onToggle: () -> Unit) {
    val accent = station.metar?.colour?.let { colour -> Color(colour) } ?: CockpitColors.Muted

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(CARD_BACKGROUND)
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = station.name,
                color = CockpitColors.OnBackground,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.width(6.dp))
            station.metar?.let { metar ->
                Text(
                    text = metar.category.name,
                    color = accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        station.extendedName?.takeIf { name -> name != station.name }?.let { name ->
            Text(
                text = name,
                color = CockpitColors.Muted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        station.way?.let { way ->
            Text(text = way, color = CockpitColors.Primary, fontSize = 11.sp)
        }

        station.metar?.summary?.let { summary ->
            Text(
                text = summary,
                color = CockpitColors.OnBackground,
                fontSize = 12.sp,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
            )
        }

        if (expanded) {
            // Monospaced, because a METAR is read group by group and proportional
            // digits make that harder than it needs to be.
            station.metar?.let { metar -> RawReport(metar.raw) }
            station.taf?.let { taf -> RawReport(taf.raw) }
        }
    }
}

@Composable
private fun RawReport(raw: String) {
    Text(
        text = raw,
        color = CockpitColors.Muted,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(top = 2.dp),
    )
}

private val CARD_BACKGROUND = Color(0xFF14181C)
