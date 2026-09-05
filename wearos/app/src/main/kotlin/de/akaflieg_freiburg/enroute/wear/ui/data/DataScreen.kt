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

package de.akaflieg_freiburg.enroute.wear.ui.data

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text
import de.akaflieg_freiburg.enroute.wear.data.ConnectionState
import de.akaflieg_freiburg.enroute.wear.domain.Measured
import de.akaflieg_freiburg.enroute.wear.transport.FailureReason
import de.akaflieg_freiburg.enroute.wear.domain.RouteStatus
import de.akaflieg_freiburg.enroute.wear.domain.WaypointLeg
import de.akaflieg_freiburg.enroute.wear.ui.theme.CockpitColors

/**
 * The primary screen: what the phone shows in its own RemainingRouteBar, laid out for a
 * round watch face and readable at arm's length.
 *
 * Two rules from the protocol are enforced here rather than trusted to the caller.
 * Next- and final-waypoint values are rendered only while the status is OnRoute, because
 * that is the only state in which the phone guarantees them. And the values are never
 * blanked when the link drops -- they dim and grow an age label, since an old number
 * honestly labelled as old is far more use in a cockpit than an empty screen.
 */
@Composable
fun DataScreen(
    state: DataUiState,
    modifier: Modifier = Modifier,
) {
    val frame = state.frame
    val dimmed = state.freshness == Freshness.Old || state.freshness == Freshness.Disconnected
    val contentAlpha = if (dimmed) DIMMED_ALPHA else 1f

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CockpitColors.Background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Inset for a round display: the corners of a square layout fall under
                // the bezel, so content stays inside roughly 80 % of the width.
                .padding(horizontal = ROUND_INSET),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            LinkRow(state)

            if (frame == null) {
                Spacer(Modifier.height(8.dp))
                // Driven by the last failure rather than by the live connection
                // state. The state passes through Connecting on every retry, and
                // following it made this line alternate once per backoff period --
                // which is what a pilot saw as a flicker every ten seconds.
                Text(
                    text = connectionMessage(
                        state.session.connection,
                        state.session.lastFailure,
                    ),
                    color = CockpitColors.Muted,
                    fontSize = 15.sp,
                    modifier = Modifier.testTag(TAG_EMPTY),
                )
                if (state.session.connection == ConnectionState.Rejected) {
                    Text(
                        // The states the pilot has to act on, so each says how. A
                        // permission is granted in the watch's own settings, not here,
                        // and re-pairing would not help.
                        text = if (state.session.lastFailure == FailureReason.PermissionMissing) {
                            "Allow Bluetooth in\nthe watch settings"
                        } else {
                            "Long press to re-pair"
                        },
                        color = CockpitColors.Muted,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                return@Column
            }

            val next = frame.next
            val showLegs = frame.status.hasLegData && next != null

            if (showLegs) {
                LegBlock(leg = next, alpha = contentAlpha, tagPrefix = TAG_NEXT)
            } else {
                Spacer(Modifier.height(6.dp))
                PlaceholderBlock(alpha = contentAlpha)
            }

            Spacer(Modifier.height(6.dp))

            // Ground speed and altitude stay meaningful in every status, so they are
            // shown whenever the phone sent a position at all.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ValueText(frame.position.groundSpeed, alpha = contentAlpha, tag = TAG_GS)
                ValueText(frame.position.altitudeAmsl, alpha = contentAlpha, tag = TAG_ALT)
            }

            // The flight level, from the phone's barometer, formatted there so it reads
            // exactly as the moving map's own bar does. Dimmed when the phone has a
            // reading it does not believe: it is worse to hide a distrusted flight level
            // than to show it as distrusted, and worse still to show it as fact.
            if (frame.flightLevel.text != Measured.PLACEHOLDER) {
                Text(
                    text = frame.flightLevel.text,
                    color = if (frame.flightLevelImplausible) {
                        CockpitColors.Caution
                    } else {
                        CockpitColors.OnBackground
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(contentAlpha)
                        .testTag(TAG_FL),
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(4.dp))

            // The final-waypoint row and the status banner are mutually exclusive: the
            // vertical budget on a 227 dp disc does not stretch to both.
            val banner = statusBanner(frame.status, frame.statusText, frame.note)
            val finalLeg = frame.final
            if (banner != null) {
                Text(
                    text = banner.text,
                    color = banner.color,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TAG_BANNER),
                )
            } else if (showLegs && finalLeg != null) {
                FinalRow(finalLeg, alpha = contentAlpha)
            }
        }
    }
}

