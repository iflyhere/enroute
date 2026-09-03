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

package de.akaflieg_freiburg.enroute.wear.ui.log

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.Text
import de.akaflieg_freiburg.enroute.wear.domain.DetectionState
import de.akaflieg_freiburg.enroute.wear.domain.FlightEntry
import de.akaflieg_freiburg.enroute.wear.domain.FlightLogBoard
import de.akaflieg_freiburg.enroute.wear.ui.theme.CockpitColors
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The pilot's logbook, as the phone keeps it.
 *
 * Read only, and the screen says so rather than offering a control that would have to
 * be reconciled with the phone later. What the watch adds over the phone's page is
 * being on a wrist: the detector banner is at the top, because "am I being recorded"
 * is the question a pilot asks in the air, and the entries follow for the questions
 * asked on the ground.
 *
 * Only the most recent entries travel. When some were left behind the list says so at
 * the end, so it cannot be mistaken for the whole logbook.
 */
@Composable
fun FlightLogScreen(
    board: FlightLogBoard?,
    listState: ScalingLazyListState,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CockpitColors.Background),
        contentAlignment = Alignment.Center,
    ) {
        if (board == null) {
            Text(
                text = "Waiting for the flight log",
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

            if (board.state != DetectionState.Idle || board.recording) {
                item { StatusBanner(board) }
            }

            if (board.entries.isEmpty()) {
                item {
                    Text(
                        text = "No flights logged.",
                        color = CockpitColors.Muted,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    )
                }
            }

            items(board.entries, key = { entry -> entry.id }) { entry ->
                EntryCard(entry)
            }

            if (board.dropped > 0) {
                item {
                    Text(
                        text = board.dropped.toString() + " older flights not sent",
                        color = CockpitColors.Muted,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(board: FlightLogBoard) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "LOG",
            color = CockpitColors.OnBackground,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = board.total.toString() + " flights",
            color = CockpitColors.Muted,
            fontSize = 12.sp,
        )
    }
}

/**
 * What the phone's detector believes, in the phone's own colour scheme.
 *
 * The colours are this screen's reading of the three states rather than values from
 * the wire: the phone's own are written in its QML page and never reach C++, so
 * sending them would mean keeping a second copy of them in the encoder -- which is the
 * drift this project avoids elsewhere by not copying at all. Green for airborne, blue
 * for a landing being confirmed and amber for a takeoff being confirmed matches what
 * the phone shows.
 */
@Composable
private fun StatusBanner(board: FlightLogBoard) {
    val colour = when (board.state) {
        DetectionState.InFlight -> CockpitColors.Good
        DetectionState.LandingPhase -> CockpitColors.Primary
        DetectionState.TakeoffPhase -> CockpitColors.Caution
        else -> CockpitColors.Muted
    }
    val text = when (board.state) {
        DetectionState.TakeoffPhase -> "Takeoff detected"
        DetectionState.InFlight -> if (board.recording) "In flight, recording" else "In flight"
        DetectionState.LandingPhase -> "Landing detected"
        DetectionState.Unknown -> "Unknown state"
        DetectionState.Idle -> if (board.recording) "Recording track" else ""
    }
    if (text.isEmpty()) {
        return
    }

    Text(
        text = text,
        color = colour,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
    )
}

@Composable
private fun EntryCard(entry: FlightEntry) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(CARD_BACKGROUND)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            // The same two placeholders the phone's own page uses, so a leg with no
            // aerodrome recorded reads the same on both.
            text = (entry.departure ?: "?") + "  →  " + (entry.arrival ?: "?"),
            color = CockpitColors.OnBackground,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Text(
            text = timeLine(entry),
            color = CockpitColors.Muted,
            fontSize = 11.sp,
        )

        val details = buildList {
            entry.callsign?.let { callsign -> add(callsign) }
            entry.flightTime?.let { time -> add(time) }
            if (entry.landings > 1) {
                add(entry.landings.toString() + " ldg")
            }
        }
        if (details.isNotEmpty()) {
            Text(
                text = details.joinToString(" · "),
                color = CockpitColors.Primary,
                fontSize = 11.sp,
            )
        }
    }
}

/**
 * Date and the two clock times, UTC, as the phone's page writes them.
 *
 * UTC and not local, deliberately: a logbook is kept in UTC, and a watch worn across a
 * time zone would otherwise disagree with the entry the phone shows for the same
 * flight.
 */
private fun timeLine(entry: FlightEntry): String {
    val start = entry.startEpochSeconds ?: return "No time data"
    val startAt = Instant.ofEpochSecond(start).atOffset(ZoneOffset.UTC)
    val landing = entry.landingEpochSeconds
        ?.let { seconds -> Instant.ofEpochSecond(seconds).atOffset(ZoneOffset.UTC) }
    return DATE.format(startAt) + "  " + CLOCK.format(startAt) + " - " +
        (landing?.let { at -> CLOCK.format(at) } ?: "--:--") + " UTC"
}

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private val CARD_BACKGROUND = Color(0xFF14181C)
