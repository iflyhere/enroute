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

package de.akaflieg_freiburg.enroute.wear.ui.traffic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import de.akaflieg_freiburg.enroute.wear.domain.GeoPoint
import de.akaflieg_freiburg.enroute.wear.domain.TrafficBoard
import de.akaflieg_freiburg.enroute.wear.domain.TrafficTarget
import de.akaflieg_freiburg.enroute.wear.domain.TrafficWarning
import de.akaflieg_freiburg.enroute.wear.ui.theme.CockpitColors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the traffic receiver is reporting.
 *
 * The screen is built around one distinction: an empty sky and a silent receiver look
 * identical, and only one of them means it is safe to believe the display. So the
 * receiver's state is stated before anything else, in words, every time -- not as a
 * small icon a pilot has to notice the absence of.
 *
 * The radar comes first, because that is the shape a pilot reads a traffic picture in
 * and the shape every instrument in a cockpit uses. The list follows underneath it,
 * one scroll away: it carries the identifiers, the types and the phone's own sentence
 * about each target, which a dot on a circle cannot.
 *
 * The list is ordered most alarming first and then nearest. That ordering is this
 * screen's own; the phone keeps its targets in a pool and draws them on a map, where
 * order does not exist. Colours and alarm levels are the phone's.
 *
 * A target whose bearing the receiver does not know appears twice, deliberately: as a
 * dashed ring on the radar, which is the honest shape of "somewhere at this range",
 * and at the end of the list in words.
 */
@Composable
fun TrafficScreen(
    board: TrafficBoard?,
    ownPosition: GeoPoint?,
    ownTrackDeg: Double?,
    verticalUnit: String,
    rangeOverrideM: Double?,
    onRange: (step: Int, currentM: Double) -> Unit,
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
            // No frame at all. Not "no traffic": the link is what is missing, and the
            // difference is the whole point of this screen.
            Text(
                text = "No traffic data\nfrom the phone",
                color = CockpitColors.Warning,
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
            // A banner above the radar rather than a card in the list: when there is
            // an alarm, this is the first thing on the screen and it names the
            // direction to look in, the way a traffic instrument does.
            board.warning?.let { warning ->
                item { WarningBanner(warning, board, ownPosition, ownTrackDeg) }
            }

            if (board.receiving && board.hasDrawable) {
                item {
                    TrafficRadar(
                        board = board,
                        ownPosition = ownPosition,
                        ownTrackDeg = ownTrackDeg,
                        verticalUnit = verticalUnit,
                        rangeOverrideM = rangeOverrideM,
                        onRange = onRange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                    )
                }
            }

            item { Header(board) }

            if (board.receiving && board.hasDrawable) {
                item {
                    Note("Tap the top or bottom of the display to change the range.")
                }
            }

            board.warning?.let { warning -> item { WarningCard(warning) } }

            if (!board.receiving) {
                item { ReceiverSilent(board) }
            }

            board.runtimeError?.let { error ->
                item { ErrorRow(error) }
            }
            board.selfTestError?.let { error ->
                item { ErrorRow(error) }
            }

            if (board.receiving && !board.hasDrawable) {
                item {
                    Text(
                        // Three different things, and the pilot has to be able to
                        // tell them apart: nothing is out there, something is out
                        // there but outside the band the phone draws, or nothing is
                        // listening. The last case is the card above.
                        text = if (board.targets.isEmpty()) {
                            "No traffic reported."
                        } else {
                            board.targets.size.toString() +
                                " contacts, none within 20 nm and 5000 ft"
                        },
                        color = CockpitColors.Good,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }

            // Keyed by position as well as identity: a receiver may report a target
            // with no identifier at all, and two of those would collide.
            board.listed.forEachIndexed { index, target ->
                item(key = "tfc-" + (target.id ?: "anon") + "-" + index) {
                    TargetCard(target)
                }
            }

            board.withoutBearing?.let { target ->
                item { TargetCard(target, bearingUnknown = true) }
            }
        }
    }
}

@Composable
private fun Header(board: TrafficBoard) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "TRAFFIC",
            color = CockpitColors.OnBackground,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            // Drawn out of seen, so the number on the radar and the length of the
            // list below it never look like a contradiction.
            text = when {
                !board.receiving -> "no signal"
                else -> board.drawable.size.toString() + " of " +
                    board.targets.size.toString()
            },
            color = if (board.receiving) CockpitColors.Muted else CockpitColors.Warning,
            fontSize = 12.sp,
        )
    }
}

/**
 * The receiver is not talking.
 *
 * Given its own card rather than a line, because it changes what every other line on
 * this page is worth.
 */
