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

package de.akaflieg_freiburg.enroute.wear.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import de.akaflieg_freiburg.enroute.wear.Config
import de.akaflieg_freiburg.enroute.wear.data.Discovery
import de.akaflieg_freiburg.enroute.wear.data.DiscoveredPhone
import de.akaflieg_freiburg.enroute.wear.data.DiscoveryEvent
import de.akaflieg_freiburg.enroute.wear.data.SettingsStore
import de.akaflieg_freiburg.enroute.wear.ui.connect.CodeEntryScreen
import de.akaflieg_freiburg.enroute.wear.ui.connect.ConnectScreen
import de.akaflieg_freiburg.enroute.wear.ui.data.DataScreen
import de.akaflieg_freiburg.enroute.wear.ui.data.DataUiState

private enum class Screen { Data, Connect, CodeEntry }

/**
 * The whole user interface.
 *
 * Deliberately a small state machine rather than a navigation graph: three screens do
 * not justify the dependency, and swipe-to-dismiss on Wear needs handling either way.
 *
 * A long press on the data screen opens the connection screen. That keeps the data
 * screen free of controls a pilot could hit by accident, and there is nothing else on
 * it to compete with a long press.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EnrouteWearUi(
    state: DataUiState,
    settings: SettingsStore,
    discovery: Discovery,
    onSettingsChanged: () -> Unit,
) {
    // Start on the connection screen when the address has never been set, so that a
    // fresh install leads somewhere useful instead of retrying localhost forever.
    var screen by remember {
        mutableStateOf(
            if (settings.host == Config.DEFAULT_HOST) Screen.Connect else Screen.Data,
        )
    }
    val phones = remember { mutableStateListOf<DiscoveredPhone>() }
    var discoveryError by remember { mutableStateOf<String?>(null) }

    // Listen for beacons only while the connection screen is up: the radio should not
    // stay awake for discovery once a phone has been chosen.
    LaunchedEffect(screen) {
        if (screen != Screen.Connect) {
            return@LaunchedEffect
        }
        phones.clear()
        discoveryError = null
        discovery.events().collect { event ->
            when (event) {
                is DiscoveryEvent.Found ->
                    if (phones.none { it.host == event.phone.host && it.port == event.phone.port }) {
                        phones.add(event.phone)
                    }

                is DiscoveryEvent.Failed -> discoveryError = event.reason
            }
        }
    }

    when (screen) {
        Screen.Data -> Box(
            modifier = Modifier
                .fillMaxSize()
                .combinedClickable(
                    onClick = {},
                    onLongClick = { screen = Screen.Connect },
                ),
        ) {
            DataScreen(state = state)
        }

        Screen.Connect -> ConnectScreen(
            phones = phones,
            discoveryError = discoveryError,
            currentHost = settings.host,
            currentPort = settings.port,
            onSelectPhone = { phone ->
                settings.host = phone.host
                settings.port = phone.port
                onSettingsChanged()
                screen = Screen.Data
            },
            onEnterCode = { screen = Screen.CodeEntry },
        )

        Screen.CodeEntry -> CodeEntryScreen(
            initialCode = settings.pairingCode,
            onCodeEntered = { code ->
                settings.pairingCode = code
                onSettingsChanged()
                screen = Screen.Connect
            },
        )
    }
}
