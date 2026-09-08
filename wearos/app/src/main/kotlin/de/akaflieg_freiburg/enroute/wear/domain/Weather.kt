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
 * METAR and TAF for the stations the phone knows about, nearest first.
 *
 * Every displayed string in here was written by the phone: the summary, the bearing
 * line, the QNH sentence and the sun times. That is the same rule the navigation frame
 * follows, and for the same reason -- those strings carry the pilot's unit preferences
 * and are translated, so a second implementation here would eventually disagree with
 * the phone about the same weather.
 *
 * The raw report travels too, because a pilot reads METAR verbatim and no summary
 * replaces that.
 */
data class WeatherBoard(
    val revision: Long,
    val stations: List<WeatherStation>,
    /** The phone's own QNH sentence, naming the station it came from and its age. */
    val qnh: String?,
    /** The phone's own sunrise and sunset line. */
    val sun: String?,
    /** True while the phone is fetching. Shown so a stale list does not look final. */
    val downloading: Boolean,
) {
    companion object {
        val EMPTY = WeatherBoard(
            revision = 0,
            stations = emptyList(),
            qnh = null,
            sun = null,
            downloading = false,
        )
    }
}

data class WeatherStation(
    val name: String,
    val extendedName: String?,
    val point: GeoPoint?,
    val type: WaypointType,
    /** Distance and bearing as the phone words it, or null while the position is unknown. */
    val way: String?,
    val metar: MetarReport?,
    val taf: TafReport?,
)

/**
 * The five flight categories the app distinguishes.
 *
 * The colour is not derived from this. The phone sends its own colour for the category,
 * because the app maps these five onto its palette and a second mapping here would show
 * a different colour for the same weather.
 */
enum class FlightCategory {
    VFR, MVFR, IFR, LIFR, Unknown;

    companion object {
        fun fromWire(code: String?): FlightCategory = when (code) {
            "VFR" -> VFR
            "MVFR" -> MVFR
            "IFR" -> IFR
            "LIFR" -> LIFR
            else -> Unknown
        }
    }
}

data class MetarReport(
    val raw: String,
    /** The phone's own one-line summary, which already states the observation's age. */
    val summary: String?,
    val category: FlightCategory,
    /** The app's own colour for the category, already parsed, or null if unusable. */
    val colour: Long?,
    /** Observation time, epoch seconds, or null. */
    val observedEpochSeconds: Long?,
)

data class TafReport(
    val raw: String,
    /** Issue time, epoch seconds, or null. */
    val issuedEpochSeconds: Long?,
)
