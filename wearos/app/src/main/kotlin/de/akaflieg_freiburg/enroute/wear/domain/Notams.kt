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
 * NOTAMs for the waypoints of the current route, as the phone reports them.
 *
 * Nothing here decides relevance. The phone hands over what its own NOTAM page would
 * show for each waypoint, and this watch shows the same thing in the same order. That
 * is a deliberate limit: inventing a filter of our own would be a safety claim the app
 * itself does not make.
 */
data class NotamBoard(
    val revision: Long,
    val groups: List<NotamGroup>,
    /** The phone's own warning that its NOTAM data is not current, or null. */
    val warning: String?,
    val filter: NotamFilter,
    /** Oldest retrieval among the groups, epoch seconds, or null if nothing was retrieved. */
    val retrievedEpochSeconds: Long?,
    /** NOTAMs the phone could not fit into the document. */
    val dropped: Int,
) {
    val total: Int get() = groups.sumOf { it.notams.size }

    companion object {
        val EMPTY = NotamBoard(
            revision = 0,
            groups = emptyList(),
            warning = null,
            filter = NotamFilter(radiusM = null, horizontalOnly = true, flightLevelApplied = false),
            retrievedEpochSeconds = null,
            dropped = 0,
        )
    }
}

/**
 * The limits of the phone's filter, carried so they can be shown rather than assumed.
 *
 * A pilot reading a NOTAM list on a watch has to be able to tell that this is not an
 * airspace check, and the only honest way to make that possible is to state what the
 * filter did.
 */
data class NotamFilter(
    val radiusM: Double?,
    val horizontalOnly: Boolean,
    val flightLevelApplied: Boolean,
)

/**
 * What is known about one route waypoint.
 *
 * [knowledge] exists so that no composable ever has to work this out from three
 * separate fields. An empty list is not the same as "nothing here", and the difference
 * is the whole reason this type is not just a List.
 */
data class NotamGroup(
    val waypointIndex: Int,
    val name: String,
    val hasData: Boolean,
    val retrievedEpochSeconds: Long?,
    val notams: List<Notam>,
    /** How many NOTAMs the phone dropped from this group because the document was full. */
    val cut: Int,
) {
    val knowledge: NotamKnowledge
        get() = when {
            // First, because incompleteness outranks everything else here. A group that
            // carries three NOTAMs and dropped two is not a listed group; a display
            // that treats it as one tells the pilot they have seen all of them.
            cut > 0 -> NotamKnowledge.Incomplete
            notams.isNotEmpty() -> NotamKnowledge.Listed
            hasData -> NotamKnowledge.ConfirmedNone
            else -> NotamKnowledge.Unknown
        }
}

enum class NotamKnowledge {
    /** NOTAMs are present, and all of the ones the phone has are here. */
    Listed,

    /**
     * Some NOTAMs for this waypoint were not sent. Says nothing about whether any
     * arrived: a group may be incomplete with three entries or with none.
     */
    Incomplete,

    /** The phone retrieved data and there are no NOTAMs for this waypoint. */
    ConfirmedNone,

    /** Nothing has been retrieved. Not the same as nothing being there. */
    Unknown,
}

data class Notam(
    val number: String,
    val icaoLocation: String?,
    val text: String,
    val category: NotamCategory,
    /** The phone's own section heading, e.g. "Current" or "Next 24h". */
    val section: String?,
    val traffic: String?,
    val read: Boolean,
    val fromEpochSeconds: Long?,
    val toEpochSeconds: Long?,
    val area: NotamArea?,
) {
    /** True when the NOTAM has a start but no end: permanent. */
    val isPermanent: Boolean get() = fromEpochSeconds != null && toEpochSeconds == null
}

data class NotamArea(val centre: GeoPoint, val radiusM: Double)

/**
 * The phone's NOTAM category, derived there from the ICAO Q-code's subject letters.
 *
 * These four plus the generic case are the complete set the phone emits. [Other] takes
 * both the generic "NOTAM" and anything a future phone might add, so a new category
 * cannot make an older watch throw.
 *
 * [RestrictedArea] is the one worth singling out on a display: it is the closest thing
 * the app has to an airspace warning, and it is the phone's own classification rather
 * than anything computed here.
 */
enum class NotamCategory {
    Obstacle, ParachuteJumping, UnmannedAircraft, RestrictedArea, Other;

    companion object {
        fun fromWire(code: String?): NotamCategory = when (code) {
            "NOTAM-OBST" -> Obstacle
            "NOTAM-PJE" -> ParachuteJumping
            "NOTAM-UAS" -> UnmannedAircraft
            "NOTAM-RA" -> RestrictedArea
            else -> Other
        }
    }
}
