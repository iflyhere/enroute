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

package de.akaflieg_freiburg.enroute.wear.domain

/**
 * The approach charts the pilot has on the phone, imported or downloaded.
 *
 * [available] is not the same claim as an empty [charts] list. False means the phone
 * could not reach its chart library at all; an empty list means it reached it and there
 * is nothing in it. A pilot who imported a chart and sees nothing needs to be able to
 * tell those apart.
 */
data class VacBoard(
    val revision: Long,
    val available: Boolean,
    val charts: List<ApproachChart>,
) {
    /**
     * The charts covering a position, by the app's own rule.
     *
     * `VACLibrary::vacs4Point()` is plain bounding-box containment, so this is that
     * same test applied to the same numbers rather than a rule of our own. Sorted by
     * name, as the app sorts it, so two devices pick the same chart when several
     * overlap.
     */
    fun coveringSortedByName(point: GeoPoint): List<ApproachChart> =
        charts.filter { chart -> chart.contains(point) }.sortedBy { chart -> chart.name }

    companion object {
        val EMPTY = VacBoard(revision = 0, available = false, charts = emptyList())
    }
}

/**
 * One chart, with the four corners it is drawn on.
 *
 * A manually imported chart is always axis aligned, because its corners come from its
 * file name. A chart from a GeoTIFF can be a true quadrilateral, which is why the four
 * corners travel rather than a rectangle.
 */
data class ApproachChart(
    val name: String,
    val description: String?,
    val section: String?,
    /** Top left, top right, bottom right, bottom left -- the order a quad expects. */
    val quad: List<GeoPoint>,
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
) {
    fun contains(point: GeoPoint): Boolean =
        point.lonDeg >= west && point.lonDeg <= east &&
            point.latDeg >= south && point.latDeg <= north

    /**
     * Where the phone serves the image, relative to the protocol prefix.
     *
     * The name is a path segment and real ones are full of spaces -- "EDDS Stuttgart
     * 3" -- so it is percent encoded here. Pasted into a URL raw it does not merely
     * fail to fetch: java.net.URI rejects it outright, and the renderer's ImageSource
     * takes a URI, so the app went down with a URISyntaxException the first time a
     * pilot flew within range of a chart. It never showed while the chart library was
     * empty, which it is until a trip kit is imported.
     */
    val imagePath: String get() = "/map/vac/" + percentEncodeSegment(name)
}

/**
 * Percent encodes one path segment of a URL.
 *
 * Written out rather than taken from android.net.Uri because this file is domain code
 * with unit tests behind it, and the platform's encoder is a stub that throws when it
 * runs off a device. The unreserved set is the one RFC 3986 names, so a chart called
 * "EDDS Stuttgart 3" becomes "EDDS%20Stuttgart%203" and one called "EDTL" is untouched.
 */
fun percentEncodeSegment(segment: String): String {
    val out = StringBuilder(segment.length)
    for (byte in segment.toByteArray(Charsets.UTF_8)) {
        val char = byte.toInt().toChar()
        if (char.isLetterOrDigit() && char.code < 128 || char in "-._~") {
            out.append(char)
        } else {
            out.append('%').append("%02X".format(byte.toInt() and 0xFF))
        }
    }
    return out.toString()
}
