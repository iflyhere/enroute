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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import de.akaflieg_freiburg.enroute.wear.domain.GeoPoint
import de.akaflieg_freiburg.enroute.wear.domain.TrafficBoard
import de.akaflieg_freiburg.enroute.wear.ui.theme.CockpitColors
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The traffic picture as a traffic instrument draws it.
 *
 * Own aircraft at the centre, range rings around it, and every target at its bearing
 * and range with its height difference beside it. Track-up when the phone knows the
 * track, and it says so at the top when it does not -- a display that is quietly
 * north-up while looking track-up points a pilot at the wrong piece of sky.
 *
 * Colours are the phone's, one per alarm level. The only thing invented here is the
 * geometry.
 *
 * A target whose bearing the receiver does not know is drawn as a dashed ring at its
 * range. That is exactly what is known about it -- somewhere on that circle -- and it
 * is how the instruments in a cockpit show the same thing.
 */
@Composable
fun TrafficRadar(
    board: TrafficBoard,
    ownPosition: GeoPoint?,
    ownTrackDeg: Double?,
    verticalUnit: String,
    /** The range the pilot chose, or null to fit whatever is being drawn. */
    rangeOverrideM: Double?,
    /**
     * Called with the step and the range currently on screen.
     *
     * The current range travels with the step because the first tap has to move from
     * what the pilot is looking at. An earlier version stepped from a fixed default
     * instead, so one tap took a display showing 50 km straight to 1 km.
     */
    onRange: (step: Int, currentM: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    // What the phone would draw, and nothing else. See TrafficBoard.drawable.
    val fixes = radarFixes(board.drawable, ownPosition, ownTrackDeg)
    val rangeM = rangeOverrideM ?: radarRangeM(fixes)
    val labelled = labelledFixes(fixes.filter { fix -> fix.rangeM <= rangeM })
    val trackUp = ownTrackDeg != null

    Box(
        modifier = modifier.pointerInput(Unit) {
            // A tap rather than a drag. The drag is what every traffic instrument
            // uses for this, and it is also what the list under this radar consumes
            // before the pager's gesture handler can see it -- so on this page it
            // never fired. Upper half widens, lower half narrows, the same direction
            // the map's zoom gesture has.
            detectTapGestures { at ->
                onRange(if (at.y < size.height / 2f) 1 else -1, rangeM)
            }
        },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centre = Offset(size.width / 2f, size.height / 2f)
            // Room outside the outer ring for the labels, and for the round bezel.
            val radius = minOf(size.width, size.height) / 2f * RING_FRACTION

            drawRings(centre, radius, rangeM, measurer, trackUp)

            // The warning sector first, so the targets sit on top of it.
            if (board.warning != null) {
                mostAlarming(fixes)?.let { worst ->
                    drawWarningSector(centre, radius, worst.screenBearingDeg)
                }
            }

            // Gated on relevance like the rest, because the phone gates its own
            // version of this ring the same way.
            board.withoutBearing
                ?.takeIf { target -> target.relevant }
                ?.horizontalDistanceM
                ?.let { range -> drawUnknownBearingRing(centre, radius, range, rangeM) }

            // Anything past the chosen range is left off rather than pinned to the
            // outer ring. Pinning was the earlier behaviour and it lies about where a
            // contact is; with a manual range the pilot has said which piece of sky
            // they are looking at, and the list underneath still holds the rest.
            // Rectangles of the labels already drawn. A label that would land on one
            // of them is left off: six labels on clustered targets still overlap into
            // something unreadable, and an unreadable number is worse than a bare dot
            // whose name is in the list below.
            val placed = mutableListOf<Rect>()
            fixes.filter { fix -> fix.rangeM <= rangeM }.forEach { fix ->
                drawTarget(
                    fix, centre, radius, rangeM, measurer, verticalUnit,
                    withLabel = fix in labelled,
                    placed = placed,
                )
            }

            drawOwnShip(centre)

            // Whether the scale is following the traffic or was chosen. Without it a
            // pilot cannot tell a quiet sky from a range they narrowed earlier.
            // "fixed" rather than the range itself: the outer ring is already
            // labelled with it, and the same number twice on a 454 pixel disc reads
            // as two different things.
            val mode = measurer.measure(
                if (rangeOverrideM == null) "auto" else "fixed",
                TextStyle(color = RING_LABEL, fontSize = 10.sp),
            )
            drawText(
                textLayoutResult = mode,
                topLeft = Offset(
                    centre.x - mode.size.width / 2f,
                    centre.y + radius - mode.size.height,
                ),
            )
        }
    }
}

