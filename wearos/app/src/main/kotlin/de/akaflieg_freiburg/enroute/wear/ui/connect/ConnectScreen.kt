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

package de.akaflieg_freiburg.enroute.wear.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.Text
import de.akaflieg_freiburg.enroute.wear.data.DiscoveredPhone
import de.akaflieg_freiburg.enroute.wear.ui.theme.CockpitColors

/**
 * Picks the phone to listen to.
 *
 * The list is filled by the discovery beacon, so in the common case the pilot taps a
 * phone and never types anything. Discovery is a convenience though, not a guarantee:
 * plenty of networks block broadcast between clients, which is why the current address
 * is always shown and why the pairing code is entered by hand.
 */
@Composable
fun ConnectScreen(
    phones: List<DiscoveredPhone>,
    discoveryError: String?,
    currentHost: String,
    currentPort: Int,
    onSelectPhone: (DiscoveredPhone) -> Unit,
    onEnterCode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberScalingLazyListState()

    ScalingLazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .background(CockpitColors.Background),
    ) {
        item {
            ListHeader { Text(text = "Phone") }
        }

        if (phones.isEmpty()) {
            item {
                // Say why nothing is appearing. "Searching" forever is the least
                // useful thing a screen can do, and the usual cause is that the
                // watch has dropped Wi-Fi, which the pilot can act on.
                Text(
                    text = discoveryError
                        ?: "Searching. The phone must be on the same Wi-Fi network",
                    color = if (discoveryError == null) {
                        CockpitColors.Muted
                    } else {
                        CockpitColors.Caution
                    },
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .testTag(TAG_SEARCHING),
                )
            }
        } else {
            items(phones) { phone ->
                Button(
                    onClick = { onSelectPhone(phone) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TAG_PHONE_PREFIX + phone.host),
                ) {
                    Text(text = phone.host, fontSize = 15.sp, maxLines = 1)
                }
            }
        }

        item {
            ListHeader { Text(text = "Using") }
        }
        item {
            Text(
                text = "$currentHost:$currentPort",
                color = CockpitColors.Muted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TAG_CURRENT),
            )
        }

        item {
            Button(
                onClick = onEnterCode,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TAG_ENTER_CODE),
            ) {
                Text(text = "Pairing code", fontSize = 15.sp)
            }
        }
    }
}

const val TAG_SEARCHING = "connect.searching"
const val TAG_CURRENT = "connect.current"
const val TAG_ENTER_CODE = "connect.enterCode"
const val TAG_PHONE_PREFIX = "connect.phone."
