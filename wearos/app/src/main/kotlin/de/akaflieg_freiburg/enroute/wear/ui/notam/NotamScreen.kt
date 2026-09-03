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

package de.akaflieg_freiburg.enroute.wear.ui.notam

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
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
import de.akaflieg_freiburg.enroute.wear.domain.Notam
import de.akaflieg_freiburg.enroute.wear.domain.NotamBoard
import de.akaflieg_freiburg.enroute.wear.domain.NotamCategory
import de.akaflieg_freiburg.enroute.wear.domain.NotamGroup
import de.akaflieg_freiburg.enroute.wear.domain.NotamKnowledge
import de.akaflieg_freiburg.enroute.wear.ui.theme.CockpitColors

/**
 * NOTAMs for the waypoints of the current route, grouped exactly as the phone groups
 * them.
 *
 * This screen adds no judgement of its own. It does not reorder, does not merge, and
 * does not decide that a NOTAM is irrelevant because of the altitude: the phone's filter
 * is horizontal only and says so, and a watch that quietly improved on it would be
 * making a promise the data cannot keep. What it does insist on is the difference
 * between "there is nothing here" and "we do not know", which is why every group renders
 * from [NotamGroup.knowledge] and never from whether a list happens to be empty.
 *
 * The filter's limits are the last item in the list rather than a hidden info screen. A
 * pilot who scrolls to the end of a NOTAM list is exactly the pilot who needs to know
 * what the list did not cover.
 */
@Composable
fun NotamScreen(
    board: NotamBoard?,
    listState: ScalingLazyListState,
    modifier: Modifier = Modifier,
) {
    // Keyed on the NOTAM number alone, deliberately unlike the list keys below: the
    // same NOTAM listed under two waypoints is one NOTAM, so opening it in one place
    // opens it in the other. And because it is the number and not a position, an entry
    // the pilot opened stays open when the board is refetched a minute later.
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CockpitColors.Background),
        contentAlignment = Alignment.Center,
    ) {
        if (board == null) {
            Text(
                text = "Waiting for NOTAMs",
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

            board.warning?.let { warning ->
                item { WarningRow(warning) }
            }

            board.groups.forEach { group ->
                item(key = "wp-" + group.waypointIndex) { GroupHeader(group) }

                when (group.knowledge) {
                    NotamKnowledge.Listed, NotamKnowledge.Incomplete -> {
                        items(group.notams, key = { notamItemKey(group, it) }) { notam ->
                            NotamCard(
                                notam = notam,
                                expanded = expanded[notam.number] == true,
                                onToggle = {
                                    expanded[notam.number] = expanded[notam.number] != true
                                },
                            )
                        }
                        if (group.cut > 0) {
                            item(key = "cut-" + group.waypointIndex) {
                                StatementRow(
                                    text = group.cut.toString() + " more not sent",
                                    color = CockpitColors.Caution,
                                )
                            }
                        }
                    }

                    NotamKnowledge.ConfirmedNone -> item(key = "none-" + group.waypointIndex) {
                        StatementRow(text = "No NOTAMs", color = CockpitColors.Muted)
                    }

                    // Deliberately not the same rendering as ConfirmedNone, and in the
                    // colour reserved for something the pilot should notice. This is the
                    // one confusion the whole document is shaped to avoid.
                    NotamKnowledge.Unknown -> item(key = "unknown-" + group.waypointIndex) {
                        StatementRow(text = "No data", color = CockpitColors.Caution)
                    }
                }
            }

            item { FilterNote(board) }
        }
    }
}

@Composable
private fun Header(board: NotamBoard) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "NOTAM",
            color = CockpitColors.OnBackground,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = if (board.groups.isEmpty()) "no route" else board.total.toString() + " listed",
            color = CockpitColors.Muted,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun WarningRow(warning: String) {
    Text(
        text = warning,
        color = CockpitColors.Caution,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun GroupHeader(group: NotamGroup) {
    Text(
        text = group.name,
        color = CockpitColors.Primary,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, start = 6.dp, end = 6.dp),
    )
}

@Composable
private fun StatementRow(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 6.dp, bottom = 2.dp),
    )
}

/**
 * One NOTAM.
 *
 * Collapsed it shows two lines, which is enough to recognise one; tapping expands it.
 * The expanded state never truncates, because the part of a NOTAM that matters is as
 * often at the end as at the start.
 */
@Composable
private fun NotamCard(notam: Notam, expanded: Boolean, onToggle: () -> Unit) {
    val accent = when (notam.category) {
        NotamCategory.RestrictedArea -> CockpitColors.Warning
        NotamCategory.ParachuteJumping, NotamCategory.UnmannedAircraft -> CockpitColors.Caution
        else -> CockpitColors.Muted
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (notam.read) READ_BACKGROUND else CARD_BACKGROUND)
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = notam.number,
                color = if (notam.read) CockpitColors.Muted else CockpitColors.OnBackground,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            categoryLabel(notam.category)?.let { label ->
                Text(text = label, color = accent, fontSize = 11.sp)
            }
        }

        notam.section?.let { section ->
            Text(text = section, color = CockpitColors.Muted, fontSize = 11.sp)
        }

        Text(
            text = notam.text,
            color = if (notam.read) CockpitColors.Muted else CockpitColors.OnBackground,
            fontSize = 12.sp,
            maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES,
            overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FilterNote(board: NotamBoard) {
    val radius = board.filter.radiusM
        ?.let { (it / M_PER_NM).toInt().toString() + " NM" }
        ?: "a fixed radius"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Not an airspace check",
            color = CockpitColors.Caution,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "Within " + radius + " of route waypoints only, with no altitude " +
                "filter. NOTAMs beside a leg are not listed.",
            color = CockpitColors.Muted,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
        )
        if (board.dropped > 0) {
            Text(
                text = board.dropped.toString() + " did not fit and are not shown",
                color = CockpitColors.Caution,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * List key for one NOTAM in one group.
 *
 * The group index has to be in it. A NOTAM whose area covers two route waypoints is
 * listed under both -- which is normal, not exotic -- and a lazy list keyed on the
 * number alone then throws "Key was already used" the moment both entries are measured
 * in the same pass. That is a crash in flight, and it took a mock fixture with a wide
 * restricted area to produce it.
 */
internal fun notamItemKey(group: NotamGroup, notam: Notam): String =
    group.waypointIndex.toString() + "-" + notam.number

/** Null for the generic category, where a label would say nothing. */
private fun categoryLabel(category: NotamCategory): String? = when (category) {
    NotamCategory.Obstacle -> "OBST"
    NotamCategory.ParachuteJumping -> "PJE"
    NotamCategory.UnmannedAircraft -> "UAS"
    NotamCategory.RestrictedArea -> "AREA"
    NotamCategory.Other -> null
}

private const val COLLAPSED_LINES = 2
private const val M_PER_NM = 1852.0

private val CARD_BACKGROUND = Color(0xFF161616)
private val READ_BACKGROUND = Color(0xFF0C0C0C)
