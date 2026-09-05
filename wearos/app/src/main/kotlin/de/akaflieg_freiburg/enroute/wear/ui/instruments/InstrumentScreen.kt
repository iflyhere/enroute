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

package de.akaflieg_freiburg.enroute.wear.ui.instruments

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text
import de.akaflieg_freiburg.enroute.wear.domain.NavFrame
import de.akaflieg_freiburg.enroute.wear.ui.theme.CockpitColors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The three round instruments, one at a time, on the whole face.
 *
 * One at a time because a 454 pixel disc holds exactly one dial that can be read
 * without looking twice, and reading without looking twice is the only reason to draw a
 * dial rather than a number. A tap moves to the next one; the other two stay on screen
 * as digits, so nothing is hidden behind the tap.
 *
 * **What these instruments are fed matters more than how they look.** The phone has one
 * position source and no pitot tube, so:
 *
 *  - the speed dial is **ground speed**, not indicated airspeed, and is labelled GS.
 *    An airspeed dial fed by GPS would read the tailwind as performance, and on final
 *    that is exactly the wrong lie;
 *  - the variometer is the vertical component of successive GPS fixes. It lags a real
 *    variometer by seconds and has none of its total-energy compensation. It is good
 *    for "am I going up" and not for centring a thermal;
 *  - the altimeter shows the phone's altitude above mean sea level, which is
 *    geometric. When the phone can offer a pressure altitude the flight level is shown
 *    beside it, and the two are labelled apart rather than blended.
 *
 * None of that makes the instruments useless. It makes them instruments whose source is
 * stated, which is the difference between a display and a decoration.
 */
@Composable
fun InstrumentScreen(
    frame: NavFrame?,
    showing: Instrument,
    verticalUnit: String,
    horizontalUnit: String,
    onCycle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val position = frame?.position

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CockpitColors.Background)
            .pointerInput(Unit) { detectTapGestures { onCycle() } },
        contentAlignment = Alignment.Center,
    ) {
        if (position == null || !position.hasFix) {
            Text(
                text = "No position\nfrom the phone",
                color = CockpitColors.Muted,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            return@Box
        }

        val speedUnit = speedUnitFor(horizontalUnit)

        Canvas(modifier = Modifier.fillMaxSize()) {
            val centre = Offset(size.width / 2f, size.height / 2f)
            val radius = minOf(size.width, size.height) / 2f * DIAL_FRACTION

            when (showing) {
                Instrument.Altimeter -> drawAltimeter(
                    centre, radius, measurer,
                    altitudeM = position.altitudeAmsl.si,
                    unit = verticalUnit,
                )

                Instrument.Speed -> drawSpeed(
                    centre, radius, measurer,
                    speedMps = position.groundSpeed.si,
                    unit = speedUnit,
                )

                Instrument.Variometer -> drawVariometer(
                    centre, radius, measurer,
                    verticalSpeedMps = position.verticalSpeedMps,
                    unit = verticalUnit,
                )
            }

            // Below the reading, not beside it. Drawn here rather than as a Compose
            // overlay because the overlay landed just above the centre, where the
            // needle passes through it -- and a needle crossing the words that say
            // what the needle means is the one place this label must not be.
            centreText(
                centre, measurer, showing.title + subtitle(showing),
                10.sp.value, TICK_LABEL, offsetY = radius * 0.45f,
            )

            // The other two instruments as digits, in the phone's own words, inside the
            // dial. Outside it there is nothing left: the face now runs to the edge of a
            // round display, and text under the bottom ticks would be under the bezel.
            centreText(
                centre, measurer, digitalSummary(frame, showing, verticalUnit),
                11.sp.value, CockpitColors.Primary, offsetY = radius * 0.62f,
            )
        }

    }
}

/** What the dial is fed, said in three words, because it is not what the dial's name suggests. */
private fun subtitle(showing: Instrument): String = when (showing) {
    Instrument.Altimeter -> "  GPS, AMSL"
    Instrument.Speed -> "  ground speed"
    Instrument.Variometer -> "  from GPS"
}

/**
 * The speed unit that goes with a distance preference.
 *
 * The same mapping the app makes in `Aircraft::horizontalSpeedToString`: the pilot
 * chooses a distance unit and the speed follows it.
 */
