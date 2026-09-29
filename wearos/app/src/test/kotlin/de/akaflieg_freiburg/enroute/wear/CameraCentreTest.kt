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

package de.akaflieg_freiburg.enroute.wear

import de.akaflieg_freiburg.enroute.wear.domain.GeoPoint
import de.akaflieg_freiburg.enroute.wear.ui.map.cameraCentre
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where the map is centred, which decides whether it follows the aircraft.
 *
 * The case this exists for was reported after a real flight: the phone lost its fix,
 * the frame stopped carrying a position, and the map snapped back to the departure
 * aerodrome and stayed there for the rest of the flight.
 */
class CameraCentreTest {

    private val aircraft = GeoPoint(48.49, 9.14)
    private val lastSeen = GeoPoint(48.55, 9.17)
    private val departure = GeoPoint(48.68988, 9.22196)
    private val mapCentre = GeoPoint(51.0, 10.0)

    @Test
    fun `the aircraft wins whenever there is a fix`() {
        assertEquals(aircraft, cameraCentre(aircraft, lastSeen, departure, mapCentre))
    }

    @Test
    fun `a lost fix leaves the map where the aircraft was, not at the departure`() {
        // The regression. Before, this returned the departure: fly away from the
        // aerodrome, lose the fix for twenty seconds, and the map went home.
        assertEquals(lastSeen, cameraCentre(null, lastSeen, departure, mapCentre))
    }

    @Test
    fun `before any fix the route decides`() {
        // On the ground, before the phone has seen a satellite, the departure is the
        // best guess of where the pilot is -- and it is where they are.
        assertEquals(departure, cameraCentre(null, null, departure, mapCentre))
    }

    @Test
    fun `with no route and no fix the phone's own maps decide`() {
        assertEquals(mapCentre, cameraCentre(null, null, null, mapCentre))
    }

    @Test
    fun `with nothing at all there is nothing to centre on`() {
        assertNull(cameraCentre(null, null, null, null))
    }
}
