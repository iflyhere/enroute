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

import android.util.Log

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import de.akaflieg_freiburg.enroute.wear.data.Discovery
import de.akaflieg_freiburg.enroute.wear.data.DiscoveredPhone
import de.akaflieg_freiburg.enroute.wear.data.DiscoveryEvent
import de.akaflieg_freiburg.enroute.wear.data.SettingsStore
import de.akaflieg_freiburg.enroute.wear.transport.TransportMode
import de.akaflieg_freiburg.enroute.wear.ui.connect.CodeEntryScreen
import de.akaflieg_freiburg.enroute.wear.ui.connect.ConnectScreen
import de.akaflieg_freiburg.enroute.wear.ui.data.DataScreen
import de.akaflieg_freiburg.enroute.wear.ui.data.DataUiState
import de.akaflieg_freiburg.enroute.wear.ui.map.MapLibreScreen
import de.akaflieg_freiburg.enroute.wear.ui.instruments.Instrument
import de.akaflieg_freiburg.enroute.wear.ui.instruments.InstrumentScreen
import de.akaflieg_freiburg.enroute.wear.ui.log.FlightLogScreen
import de.akaflieg_freiburg.enroute.wear.ui.nearby.NearbyScreen
import de.akaflieg_freiburg.enroute.wear.ui.notam.NotamScreen
import de.akaflieg_freiburg.enroute.wear.ui.route.RouteScreen
import de.akaflieg_freiburg.enroute.wear.ui.settings.SettingsScreen
import de.akaflieg_freiburg.enroute.wear.ui.traffic.TrafficScreen
import de.akaflieg_freiburg.enroute.wear.ui.traffic.steppedRangeM
import de.akaflieg_freiburg.enroute.wear.ui.route.ZoomLevel
import de.akaflieg_freiburg.enroute.wear.ui.weather.WeatherScreen
import kotlinx.coroutines.launch

private enum class Screen { Main, Connect, CodeEntry }