fun speedUnitFor(horizontalUnit: String): String = when (horizontalUnit) {
    "km" -> "kmh"
    "mil" -> "mph"
    else -> "kn"
}

private fun digitalSummary(frame: NavFrame, showing: Instrument, verticalUnit: String): String {
    val position = frame.position ?: return ""
    val parts = mutableListOf<String>()
    if (showing != Instrument.Altimeter) {
        parts += position.altitudeAmsl.text
    }
    if (showing != Instrument.Speed) {
        parts += position.groundSpeed.text
    }
    if (showing != Instrument.Variometer) {
        position.verticalSpeedMps?.let { value ->
            parts += verticalSpeedText(value, verticalUnit)
        }
    }
    // The flight level, when the phone has a pressure altitude to offer one from.
    // Never blended into the altimeter's own reading: one is geometric and the other
    // barometric, and a display that mixed them would be wrong in a way nothing on
    // screen could reveal.
    frame.flightLevel.text
        .takeIf { text -> text.isNotBlank() && text != "-" && !frame.flightLevelImplausible }
        ?.let { text -> parts += text }
    return parts.joinToString("   ")
}

private fun verticalSpeedText(mps: Double, unit: String): String {
    val rounded = roundedVerticalSpeed(if (unit == "m") mps else mps / METRES_PER_FOOT * 60.0)
    val suffix = if (unit == "m") " m/s" else " fpm"
    val shown = if (unit == "m") rounded else rounded.roundToInt().toDouble()
    val sign = if (shown > 0) "+" else ""
    return sign + (if (unit == "m") shown.toString() else shown.roundToInt().toString()) + suffix
}

// ------------------------------------------------------------------ the dials

private fun DrawScope.drawAltimeter(
    centre: Offset,
    radius: Float,
    measurer: TextMeasurer,
    altitudeM: Double?,
    unit: String,
) {
    drawFace(centre, radius)

    // Ten major divisions, one per hundred of the pilot's unit, zero at twelve o'clock:
    // the layout of every altimeter ever built.
    for (index in 0 until 10) {
        val angle = index * 36.0
        tick(centre, radius, angle, long = true)
        label(centre, radius * LABEL_FRACTION, angle, index.toString(), measurer, 14.sp.value)
    }
    for (index in 0 until 50) {
        if (index % 5 != 0) {
            tick(centre, radius, index * 7.2, long = false)
        }
    }

    if (!isUsable(altitudeM)) {
        centreText(centre, measurer, "no altitude", 12.sp.value, CockpitColors.Muted)
        return
    }
    val value = if (unit == "m") altitudeM!! else altitudeM!! / METRES_PER_FOOT

    centreText(
        centre,
        measurer,
        altitudeDigits(value, unit).toString() + " " + (if (unit == "m") "m" else "ft"),
        20.sp.value,
        CockpitColors.OnBackground,
        offsetY = radius * 0.30f,
    )

    // The short hand first, so the long one lies over it where they cross. Thousands
    // below hundreds is the order a pilot reads them in and the order they are drawn.
    needle(
        centre, radius * 0.58f, dialAngleDeg(value, 10_000.0),
        CockpitColors.OnBackground, halfWidth = NEEDLE_HALF_WIDTH_PX * 1.9f,
    )
    needle(centre, radius * 0.94f, dialAngleDeg(value, 1_000.0), CockpitColors.OnBackground)
}

private fun DrawScope.drawSpeed(
    centre: Offset,
    radius: Float,
    measurer: TextMeasurer,
    speedMps: Double?,
    unit: String,
) {
    drawFace(centre, radius)

    val shown = when {
        !isUsable(speedMps) -> 0.0
        unit == "kmh" -> speedMps!! * 3.6
        unit == "mph" -> speedMps!! / METRES_PER_MILE * 3600.0
        else -> speedMps!! / METRES_PER_NM * 3600.0
    }
    val fullScale = speedFullScale(shown, unit)

    // Zero at twelve o'clock, sweeping clockwise nearly the whole way round: the layout
    // of an airspeed indicator. The gap is at the top, where the scale closes on itself,
    // not at the bottom -- a gap at the bottom belongs to a rev counter.
    val step = speedTickStep(fullScale)
    val divisions = (fullScale / step).roundToInt().coerceAtLeast(1)
    for (index in 0..divisions) {
        val angle = SPEED_SWEEP_DEG * index / divisions
        tick(centre, radius, angle, long = true)
        label(
            centre, radius * LABEL_FRACTION, angle,
            (step * index).roundToInt().toString(), measurer, 12.sp.value,
        )
    }
    for (index in 0 until divisions * 2) {
        tick(centre, radius, SPEED_SWEEP_DEG * (index + 0.5) / (divisions * 2), long = false)
    }

    if (!isUsable(speedMps)) {
        centreText(centre, measurer, "no speed", 12.sp.value, CockpitColors.Muted)
        return
    }
    centreText(
        centre, measurer,
        shown.roundToInt().toString() + " " + speedUnitLabel(unit),
        20.sp.value, CockpitColors.OnBackground, offsetY = radius * 0.30f,
    )
    needle(
        centre, radius * 0.94f,
        spanAngleDeg(shown, 0.0, fullScale, 0.0, SPEED_SWEEP_DEG),
        CockpitColors.OnBackground,
    )
}

