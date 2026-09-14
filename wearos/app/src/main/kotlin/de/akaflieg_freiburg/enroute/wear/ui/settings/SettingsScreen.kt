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

package de.akaflieg_freiburg.enroute.wear.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import de.akaflieg_freiburg.enroute.wear.R
import de.akaflieg_freiburg.enroute.wear.ui.BezelAction
import de.akaflieg_freiburg.enroute.wear.ui.ChartMode
import de.akaflieg_freiburg.enroute.wear.transport.TransportMode
import de.akaflieg_freiburg.enroute.wear.ui.WearPage
import de.akaflieg_freiburg.enroute.wear.ui.theme.CockpitColors

/**
 * Everything the pilot can change, and the attribution the map data requires.
 *
 * Pinned as the last page and never hideable. A pilot who switches every other page
 * off has to be able to switch them back on, and this is the screen that does it.
 *
 * The About section at the bottom is where the map attribution lives now. It used to
 * be drawn across the bottom of the map, which cost two lines of a 454 pixel disc on
 * every glance. The licences behind that notice -- openAIP's CC BY-NC-SA and the open
 * flightmaps terms -- require the credit to be shown, not to be shown on top of the
 * map, and one swipe to a permanent page is the same arrangement the renderer's own
 * info button makes. It is not optional and it is not hidden behind anything.
 */
@Composable
fun SettingsScreen(
    pages: List<WearPage>,
    hidden: Set<String>,
    bezelAction: BezelAction,
    alarmVibration: Boolean,
    keepScreenOn: Boolean,
    chartMode: ChartMode,
    transportMode: TransportMode,
    attribution: String,
    peerDescription: String,
    appVersion: String,
    listState: ScalingLazyListState,
    onMovePage: (WearPage, Int) -> Unit,
    onToggleHidden: (WearPage) -> Unit,
    onBezelAction: (BezelAction) -> Unit,
    onAlarmVibration: (Boolean) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    onChartMode: (ChartMode) -> Unit,
    onTransportMode: (TransportMode) -> Unit,
    onOpenConnect: () -> Unit,
    sessionRunning: Boolean,
    onToggleSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CockpitColors.Background),
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text(
                    text = stringResource(R.string.settings_title),
                    color = CockpitColors.OnBackground,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }

            item { SectionTitle(stringResource(R.string.settings_charts)) }
            item {
                ChoiceRow(
                    options = ChartMode.entries.map { mode -> mode.label },
                    selected = ChartMode.entries.indexOf(chartMode),
                    onSelect = { index -> onChartMode(ChartMode.entries[index]) },
                )
            }
            item {
                Note(stringResource(R.string.settings_charts_note))
            }

            item { SectionTitle(stringResource(R.string.settings_alarm)) }
            item {
                ChoiceRow(
                    options = listOf(R.string.settings_alarm_vibrate, R.string.settings_alarm_silent),
                    selected = if (alarmVibration) 0 else 1,
                    onSelect = { index -> onAlarmVibration(index == 0) },
                )
            }
            item {
                Note(stringResource(R.string.settings_alarm_note))
            }

            item { SectionTitle(stringResource(R.string.settings_display)) }
            item {
                ChoiceRow(
                    options = listOf(
                        R.string.settings_display_always_on,
                        R.string.settings_display_normal,
                    ),
                    selected = if (keepScreenOn) 0 else 1,
                    onSelect = { index -> onKeepScreenOn(index == 0) },
                )
            }
            item {
                Note(stringResource(R.string.settings_display_note))
            }

            item { SectionTitle(stringResource(R.string.settings_bezel)) }
            item {
                ChoiceRow(
                    options = BezelAction.entries.map { action -> action.label },
                    selected = BezelAction.entries.indexOf(bezelAction),
                    onSelect = { index -> onBezelAction(BezelAction.entries[index]) },
                )
            }
            item {
                Note(stringResource(R.string.settings_bezel_note))
            }

            item { SectionTitle(stringResource(R.string.settings_screens)) }
            // Settings itself is in the list but shows no controls: seeing it pinned
            // at the end explains why it cannot be moved better than its absence would.
            pages.forEach { page ->
                item(key = "page-" + page.id) {
                    PageRow(
                        page = page,
                        visible = page.id !in hidden,
                        first = pages.firstOrNull { entry -> entry.canBeMoved } == page,
                        last = pages.lastOrNull { entry -> entry.canBeMoved } == page,
                        onMove = { delta -> onMovePage(page, delta) },
                        onToggle = { onToggleHidden(page) },
                    )
                }
            }

            item { SectionTitle(stringResource(R.string.settings_link)) }
            item {
                ChoiceRow(
                    options = TransportMode.entries.map { mode -> mode.label },
                    selected = TransportMode.entries.indexOf(transportMode),
                    onSelect = { index -> onTransportMode(TransportMode.entries[index]) },
                )
            }
            item {
                Note(stringResource(R.string.settings_link_note))
            }

            item { SectionTitle(stringResource(R.string.settings_phone)) }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(CARD_BACKGROUND)
                        .clickable(onClick = onOpenConnect)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = peerDescription,
                        color = CockpitColors.OnBackground,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            item { Note(stringResource(R.string.settings_phone_note)) }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(CARD_BACKGROUND)
                        .clickable(onClick = onToggleSession)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (sessionRunning) {
                                R.string.settings_stop_session
                            } else {
                                R.string.settings_start_session
                            },
                        ),
                        color = if (sessionRunning) CockpitColors.Warning else CockpitColors.OnBackground,
                        fontSize = 12.sp,
                    )
                }
            }
            item {
                Note(stringResource(R.string.settings_session_note))
            }

            item { SectionTitle(stringResource(R.string.settings_about)) }
            item {
                Note(stringResource(R.string.settings_about_version, appVersion))
            }
            if (attribution.isNotBlank()) {
                item {
                    Text(
                        text = attribution,
                        color = CockpitColors.OnBackground,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                    )
                }
            }
            item {
                Note(stringResource(R.string.settings_about_maps))
            }
            item {
                Note(stringResource(R.string.settings_about_licence))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = CockpitColors.Primary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        color = CockpitColors.Muted,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
    )
}

