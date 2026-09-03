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

    /** Where the phone serves the image, relative to the protocol prefix. */
    val imagePath: String get() = "/map/vac/" + name
}
