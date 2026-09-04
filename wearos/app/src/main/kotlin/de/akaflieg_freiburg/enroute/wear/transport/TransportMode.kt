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

package de.akaflieg_freiburg.enroute.wear.transport

/** Which link to the phone the pilot wants. */
enum class TransportMode(val id: String, val label: String) {
    /**
     * Wi-Fi when it works, Bluetooth when it does not.
     *
     * Alternating rather than deciding once: the right answer changes when the pilot
     * walks out of the door, and a setting they have to remember to change is a
     * setting that will be wrong exactly when it matters.
     */
    Automatic("auto", "Automatic"),

    /** Wi-Fi only. Faster, and needs both devices on one network. */
    WiFi("wifi", "Wi-Fi"),

    /** Bluetooth only. Slower, and needs no network at all. */
    Bluetooth("ble", "Bluetooth"),
    ;

    /**
     * Whether attempt number [attempt] should go over Bluetooth.
     *
     * Zero-based, and Wi-Fi takes the even attempts so that a watch on the same network
     * as the phone connects over the faster link on its first try rather than after a
     * Bluetooth timeout. Pure arithmetic on purpose: an off-by-one here would show up
     * as "Bluetooth never gets tried", which looks like a radio fault.
     */
    fun usesBluetooth(attempt: Int): Boolean = when (this) {
        WiFi -> false
        Bluetooth -> true
        Automatic -> attempt % 2 != 0
    }

    companion object {
        fun byId(id: String?): TransportMode =
            entries.firstOrNull { mode -> mode.id == id } ?: Automatic
    }
}
