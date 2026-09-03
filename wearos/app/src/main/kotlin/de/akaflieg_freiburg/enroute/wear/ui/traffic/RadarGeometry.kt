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

import de.akaflieg_freiburg.enroute.wear.domain.GeoPoint
import de.akaflieg_freiburg.enroute.wear.domain.TrafficTarget
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The arithmetic behind the radar view, kept out of the drawing code so it can be
 * tested.
 *
 * None of this is a judgement about traffic. It is bearing, range and rounding: the
 * relevance, the alarm level and the colour all arrive from the phone.
 */

/** Where a target sits on the display, once own track has been taken out. */
data class RadarFix(
    val target: TrafficTarget,
    /** Degrees clockwise from the top of the screen. */
    val screenBearingDeg: Double,
    /**
     * The target's own track, in the same frame.
     *
     * Rotated here rather than at the drawing, because this is the only place that
     * knows what the display was rotated by. Null when the receiver reports no track.
     */
    val screenTrackDeg: Double?,
    val rangeM: Double,
)

/**
 * True bearing from one point to another, in degrees.
 *
 * The spherical formula rather than the flat one: at the ranges a traffic display
 * covers the two agree, but this one also behaves at high latitude and across the
 * date line, and it costs two trigonometric calls a frame.
 */
fun bearingDeg(from: GeoPoint, to: GeoPoint): Double {
    val fromLat = Math.toRadians(from.latDeg)
    val toLat = Math.toRadians(to.latDeg)
    val deltaLon = Math.toRadians(to.lonDeg - from.lonDeg)
    val y = sin(deltaLon) * cos(toLat)
    val x = cos(fromLat) * sin(toLat) - sin(fromLat) * cos(toLat) * cos(deltaLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

/** Great-circle distance in metres. Same reasoning as [bearingDeg]. */
fun distanceM(from: GeoPoint, to: GeoPoint): Double {
    val fromLat = Math.toRadians(from.latDeg)
    val toLat = Math.toRadians(to.latDeg)
    val deltaLat = toLat - fromLat
    val deltaLon = Math.toRadians(to.lonDeg - from.lonDeg)
    val a = sin(deltaLat / 2) * sin(deltaLat / 2) +
        cos(fromLat) * cos(toLat) * sin(deltaLon / 2) * sin(deltaLon / 2)
    return 2 * EARTH_RADIUS_M * atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
}

/**
 * Places the targets on the display.
 *
 * With a known own track the display is track-up, which is what every traffic
 * instrument does and what makes "one o'clock" mean anything. Without one it is
 * north-up, and the caller has to say so on screen: a track-up display that is
 * quietly north-up points a pilot at the wrong piece of sky.
 *
 * A target's own reported range is preferred over the computed one. It is what the
 * receiver measured; the coordinates are extrapolated, and the two can disagree by a
 * little.
 */
fun radarFixes(
    targets: List<TrafficTarget>,
    own: GeoPoint?,
    ownTrackDeg: Double?,
): List<RadarFix> {
    if (own == null) {
        return emptyList()
    }
    val rotation = ownTrackDeg ?: 0.0
    return targets.mapNotNull { target ->
        val point = target.point ?: return@mapNotNull null
        val bearing = bearingDeg(own, point)
        RadarFix(
            target = target,
            screenBearingDeg = (bearing - rotation + 360.0) % 360.0,
            screenTrackDeg = target.trackDeg?.let { track -> (track - rotation + 360.0) % 360.0 },
            rangeM = target.horizontalDistanceM ?: distanceM(own, point),
        )
    }
}

/**
 * The outer ring's range, in metres.
 *
 * Chosen from a fixed ladder rather than fitted to the farthest target, so the rings
 * mean the same thing from one second to the next. A display whose scale slides around
 * cannot be read at a glance, which is the only way it is ever read.
 *
 * The smallest step is 1 km: closer than that, a target is not a dot on a display any
 * more, it is something to look out of the window for.
 */
fun radarRangeM(fixes: List<RadarFix>): Double {
    // Driven by the targets the phone calls relevant, when it calls any of them that.
    // A contact twenty kilometres away would otherwise push the scale out until the
    // one converging with the aircraft is a dot on the centre spot -- the display
    // would be technically complete and practically useless.
    val considered = fixes.filter { fix -> fix.target.relevant }.ifEmpty { fixes }
    val farthest = considered.maxOfOrNull { fix -> fix.rangeM } ?: 0.0
    return RANGE_LADDER_M.firstOrNull { step -> farthest <= step } ?: RANGE_LADDER_M.last()
}

/**
 * The clock position a bearing corresponds to, 1 to 12.
 *
 * Dead ahead is twelve, not zero: this is read aloud, and "traffic at zero o'clock" is
 * not something anyone says.
 */
fun clockPosition(screenBearingDeg: Double): Int {
    val normalised = ((screenBearingDeg % 360.0) + 360.0) % 360.0
    val hour = (normalised / 30.0).roundToInt() % 12
    return if (hour == 0) 12 else hour
}

/**
 * The height difference as a traffic display writes it, in the pilot's own unit.
 *
 * Rounded to the nearest hundred feet or fifty metres, which is the resolution these
 * instruments show and roughly the resolution the data deserves. Anything within one
 * step of zero reads as level, because "+0 ft" invites a precision that is not there.
 */
fun relativeAltitudeLabel(verticalM: Double?, unit: String): String? {
    if (verticalM == null || !verticalM.isFinite()) {
        return null
    }
    return if (unit == "m") {
        val rounded = (verticalM / 50.0).roundToInt() * 50
        if (abs(rounded) < 50) "0 m" else signed(rounded) + " m"
    } else {
        val feet = verticalM / METRES_PER_FOOT
        val rounded = (feet / 100.0).roundToInt() * 100
        if (abs(rounded) < 100) "0 ft" else signed(rounded) + " ft"
    }
}

private fun signed(value: Int): String = if (value > 0) "+$value" else value.toString()

private val RANGE_LADDER_M = listOf(1_000.0, 2_000.0, 5_000.0, 10_000.0, 20_000.0, 50_000.0)

private const val EARTH_RADIUS_M = 6_371_000.0
private const val METRES_PER_FOOT = 0.3048

/**
 * The target a warning is about: the most alarming, and among equals the nearest.
 *
 * Both the sector on the radar and the "one o'clock" in the banner come from this, so
 * that they cannot point in different directions. Ranking on level alone was not
 * enough -- a receiver reporting three targets at the same level would have the two
 * disagree depending on list order.
 */
fun mostAlarming(fixes: List<RadarFix>): RadarFix? = fixes
    .filter { fix -> fix.target.alarmLevel > 0 }
    .minWithOrNull(
        compareByDescending<RadarFix> { fix -> fix.target.alarmLevel }
            .thenBy { fix -> fix.rangeM },
    )
