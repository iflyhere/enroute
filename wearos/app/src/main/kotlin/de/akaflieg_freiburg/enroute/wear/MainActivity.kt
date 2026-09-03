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

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.akaflieg_freiburg.enroute.wear.data.Discovery
import de.akaflieg_freiburg.enroute.wear.data.SettingsStore
import de.akaflieg_freiburg.enroute.wear.service.NavSessionService
import de.akaflieg_freiburg.enroute.wear.ui.EnrouteWearUi
import de.akaflieg_freiburg.enroute.wear.ui.data.DataViewModel
import de.akaflieg_freiburg.enroute.wear.ui.theme.EnrouteWearTheme

class MainActivity : ComponentActivity() {

    private lateinit var settings: SettingsStore

    // The platform requires a notification for a foreground service, and from API 33
    // it requires permission to post one. Without the permission the service still
    // runs but its notification is invisible, which is worse than asking.
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // A running activity receives onNewIntent rather than onCreate, so without this
    // an override passed on the command line was silently ignored unless the app had
    // been stopped first. The activity is declared singleTop, which is what makes this
    // callback fire at all.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        settings.applyOverrides(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Without this, Wear OS returns to the watch face after about ten seconds
        // without touch input -- and a pilot with both hands on the controls touches
        // nothing. Keeping the display awake is the single most important thing this
        // app does to be usable in flight.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        settings = SettingsStore(this)
        settings.applyOverrides(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val discovery = Discovery(getSystemService(Context.WIFI_SERVICE) as? WifiManager)

        setContent { EnrouteWearApp(settings, discovery) }
    }
}

@Composable
private fun EnrouteWearApp(settings: SettingsStore, discovery: Discovery) {
    val context = LocalContext.current
    val viewModel: DataViewModel = viewModel(factory = remember { DataViewModel.Factory() })
    val uiState = viewModel.uiState.collectAsStateWithLifecycle()

    // The service owns the session, so it is started once and then left alone.
    // Deliberately not stopped when this screen goes away: surviving that is the
    // entire point of it.
    LaunchedEffect(Unit) {
        if (settings.host != Config.DEFAULT_HOST) {
            NavSessionService.start(context)
        }
    }

    EnrouteWearTheme {
        EnrouteWearUi(
            uiState = uiState,
            settings = settings,
            discovery = discovery,
            onSettingsChanged = {
                // A restart is what makes the service pick up the new address.
                NavSessionService.stop(context)
                NavSessionService.start(context)
            },
        )
    }
}