private fun DrawScope.drawRings(
    centre: Offset,
    radius: Float,
    rangeM: Double,
    measurer: TextMeasurer,
    trackUp: Boolean,
) {
    listOf(0.5f, 1.0f).forEach { fraction ->
        drawCircle(
            color = RING_COLOUR,
            radius = radius * fraction,
            center = centre,
            style = Stroke(width = RING_WIDTH_PX),
        )
    }

    // Range labels on the lower-right diagonal rather than straight out to the right.
    // Dead right is where a target at one or two o'clock lands, and a range label
    // sitting on top of a target is the one piece of clutter this display cannot
    // afford. Down-right is empty on almost every picture.
    val diagonal = Math.toRadians(LABEL_ANGLE_DEG)
    listOf(0.5f, 1.0f).forEach { fraction ->
        val label = rangeLabel(rangeM * fraction)
        val measured = measurer.measure(label, TextStyle(color = RING_LABEL, fontSize = 9.sp))
        drawText(
            textLayoutResult = measured,
            topLeft = Offset(
                centre.x + (radius * fraction * cos(diagonal)).toFloat()
                    - measured.size.width / 2f,
                centre.y + (radius * fraction * sin(diagonal)).toFloat(),
            ),
        )
    }

    // What the top of the display means. Never omitted: this is the difference
    // between "one o'clock" being a direction and being a guess.
    val heading = measurer.measure(
        if (trackUp) "TRK" else "N",
        TextStyle(color = RING_LABEL, fontSize = 10.sp),
    )
    drawText(
        textLayoutResult = heading,
        topLeft = Offset(centre.x - heading.size.width / 2f, centre.y - radius - heading.size.height),
    )
}

private fun DrawScope.drawWarningSector(centre: Offset, radius: Float, bearingDeg: Double) {
    val sweep = SECTOR_SWEEP_DEG
    rotate(degrees = bearingDeg.toFloat() - sweep / 2f - 90f, pivot = centre) {
        val path = Path().apply {
            moveTo(centre.x, centre.y)
            arcTo(
                rect = Rect(
                    left = centre.x - radius,
                    top = centre.y - radius,
                    right = centre.x + radius,
                    bottom = centre.y + radius,
                ),
                startAngleDegrees = 0f,
                sweepAngleDegrees = sweep,
                forceMoveTo = false,
            )
            close()
        }
        drawPath(path = path, color = SECTOR_COLOUR)
    }
}

/**
 * The target whose range is known and whose bearing is not.
 *
 * A ring, because that is the honest shape of the information. Dashed, so it cannot be
 * mistaken for a range ring.
 */
private fun DrawScope.drawUnknownBearingRing(
    centre: Offset,
    radius: Float,
    targetRangeM: Double,
    rangeM: Double,
) {
    val fraction = (targetRangeM / rangeM).coerceIn(0.05, 1.0).toFloat()
    drawCircle(
        color = UNKNOWN_BEARING_COLOUR,
        radius = radius * fraction,
        center = centre,
        style = Stroke(
            width = UNKNOWN_RING_WIDTH_PX,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 10f)),
        ),
    )
}

