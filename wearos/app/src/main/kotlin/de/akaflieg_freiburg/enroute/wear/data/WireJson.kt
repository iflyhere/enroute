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

package de.akaflieg_freiburg.enroute.wear.data

import kotlinx.serialization.json.Json

object WireJson {

    /** Protocol version this build speaks. See doc/companion-protocol.md. */
    const val PROTOCOL_VERSION = 1

    val json: Json = Json {
        // A newer phone may add keys. Ignoring them is the forward-compatibility
        // contract, and without it an app update on the phone would break the watch.
        ignoreUnknownKeys = true

        // The phone omits keys whose value is unknown rather than sending null, so
        // absent and null must both mean "not known".
        explicitNulls = false

        isLenient = false
        encodeDefaults = false
    }
}