private fun DrawScope.drawVariometer(
    centre: Offset,
    radius: Float,
    measurer: TextMeasurer,
    verticalSpeedMps: Double?,
    unit: String,
) {
    drawFace(centre, radius)

    val fullScale = if (unit == "m") VARIO_FULL_SCALE_MPS else VARIO_FULL_SCALE_FPM
    val shown = when {
        !isUsable(verticalSpeedMps) -> 0.0
        unit == "m" -> verticalSpeedMps!!
        else -> verticalSpeedMps!! / METRES_PER_FOOT * 60.0
    }

    // Zero at nine o'clock, climb clockwise over the top, sink counter-clockwise under
    // the bottom, both reaching full scale at three o'clock. One expression covers both
    // halves, which is what makes the needle behave the way the instrument does.
    val divisions = if (unit == "m") VARIO_DIVISIONS_MPS else VARIO_DIVISIONS_FPM
    for (half in listOf(1, -1)) {
        for (index in 0..divisions) {
            val value = fullScale * index / divisions
            tick(centre, radius, varioAngleDeg(half * value, fullScale), long = index % 1 == 0)
            if (index > 0 || half > 0) {
                label(
                    centre, radius * LABEL_FRACTION, varioAngleDeg(half * value, fullScale),
                    varioLabel(value, unit), measurer, 13.sp.value,
                )
            }
        }
    }
    for (half in listOf(1, -1)) {
        for (index in 0 until divisions) {
            val value = fullScale * (index + 0.5) / divisions
            tick(centre, radius, varioAngleDeg(half * value, fullScale), long = false)
        }
    }

    if (!isUsable(verticalSpeedMps)) {
        centreText(centre, measurer, "no vertical speed", 11.sp.value, CockpitColors.Muted)
        return
    }
    centreText(
        centre, measurer, verticalSpeedText(verticalSpeedMps!!, unit),
        18.sp.value, CockpitColors.OnBackground, offsetY = radius * 0.30f,
    )
    val colour = when {
        abs(shown) < fullScale * 0.03 -> CockpitColors.OnBackground
        shown > 0 -> CockpitColors.Good
        else -> CockpitColors.Caution
    }
    needle(centre, radius * 0.94f, varioAngleDeg(shown, fullScale), colour)
}

/**
 * Where a vertical speed sits on a dial with zero at nine o'clock.
 *
 * Clockwise from there for climb and counter-clockwise for sink, so both ends of the
 * scale arrive at three o'clock. Written as one expression rather than two branches
 * because it is one: the sign of the value carries the direction.
 */
fun varioAngleDeg(value: Double, fullScale: Double): Double {
    if (fullScale <= 0.0) {
        return VARIO_ZERO_DEG
    }
    val fraction = (value / fullScale).coerceIn(-1.0, 1.0)
    return VARIO_ZERO_DEG + fraction * 180.0
}

/** What a vario tick says: metres per second as they are, feet per minute in hundreds. */
private fun varioLabel(value: Double, unit: String): String =
    if (unit == "m") value.roundToInt().toString() else (value / 100.0).roundToInt().toString()

private fun speedUnitLabel(unit: String): String = when (unit) {
    "kmh" -> "km/h"
    "mph" -> "mph"
    else -> "kn"
}

// ------------------------------------------------------------ drawing helpers