@Composable
private fun ReceiverSilent(board: TrafficBoard) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(CARD_BACKGROUND)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "No traffic receiver",
            color = CockpitColors.Warning,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            // The app's own sentence, which names what it tried.
            text = board.status ?: "The phone is not receiving a heartbeat.",
            color = CockpitColors.Muted,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun ErrorRow(text: String) {
    Text(
        text = text,
        color = CockpitColors.Caution,
        fontSize = 11.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
    )
}

/**
 * "Warning, one o'clock" -- the line a traffic instrument puts across the top.
 *
 * The clock position comes from the most alarming target's bearing relative to the
 * aircraft's track. Without a known track there is no clock position to give, and the
 * banner says only that there is a warning rather than pointing somewhere it cannot
 * justify.
 */
@Composable
private fun WarningBanner(
    warning: TrafficWarning,
    board: TrafficBoard,
    ownPosition: GeoPoint?,
    ownTrackDeg: Double?,
) {
    val colour = if (warning.alarmLevel >= 2) CockpitColors.Warning else CockpitColors.Caution
    val worst = mostAlarming(radarFixes(board.targets, ownPosition, ownTrackDeg))
    val direction = if (ownTrackDeg != null && worst != null) {
        " " + clockPosition(worst.screenBearingDeg) + " o'clock"
    } else {
        ""
    }

    Text(
        text = "Warning" + direction,
        color = CockpitColors.Background,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colour)
            .padding(vertical = 6.dp),
    )
}

@Composable
private fun WarningCard(warning: TrafficWarning) {
    val colour = when {
        warning.alarmLevel >= 2 -> CockpitColors.Warning
        else -> CockpitColors.Caution
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colour)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            text = "ALARM " + warning.alarmLevel,
            color = CockpitColors.Background,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
        warning.description?.let { description ->
            Text(text = description, color = CockpitColors.Background, fontSize = 12.sp)
        }
        Text(
            text = separation(warning.horizontalDistanceM, warning.verticalDistanceM),
            color = CockpitColors.Background,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun TargetCard(target: TrafficTarget, bearingUnknown: Boolean = false) {
    val accent = target.colour?.let { colour -> Color(colour) } ?: CockpitColors.Muted

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(CARD_BACKGROUND)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = target.label,
                color = CockpitColors.OnBackground,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            target.type?.let { type ->
                Text(text = type, color = CockpitColors.Muted, fontSize = 10.sp)
            }
        }

        Text(
            text = separation(target.horizontalDistanceM, target.verticalDistanceM),
            color = CockpitColors.Primary,
            fontSize = 12.sp,
        )

        target.description?.let { description ->
            Text(
                text = description,
                color = CockpitColors.Muted,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (bearingUnknown) {
            Text(
                text = "Bearing unknown",
                color = CockpitColors.Caution,
                fontSize = 11.sp,
            )
        }

        // Said in the list, because the list is the one place that carries contacts
        // the radar does not draw. Without this the two would appear to contradict
        // each other.
        if (!target.relevant) {
            Text(
                text = "Not on the display",
                color = CockpitColors.Muted,
                fontSize = 10.sp,
            )
        }
    }
}

/**
 * Range and height difference in one line.
 *
 * The units here are this screen's own, and are the only place on the watch where a
 * displayed quantity is not a string the phone wrote. The traffic document carries SI
 * metres because a map needs numbers, and the phone composes no separation line of its
 * own to copy. Nautical miles and feet are what a traffic display uses.
 */
private fun separation(horizontalM: Double?, verticalM: Double?): String {
    val parts = mutableListOf<String>()
    if (horizontalM != null && horizontalM.isFinite()) {
        val nauticalMiles = horizontalM / METRES_PER_NM
        parts += if (nauticalMiles >= 10.0) {
            nauticalMiles.roundToInt().toString() + " nm"
        } else {
            String.format("%.1f nm", nauticalMiles)
        }
    }
    if (verticalM != null && verticalM.isFinite()) {
        val feet = (verticalM / METRES_PER_FOOT).roundToInt()
        val rounded = (feet / 100.0).roundToInt() * 100
        parts += when {
            abs(rounded) < 100 -> "level"
            rounded > 0 -> "+" + rounded + " ft"
            else -> rounded.toString() + " ft"
        }
    }
    return if (parts.isEmpty()) "distance unknown" else parts.joinToString(" · ")
}

private const val METRES_PER_NM = 1852.0
private const val METRES_PER_FOOT = 0.3048

private val CARD_BACKGROUND = Color(0xFF14181C)

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        color = CockpitColors.Muted,
        fontSize = 10.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
    )
}