/**
 * A row of mutually exclusive choices.
 *
 * Buttons rather than a picker: two or three short options fit across a watch face,
 * and a picker would need a focus of its own, which competes with the one focusable
 * the bezel needs.
 */
@Composable
private fun ChoiceRow(
    @Suppress("ComposableNaming") options: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { index, labelId ->
            val active = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) CockpitColors.Primary else CARD_BACKGROUND)
                    .clickable { onSelect(index) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(labelId),
                    color = if (active) CockpitColors.Background else CockpitColors.OnBackground,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PageRow(
    page: WearPage,
    visible: Boolean,
    first: Boolean,
    last: Boolean,
    onMove: (Int) -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(CARD_BACKGROUND)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The dot doubles as the switch: a filled one is a page in the pager, a hollow
        // one is a page switched off. One target instead of a label plus a checkbox,
        // which is as much as a watch row has space for.
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(if (visible) CockpitColors.Good else CARD_OUTLINE)
                .clickable(enabled = page.canBeHidden, onClick = onToggle),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(page.label),
            color = if (visible) CockpitColors.OnBackground else CockpitColors.Muted,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (page.canBeMoved) {
            MoveButton("↑", enabled = !first) { onMove(-1) }
            Spacer(modifier = Modifier.width(4.dp))
            MoveButton("↓", enabled = !last) { onMove(1) }
        } else {
            Text(
                text = stringResource(R.string.settings_screens_pinned),
                color = CockpitColors.Muted,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun MoveButton(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) CARD_OUTLINE else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            color = if (enabled) CockpitColors.OnBackground else CockpitColors.Muted,
            fontSize = 13.sp,
        )
    }
}

private val CARD_BACKGROUND = Color(0xFF14181C)
private val CARD_OUTLINE = Color(0xFF2A3138)