private fun DrawScope.drawFace(centre: Offset, radius: Float) {
    drawCircle(color = FACE_COLOUR, radius = radius, center = centre, style = Stroke(width = 2f))
}

private fun DrawScope.tick(centre: Offset, radius: Float, angleDeg: Double, long: Boolean) {
    val angle = Math.toRadians(angleDeg - 90.0)
    val inner = radius * if (long) 0.86f else 0.92f
    drawLine(
        color = TICK_COLOUR,
        start = Offset(
            centre.x + (inner * cos(angle)).toFloat(),
            centre.y + (inner * sin(angle)).toFloat(),
        ),
        end = Offset(
            centre.x + (radius * cos(angle)).toFloat(),
            centre.y + (radius * sin(angle)).toFloat(),
        ),
        strokeWidth = if (long) 3f else 1.5f,
    )
}

private fun DrawScope.label(
    centre: Offset,
    radius: Float,
    angleDeg: Double,
    text: String,
    measurer: TextMeasurer,
    fontSizeSp: Float,
) {
    val angle = Math.toRadians(angleDeg - 90.0)
    val measured = measurer.measure(text, TextStyle(color = TICK_LABEL, fontSize = fontSizeSp.sp))
    drawText(
        textLayoutResult = measured,
        topLeft = Offset(
            centre.x + (radius * cos(angle)).toFloat() - measured.size.width / 2f,
            centre.y + (radius * sin(angle)).toFloat() - measured.size.height / 2f,
        ),
    )
}

private fun DrawScope.centreText(
    centre: Offset,
    measurer: TextMeasurer,
    text: String,
    fontSizeSp: Float,
    colour: Color,
    offsetY: Float = 0f,
) {
    val measured = measurer.measure(
        text,
        TextStyle(color = colour, fontSize = fontSizeSp.sp, fontWeight = FontWeight.Medium),
    )
    drawText(
        textLayoutResult = measured,
        topLeft = Offset(
            centre.x - measured.size.width / 2f,
            centre.y + offsetY - measured.size.height / 2f,
        ),
    )
}

private fun DrawScope.needle(
    centre: Offset,
    length: Float,
    angleDeg: Double,
    colour: Color,
    halfWidth: Float = NEEDLE_HALF_WIDTH_PX,
) {
    val angle = Math.toRadians(angleDeg - 90.0)
    val tip = Offset(
        centre.x + (length * cos(angle)).toFloat(),
        centre.y + (length * sin(angle)).toFloat(),
    )
    val perpendicular = Math.toRadians(angleDeg)
    val half = halfWidth
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(
            centre.x + (half * cos(perpendicular)).toFloat(),
            centre.y + (half * sin(perpendicular)).toFloat(),
        )
        lineTo(
            centre.x - (half * cos(perpendicular)).toFloat(),
            centre.y - (half * sin(perpendicular)).toFloat(),
        )
        close()
    }
    drawPath(path = path, color = colour)
    drawCircle(color = colour, radius = half * 1.6f, center = centre)
}

private val FACE_COLOUR = Color(0x59FFFFFF)
private val TICK_COLOUR = Color(0xB3FFFFFF)
private val TICK_LABEL = Color(0xCCFFFFFF)

// To the edge. The display is round and so is the instrument, so a margin between them
// buys nothing and costs the only thing a dial has: the length of its scale.
private const val DIAL_FRACTION = 0.985f

// Numbers sit inside the ticks, far enough in that the round bezel cannot clip them.
private const val LABEL_FRACTION = 0.80f

private const val NEEDLE_HALF_WIDTH_PX = 5f

// An airspeed indicator: zero at twelve o'clock, clockwise, closing on itself at the top.
private const val SPEED_SWEEP_DEG = 330.0

// A vertical speed indicator: zero at nine o'clock.
private const val VARIO_ZERO_DEG = 270.0

// Five metres a second, or two thousand feet a minute marked in hundreds -- which is
// what the instrument this is modelled on is marked in.
private const val VARIO_FULL_SCALE_MPS = 5.0
private const val VARIO_FULL_SCALE_FPM = 2_000.0
private const val VARIO_DIVISIONS_MPS = 5
private const val VARIO_DIVISIONS_FPM = 4

private const val METRES_PER_FOOT = 0.3048
private const val METRES_PER_NM = 1852.0
private const val METRES_PER_MILE = 1609.344