/**
 * The whole user interface.
 *
 * Deliberately a small state machine rather than a navigation graph: a handful of
 * screens do not justify the dependency, and swipe-to-dismiss on Wear needs handling
 * either way.
 *
 * The data screen, the map and the NOTAM list are pages of one pager, so a sideways
 * swipe moves between them. A long press on any of them opens the connection screen;
 * that keeps controls off the screens a pilot reads in flight.
 *
 * @param uiState Passed as State rather than a value so that the map can read the
 * aircraft position inside its draw lambda. See RouteScreen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EnrouteWearUi(
    uiState: State<DataUiState>,
    settings: SettingsStore,
    discovery: Discovery,
    onSettingsChanged: () -> Unit,
) {
    // Start on the connection screen when no phone has ever been chosen, so that a
    // fresh install leads somewhere useful instead of retrying localhost forever.
    var screen by remember {
        mutableStateOf(if (settings.isConfigured) Screen.Main else Screen.Connect)
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
        Screen.Main -> MainPages(
            uiState = uiState,
            settings = settings,
            onOpenConnect = { screen = Screen.Connect },
            onRestartSession = onSettingsChanged,
        )

        Screen.Connect -> ConnectScreen(
            phones = phones,
            discoveryError = discoveryError,
            currentHost = settings.host,
            currentPort = settings.port,
            onSelectPhone = { phone ->
                settings.host = phone.host
                settings.port = phone.port
                onSettingsChanged()
                screen = Screen.Main
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MainPages(
    uiState: State<DataUiState>,
    settings: SettingsStore,
    onOpenConnect: () -> Unit,
    onRestartSession: () -> Unit,
) {
    val host = settings.host
    val port = settings.port
    val pairingCode = settings.pairingCode

    // The layout is held in composition and written through to the store, rather than
    // read from the store on every recomposition: SharedPreferences is a file, and the
    // pager reads this on every frame it draws.
    var order by remember { mutableStateOf(settings.pageOrder) }
    var hidden by remember { mutableStateOf(settings.hiddenPages) }
    var bezelAction by remember { mutableStateOf(BezelAction.byId(settings.bezelAction)) }
    var chartMode by remember { mutableStateOf(ChartMode.byId(settings.chartMode)) }
    var transportMode by remember {
        mutableStateOf(TransportMode.byId(settings.transportMode))
    }
    var alarmVibration by remember { mutableStateOf(settings.alarmVibration) }

    // Null means "fit what is drawn". Not persisted: a range a pilot chose two flights
    // ago is not a range they chose for this one.
    var radarRangeM by remember { mutableStateOf<Double?>(null) }

    // Which of the three round instruments is on the face. Not persisted: it is a
    // glance, not a preference.
    var instrument by remember { mutableStateOf(Instrument.Altimeter) }

    val pages = visiblePages(order, hidden)

    // Every page, so the settings list can offer a hidden one back. The pager
    // uses the filtered list above; these two must not be the same value.
    val allPages = orderedPages(order)

    var zoom by remember { mutableStateOf<ZoomLevel>(ZoomLevel.Automatic) }
    val pagerState = rememberPagerState(initialPage = 0) { pages.size }
    val scope = rememberCoroutineScope()

    // Hoisted so the one rotary handler below can scroll them. Giving each list its own
    // rotaryScrollable would mean several focusables competing with the pager's, and
    // then which of them holds focus decides what the bezel does -- a race with no good
    // outcome. One focusable, one handler, dispatch by page.
    val notamListState = rememberScalingLazyListState()
    // A plain scroll state, because the traffic page is a Column: the radar has
    // to be sized to the viewport to sit centred, which a scaling lazy column
    // squeezes.
    val trafficScrollState = rememberScrollState()
    val nearbyListState = rememberScalingLazyListState()
    val weatherListState = rememberScalingLazyListState()
    val logListState = rememberScalingLazyListState()
    val settingsListState = rememberScalingLazyListState()

    // A picker or a rotary modifier is inert unless something in the hierarchy holds
    // focus. This is the trap every Wear developer hits once.
    val focusRequester = remember { FocusRequester() }

    // Re-taken on every page change, not once at the start. Swiping to the map attaches
    // a MapView, and a View that lands in the hierarchy can take focus with it; asking
    // for it back afterwards is what keeps the bezel working on every page.
    LaunchedEffect(pagerState.currentPage) {
        runCatching { focusRequester.requestFocus() }
    }

    // A bezel emits many small events per detent, so they are accumulated and only a
    // full threshold moves a step. Otherwise one flick runs through the whole range.
    var rotaryAccumulator by remember { mutableStateOf(0f) }

    // The page the bezel is steering towards while the pager is still animating there.
    // Null means "wherever the pager says", which is the truth once it has settled.
    var rotaryTarget by remember { mutableStateOf<Int?>(null) }

    // Released whenever the pager comes to rest, whatever brought it there: an
    // animation that finished, one a swipe interrupted, or the swipe itself. Clearing
    // it from the animation's own coroutine leaked -- a swipe cancels that coroutine,
    // so the target stayed set for the rest of the session and every later turn of the
    // bezel counted from a page the pilot was no longer on.
    LaunchedEffect(pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress) {
            rotaryTarget = null
        }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier
            .fillMaxSize()
            .onRotaryScrollEvent { event ->
                // Logged because a bezel that does nothing gives no clue whether the
                // events are missing or the handling is, and the two need opposite
                // fixes. This line is what tells them apart on a real watch.
                Log.d(TAG, "rotary " + event.verticalScrollPixels +
                    " on page " + pagerState.currentPage)

                val page = pages.getOrNull(pagerState.currentPage)

                // Switching screens wins over the page's own use of the bezel, because
                // it is the setting the pilot chose. Zoom stays reachable everywhere
                // through the vertical drag below, which is why this is safe to take.
                if (bezelAction == BezelAction.Pages) {
                    val (step, carry) = rotarySteps(
                        rotaryAccumulator + event.verticalScrollPixels,
                        ROTARY_PAGE_THRESHOLD,
                    )
                    rotaryAccumulator = carry
                    if (step != 0) {
                        // Counted from where the pager is heading, not from where it
                        // is. currentPage does not move until the scroll animation
                        // finishes, so a burst of events -- which is what a bezel
                        // delivers -- all computed the same target and collapsed into
                        // one step by accident. That looked correct and was luck:
                        // straddle an animation boundary and the same gesture moves
                        // two pages instead of one.
                        val from = rotaryTarget ?: pagerState.currentPage
                        val target = (from + step).coerceIn(0, pages.size - 1)
                        rotaryTarget = target
                        scope.launch { pagerState.animateScrollToPage(target) }
                    }
                    return@onRotaryScrollEvent true
                }

                when (page) {
                    WearPage.Map -> {
                        val (step, carry) = rotarySteps(
                            rotaryAccumulator + event.verticalScrollPixels,
                            ROTARY_THRESHOLD,
                        )
                        rotaryAccumulator = carry
                        // One zoom step an event, unlike the pages: a scale is worth
                        // fine control, and overshooting it costs a turn back rather
                        // than losing the screen a pilot was reading.
                        if (step != 0) {
                            zoom = ZoomLevel.stepped(zoom, step)
                        }
                        // Consumed, or the system scrolls something else instead.
                        true
                    }

                    // One to one with the bezel rather than a fling: reading a NOTAM
                    // wants precise positioning, not momentum.
                    WearPage.Notam -> {
                        notamListState.dispatchRawDelta(event.verticalScrollPixels)
                        true
                    }

                    WearPage.Traffic -> {
                        trafficScrollState.dispatchRawDelta(event.verticalScrollPixels)
                        true
                    }

                    WearPage.Nearby -> {
                        nearbyListState.dispatchRawDelta(event.verticalScrollPixels)
                        true
                    }

                    // The instrument face does not scroll, so in content mode the
                    // bezel does what the tap does: move to the next instrument. A
                    // rotary control that visibly does nothing reads as a broken
                    // bezel rather than as a page with nothing to scroll.
                    WearPage.Instruments -> {
                        rotaryAccumulator += event.verticalScrollPixels
                        while (rotaryAccumulator >= ROTARY_THRESHOLD) {
                            rotaryAccumulator -= ROTARY_THRESHOLD
                            instrument = instrument.next()
                        }
                        while (rotaryAccumulator <= -ROTARY_THRESHOLD) {
                            rotaryAccumulator += ROTARY_THRESHOLD
                            instrument = instrument.previous()
                        }
                        true
                    }

                    WearPage.Weather -> {
                        weatherListState.dispatchRawDelta(event.verticalScrollPixels)
                        true
                    }

                    WearPage.Log -> {
                        logListState.dispatchRawDelta(event.verticalScrollPixels)
                        true
                    }

                    WearPage.Settings -> {
                        settingsListState.dispatchRawDelta(event.verticalScrollPixels)
                        true
                    }

                    else -> false
                }
            }
            .focusRequester(focusRequester)
            .focusable()
            .pointerInput(pages) {
                // A vertical drag zooms the map. Not a duplicate of the bezel: half
                // the Wear OS watches ever sold have no rotary input, and this one
                // gesture works on all of them, with one finger, through gloves, and
                // without looking. It is also what lets the bezel be spent on switching
                // screens without taking zoom away. The pager keeps horizontal drags,
                // so the two do not collide.
                var dragAccumulator = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragAccumulator = 0f },
                ) { change, dragAmount ->
                    // Map only. The traffic page has its own range control, on a tap,
                    // because its list consumes a vertical drag before this handler
                    // ever sees it -- measured on the watch, where the drag did
                    // nothing at all.
                    if (pages.getOrNull(pagerState.currentPage) != WearPage.Map) {
                        return@detectVerticalDragGestures
                    }
                    change.consume()
                    dragAccumulator += dragAmount
                    while (dragAccumulator <= -DRAG_THRESHOLD) {
                        dragAccumulator += DRAG_THRESHOLD
                        zoom = ZoomLevel.stepped(zoom, 1)
                    }
                    while (dragAccumulator >= DRAG_THRESHOLD) {
                        dragAccumulator -= DRAG_THRESHOLD
                        zoom = ZoomLevel.stepped(zoom, -1)
                    }
                }
            },
    ) { index ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .combinedClickable(
                    onClick = {},
                    onLongClick = onOpenConnect,
                ),
        ) {
            when (pages.getOrNull(index)) {
                WearPage.Data -> DataScreen(state = uiState.value)

                WearPage.Map -> {
                    // The real map when the pilot has downloaded one, and the vector
                    // drawing when they have not. The fallback is not a lesser version
                    // of the same thing: it needs nothing but the route, so it still
                    // works on a phone with no maps on it at all.
                    val mapRevision = uiState.value.session.peer?.mapRevision ?: 0L
                    if (mapRevision > 0L) {
                        MapLibreScreen(
                            styleUrl = "http://" + host + ":" + port +
                                "/enroute/v1/map/style.json",
                            host = host,
                            pairingCode = pairingCode,
                            route = uiState.value.session.route,
                            ownPosition = uiState.value.frame?.position,
                            charts = if (chartMode == ChartMode.Automatic) {
                                uiState.value.session.vacs
                            } else {
                                null
                            },
                            traffic = uiState.value.session.traffic,
                            port = port,
                            zoom = zoom,
                            isActive = pages.getOrNull(pagerState.currentPage) == WearPage.Map,
                            fallbackCentre = uiState.value.session.peer?.mapCentre,
                            fallbackZoom = uiState.value.session.peer?.mapCentreZoom ?: 0.0,
                            labelColour = uiState.value.session.peer?.mapLabelColour,
                            haloColour = uiState.value.session.peer?.mapHaloColour,
                        )
                    } else {
                        RouteScreen(
                            // Read at composition: a route changes a few times a flight.
                            route = uiState.value.session.route,
                            currentLeg = uiState.value.frame?.legIndex,
                            zoom = zoom,
                            // Read in the draw phase: the aircraft moves once a second.
                            ownPosition = { uiState.value.frame?.position },
                            notams = uiState.value.session.notams,
                        )
                    }
                }

                WearPage.Traffic -> TrafficScreen(
                    board = uiState.value.session.traffic,
                    ownPosition = uiState.value.frame?.position?.point,
                    ownTrackDeg = uiState.value.frame?.position?.trackDeg,
                    verticalUnit = uiState.value.session.peer?.verticalUnit ?: "ft",
                    rangeOverrideM = radarRangeM,
                    onRange = { step, currentM ->
                        radarRangeM = steppedRangeM(currentM, step)
                    },
                    scrollState = trafficScrollState,
                )

                WearPage.Instruments -> InstrumentScreen(
                    frame = uiState.value.frame,
                    showing = instrument,
                    verticalUnit = uiState.value.session.peer?.verticalUnit ?: "ft",
                    horizontalUnit = uiState.value.session.peer?.horizontalUnit ?: "nm",
                    onCycle = { instrument = instrument.next() },
                )

                WearPage.Nearby -> NearbyScreen(
                    board = uiState.value.session.nearby,
                    listState = nearbyListState,
                )

                WearPage.Notam -> NotamScreen(
                    board = uiState.value.session.notams,
                    listState = notamListState,
                )

                WearPage.Weather -> WeatherScreen(
                    board = uiState.value.session.weather,
                    listState = weatherListState,
                )

                WearPage.Log -> FlightLogScreen(
                    board = uiState.value.session.flightLog,
                    listState = logListState,
                )

                WearPage.Settings -> SettingsScreen(
                    pages = allPages,
                    hidden = hidden,
                    bezelAction = bezelAction,
                    alarmVibration = alarmVibration,
                    chartMode = chartMode,
                    transportMode = transportMode,
                    attribution = uiState.value.session.peer?.mapAttribution.orEmpty(),
                    peerDescription = peerDescription(uiState.value, host, port),
                    appVersion = APP_VERSION,
                    listState = settingsListState,
                    onMovePage = { page, delta ->
                        // Moved within every page, not within the visible ones: a step
                        // past a hidden neighbour has to count, or the order changes by
                        // two places the next time that page is switched back on.
                        order = movePage(allPages, page, delta)
                        settings.pageOrder = order
                    },
                    onToggleHidden = { page ->
                        hidden = if (page.id in hidden) hidden - page.id else hidden + page.id
                        settings.hiddenPages = hidden
                    },
                    onBezelAction = { action ->
                        bezelAction = action
                        settings.bezelAction = action.id
                    },
                    onAlarmVibration = { wanted ->
                        alarmVibration = wanted
                        settings.alarmVibration = wanted
                    },
                    onChartMode = { mode ->
                        chartMode = mode
                        settings.chartMode = mode.id
                    },
                    onTransportMode = { mode ->
                        transportMode = mode
                        settings.transportMode = mode.id
                        // The service builds a transport per connection attempt, so a
                        // change would otherwise take effect on the next reconnect.
                        // The pilot who just changed this is waiting for it now.
                        onRestartSession()
                    },
                    onOpenConnect = onOpenConnect,
                )

                null -> Unit
            }
        }
    }
}

/** One line naming the phone, for the settings page. */
private fun peerDescription(state: DataUiState, host: String, port: Int): String {
    val peer = state.session.peer
    val where = host + ":" + port
    return if (peer == null) where else "Enroute " + peer.appVersion + " at " + where
}

private const val ROTARY_THRESHOLD = 120f

/**
 * Pixels of rotation per page.
 *
 * A Galaxy Watch 7 bezel delivers 136 pixels an event, so this is about four events --
 * roughly a quarter turn -- to the screen. Deliberately much coarser than the zoom
 * threshold above: a sleeve brushing the rim should not change what a pilot is looking
 * at, and nine screens under one revolution would make that inevitable. It also keeps
 * the feel that was measured before the arithmetic was corrected, where one burst of
 * events happened to move exactly one page.
 */
private const val ROTARY_PAGE_THRESHOLD = 500f

// Pixels of drag per zoom step. A watch face is 480 pixels across, so this gives about
// six steps across the whole screen -- coarse enough to hit without aiming.
private const val DRAG_THRESHOLD = 80f

private const val TAG = "EnrouteWear"

// Kept beside the versionName in app/build.gradle.kts by hand. Reading
// BuildConfig here would mean switching that generation on for one string.
private const val APP_VERSION = "0.1.0"