@Composable
private fun LinkRow(state: DataUiState) {
    val dotColor = when (state.freshness) {
        Freshness.Live -> CockpitColors.Good
        Freshness.Stale -> CockpitColors.Caution
        Freshness.Old, Freshness.Disconnected -> CockpitColors.Warning
        Freshness.NoData -> CockpitColors.Muted
    }
    val showAge = state.freshness != Freshness.Live && state.freshness != Freshness.NoData
    val age = state.ageSeconds

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = LINK_DOT, color = dotColor, fontSize = 10.sp)
        if (showAge && age != null) {
            Text(
                text = "  " + formatAge(age),
                color = dotColor,
                fontSize = 12.sp,
                modifier = Modifier.testTag(TAG_AGE),
            )
        }
        // Not shown for Rejected: nothing is being retried there, and saying so
        // would be the app claiming to work on a problem only the pilot can fix.
        if (state.session.connection is ConnectionState.Retrying) {
            Text(
                text = "  reconnecting",
                color = CockpitColors.Warning,
                fontSize = 12.sp,
                modifier = Modifier.testTag(TAG_RECONNECT),
            )
        }
    }
}

@Composable
private fun LegBlock(leg: WaypointLeg, alpha: Float, tagPrefix: String) {
    Text(
        text = leg.name,
        color = CockpitColors.OnBackground.copy(alpha = alpha),
        fontSize = 26.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.testTag(tagPrefix + TAG_NAME_SUFFIX),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = leg.distance.text,
            color = CockpitColors.OnBackground.copy(alpha = alpha),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag(tagPrefix + TAG_DIST_SUFFIX),
        )
        Text(
            text = formatCourse(leg.trueCourseDeg),
            color = CockpitColors.Primary.copy(alpha = alpha),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag(tagPrefix + TAG_TC_SUFFIX),
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Text(
            text = leg.eteText,
            color = CockpitColors.Muted.copy(alpha = alpha),
            fontSize = 15.sp,
            modifier = Modifier.testTag(tagPrefix + TAG_ETE_SUFFIX),
        )
        Text(
            text = leg.etaText,
            color = CockpitColors.Muted.copy(alpha = alpha),
            fontSize = 15.sp,
            modifier = Modifier.testTag(tagPrefix + TAG_ETA_SUFFIX),
        )
    }
}

@Composable
private fun PlaceholderBlock(alpha: Float) {
    Text(
        text = DASHES,
        color = CockpitColors.Muted.copy(alpha = alpha),
        fontSize = 26.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.testTag(TAG_NEXT + TAG_NAME_SUFFIX),
    )
    Text(
        text = DASHES,
        color = CockpitColors.Muted.copy(alpha = alpha),
        fontSize = 22.sp,
        modifier = Modifier.testTag(TAG_NEXT + TAG_DIST_SUFFIX),
    )
}