private fun DrawScope.drawTarget(
    fix: RadarFix,
    centre: Offset,
    radius: Float,
    rangeM: Double,
    measurer: TextMeasurer,
    verticalUnit: String,
    withLabel: Boolean,
    placed: MutableList<Rect>,
) {
    val fraction = (fix.rangeM / rangeM).coerceIn(0.0, 1.0).toFloat()
    val angle = Math.toRadians(fix.screenBearingDeg - 90.0)
    val at = Offset(
        centre.x + (radius * fraction * cos(angle)).toFloat(),
        centre.y + (radius * fraction * sin(angle)).toFloat(),
    )

    val colour = fix.target.colour?.let { value -> Color(value) } ?: CockpitColors.Muted

    drawCircle(color = colour, radius = TARGET_RADIUS_PX, center = at)
    drawCircle(
        color = CockpitColors.Background,
        radius = TARGET_RADIUS_PX,
        center = at,
        style = Stroke(width = 2f),
    )

    // A tick along the target's track, so a converging one can be told from a
    // departing one without waiting for the next frame. Already in the display's
    // frame, so it turns with the aircraft the way the bearings do.
    fix.screenTrackDeg?.let { track ->
        val relative = Math.toRadians(track - 90.0)
        drawLine(
            color = colour,
            start = at,
            end = Offset(
                at.x + (TRACK_TICK_PX * cos(relative)).toFloat(),
                at.y + (TRACK_TICK_PX * sin(relative)).toFloat(),
            ),
            strokeWidth = 3f,
        )
    }

    if (!withLabel) {
        return
    }
    relativeAltitudeLabel(fix.target.verticalDistanceM, verticalUnit)?.let { label ->
        val measured = measurer.measure(label, TextStyle(color = colour, fontSize = 10.sp))
        // On whichever side of the dot has room. A target near three o'clock is at
        // the right edge of a round display, and its label ran off the screen.
        val toTheRight = at.x + TARGET_RADIUS_PX + 3f
        val topLeft = if (toTheRight + measured.size.width <= size.width) {
            Offset(toTheRight, at.y - measured.size.height / 2f)
        } else {
            Offset(
                (at.x - TARGET_RADIUS_PX - 3f - measured.size.width).coerceAtLeast(0f),
                at.y - measured.size.height / 2f,
            )
        }
        val box = Rect(
            left = topLeft.x,
            top = topLeft.y,
            right = topLeft.x + measured.size.width,
            bottom = topLeft.y + measured.size.height,
        )
        if (placed.any { other -> other.overlaps(box) }) {
            return
        }
        placed.add(box)
        drawText(textLayoutResult = measured, topLeft = topLeft)
    }
}

private fun DrawScope.drawOwnShip(centre: Offset) {
    val path = Path().apply {
        moveTo(centre.x, centre.y - OWNSHIP_PX)
        lineTo(centre.x - OWNSHIP_PX * 0.7f, centre.y + OWNSHIP_PX * 0.8f)
        lineTo(centre.x, centre.y + OWNSHIP_PX * 0.35f)
        lineTo(centre.x + OWNSHIP_PX * 0.7f, centre.y + OWNSHIP_PX * 0.8f)
        close()
    }
    drawPath(path = path, color = CockpitColors.OnBackground)
}

private fun rangeLabel(metres: Double): String =
    if (metres >= 1000.0) {
        val kilometres = metres / 1000.0
        if (kilometres >= 10.0) {
            kilometres.roundToInt().toString() + " km"
        } else {
            String.format("%.1f km", kilometres)
        }
    } else {
        metres.roundToInt().toString() + " m"
    }

private val RING_COLOUR = Color(0x40FFFFFF)
private val RING_LABEL = Color(0x99FFFFFF)
private val SECTOR_COLOUR = Color(0x33FF5252)
private val UNKNOWN_BEARING_COLOUR = Color(0x80FFB300)

private const val RING_FRACTION = 0.82f
private const val RING_WIDTH_PX = 1.5f
private const val UNKNOWN_RING_WIDTH_PX = 2f
private const val TARGET_RADIUS_PX = 7f
private const val TRACK_TICK_PX = 14f
private const val OWNSHIP_PX = 11f
// Degrees below the horizontal, clockwise, for the range labels.
private const val LABEL_ANGLE_DEG = 35.0
private const val SECTOR_SWEEP_DEG = 40f
