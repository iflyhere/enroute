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

package de.akaflieg_freiburg.enroute.wear.ui.nearby

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
import androidx.wear.compose.material3.Text
import de.akaflieg_freiburg.enroute.wear.domain.NearbyBoard
import de.akaflieg_freiburg.enroute.wear.domain.NearbyPlace
import androidx.compose.ui.res.stringResource
import de.akaflieg_freiburg.enroute.wear.R
import de.akaflieg_freiburg.enroute.wear.ui.theme.CockpitColors

/**
 * Aerodromes, navaids and waypoints near the aircraft, as the phone lists them.
 *
 * The order is the phone's -- nearest first, twenty of each type -- and so are the
 * distance lines, which carry the pilot's own units. Nothing is re-sorted or re-filtered
 * here.
 *
 * The three groups are kept apart rather than merged into one distance-ordered list.
 * A pilot looking for somewhere to land is not helped by three reporting points
 * sitting between them and the nearest aerodrome.
 */
@Composable
fun NearbyScreen(
    board: NearbyBoard?,
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
                text = stringResource(R.string.nearby_waiting),
                color = CockpitColors.Muted,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            return@Box
        }

        if (!board.positionKnown) {
            // Not an empty list: the phone does not know where the aircraft is, and
            // an empty list would read as "nothing around here".
            Text(
                text = stringResource(R.string.nearby_position_unknown),
                color = CockpitColors.Caution,
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
            item {
                Text(
                    text = stringResource(R.string.nearby_title),
                    color = CockpitColors.OnBackground,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }

            // Resource ids rather than resolved text: the content of a lazy list is
            // not a composable scope, so the heading is looked up inside the item that
            // draws it. The key stays the identifier, which does not move with the
            // display language.
            listOf(
                Triple("ad", R.string.nearby_aerodromes, board.aerodromes),
                Triple("nav", R.string.nearby_navaids, board.navaids),
                Triple("wp", R.string.nearby_waypoints, board.waypoints),
            ).forEach { (key, titleId, places) ->
                if (places.isEmpty()) {
                    return@forEach
                }
                item(key = "head-" + key) { SectionTitle(stringResource(titleId)) }
                places.forEachIndexed { index, place ->
                    // The index is in the key as well as the name: two reporting
                    // points can share a name, and a duplicate key crashes a lazy
                    // list outright.
                    item(key = key + "-" + place.name + "-" + index) { PlaceCard(place) }
                }
            }

            if (board.aerodromes.isEmpty() &&
                board.navaids.isEmpty() &&
                board.waypoints.isEmpty()
            ) {
                item {
                    Text(
                        text = stringResource(R.string.nearby_none),
                        color = CockpitColors.Muted,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
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
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun PlaceCard(place: NearbyPlace) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(CARD_BACKGROUND)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = place.name,
                color = CockpitColors.OnBackground,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            place.category?.let { category ->
                Text(text = category, color = CockpitColors.Muted, fontSize = 10.sp)
            }
        }

        place.extendedName?.takeIf { name -> name != place.name }?.let { name ->
            Text(
                text = name,
                color = CockpitColors.Muted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        place.way?.let { way ->
            Text(text = way, color = CockpitColors.Primary, fontSize = 11.sp)
        }
    }
}

private val CARD_BACKGROUND = Color(0xFF14181C)