@Composable
private fun FinalRow(leg: WaypointLeg, alpha: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TAG_FINAL_ROW),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Text(
            text = leg.name,
            color = CockpitColors.Muted.copy(alpha = alpha),
            fontSize = 13.sp,
            maxLines = 1,
        )
        Text(
            text = leg.distance.text,
            color = CockpitColors.Muted.copy(alpha = alpha),
            fontSize = 13.sp,
        )
        Text(
            text = leg.etaText,
            color = CockpitColors.Muted.copy(alpha = alpha),
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun ValueText(value: Measured, alpha: Float, tag: String) {
    Text(
        text = value.text,
        color = CockpitColors.OnBackground.copy(alpha = alpha),
        fontSize = 17.sp,
        modifier = Modifier.testTag(tag),
    )
}

private class Banner(val text: String, val color: Color)

/**
 * The message for a status, preferring the string the phone sent so that the wording and
 * its translation match the phone's own display exactly. The fallbacks only apply when
 * the frame arrived without the formatted block.
 */
private fun statusBanner(status: RouteStatus, statusText: String, note: String): Banner? =
    when (status) {
        RouteStatus.OnRoute -> note.takeIf { it.isNotEmpty() }?.let {
            Banner(firstSentence(it), CockpitColors.Caution)
        }

        RouteStatus.NoRoute -> Banner(
            statusText.ifEmpty { "No route set on the phone" },
            CockpitColors.Muted,
        )

        RouteStatus.NearDestination -> Banner(
            statusText.ifEmpty { "Near destination." },
            CockpitColors.Good,
        )

        RouteStatus.PositionUnknown -> Banner(
            statusText.ifEmpty { "Position unknown." },
            CockpitColors.Caution,
        )

        RouteStatus.OffRoute -> Banner(
            statusText.ifEmpty { "Off route." },
            CockpitColors.Caution,
        )

        // A status value this build has never heard of. Say so plainly instead of
        // guessing what it might mean.
        RouteStatus.Unknown -> Banner(
            "Unsupported status from phone",
            CockpitColors.Caution,
        )
    }

/**
 * The first sentence of a multi-sentence note.
 *
 * The phone's note can run to three sentences, which a two-line banner on a 226 dp
 * disc truncates mid-word. The first sentence is the part a pilot can act on; the
 * rest elaborates and belongs on the phone.
 */
internal fun firstSentence(text: String): String {
    val end = text.indexOf('.')
    return if (end < 0) text else text.substring(0, end + 1)
}

internal fun formatCourse(degrees: Double?): String =
    if (degrees == null) Measured.PLACEHOLDER else "${Math.round(degrees)}°"

private val ROUND_INSET = 22.dp
private const val DIMMED_ALPHA = 0.45f
private const val DASHES = "- - -"
private const val LINK_DOT = "●"

const val TAG_NEXT = "next"
const val TAG_FINAL_ROW = "final.row"
const val TAG_NAME_SUFFIX = ".name"
const val TAG_DIST_SUFFIX = ".dist"
const val TAG_TC_SUFFIX = ".tc"
const val TAG_ETE_SUFFIX = ".ete"
const val TAG_ETA_SUFFIX = ".eta"
const val TAG_GS = "gs"
const val TAG_ALT = "alt"
const val TAG_FL = "fl"
const val TAG_BANNER = "banner"
const val TAG_AGE = "age"
const val TAG_RECONNECT = "reconnect"
const val TAG_EMPTY = "empty"

/**
 * What to say while there is no navigation frame.
 *
 * A free function so the one rule that caused a visible defect can be tested: the
 * message must not change while the link is merely retrying, because the connection
 * state legitimately passes through Connecting on every attempt.
 */
fun connectionMessage(connection: ConnectionState, lastFailure: FailureReason?): String = when {
    // Before the Rejected case below, which it shares: the state says the link will not
    // come back on its own, and only this says why, which is the half a pilot can act on.
    lastFailure == FailureReason.PermissionMissing -> "Bluetooth not allowed"
    connection == ConnectionState.Rejected -> "Wrong pairing code"
    lastFailure == FailureReason.Unauthorized -> "Wrong pairing code"
    lastFailure != null -> "No connection"
    connection == ConnectionState.Connecting -> "Connecting"
    connection == ConnectionState.Idle -> "Not connected"
    else -> "Waiting for data"
}
