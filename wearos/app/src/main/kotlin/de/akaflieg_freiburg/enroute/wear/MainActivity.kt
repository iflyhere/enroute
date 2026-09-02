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

package de.akaflieg_freiburg.enroute.wear

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.akaflieg_freiburg.enroute.wear.transport.http.HttpNavTransport
import de.akaflieg_freiburg.enroute.wear.ui.data.DataScreen
import de.akaflieg_freiburg.enroute.wear.ui.data.DataViewModel
import de.akaflieg_freiburg.enroute.wear.ui.theme.EnrouteWearTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Without this, Wear OS returns to the watch face after about ten seconds
        // without touch input -- and a pilot with both hands on the controls touches
        // nothing. Keeping the display awake is the single most important thing this
        // app does to be usable in flight.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent { EnrouteWearApp() }
    }
}

@Composable
private fun EnrouteWearApp() {
    val factory = remember {
        DataViewModel.Factory {
            HttpNavTransport(
                host = Config.DEFAULT_HOST,
                port = Config.DEFAULT_PORT,
                pairingCode = Config.DEFAULT_PAIRING_CODE,
            )
        }
    }
    val viewModel: DataViewModel = viewModel(factory = factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // The session runs for as long as this screen is composed. Keeping it alive with the
    // display off needs a foreground service, which is separate, later work.
    DisposableEffect(viewModel) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }

    EnrouteWearTheme {
        DataScreen(state = state)
    }
}
