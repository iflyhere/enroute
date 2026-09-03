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

import de.akaflieg_freiburg.enroute.wear.ui.route.circleMeetsDisc
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The visibility test for a NOTAM area outline.
 *
 * Small enough to look correct and wrong often enough to be worth pinning down: the case
 * that catches people out is a circle so large that the whole screen sits inside it, which
 * has no visible outline and must not be drawn.
 */
class NotamAreaCullingTest {

    private val disc = 200f

    @Test
    fun `a circle around the centre and the size of the disc is visible`() {
        assertTrue(circleMeetsDisc(distanceFromCentre = 0f, radiusPx = 200f, discRadiusPx = disc))
    }

    @Test
    fun `a small circle at the centre is visible`() {
        assertTrue(circleMeetsDisc(distanceFromCentre = 0f, radiusPx = 10f, discRadiusPx = disc))
    }

    @Test
    fun `a circle whose edge just reaches the disc is visible`() {
        assertTrue(circleMeetsDisc(distanceFromCentre = 500f, radiusPx = 300f, discRadiusPx = disc))
    }

    @Test
    fun `a circle entirely outside the view is culled`() {
        assertFalse(circleMeetsDisc(distanceFromCentre = 900f, radiusPx = 100f, discRadiusPx = disc))
    }

    @Test
    fun `a circle that swallows the whole view is culled`() {
        // The pilot is inside a 20 NM area seen at a 2 NM zoom. Its outline is nowhere
        // near the screen, so drawing it renders nothing at all.
        assertFalse(circleMeetsDisc(distanceFromCentre = 0f, radiusPx = 4000f, discRadiusPx = disc))
    }

    @Test
    fun `an off-centre circle that still swallows the view is culled`() {
        assertFalse(circleMeetsDisc(distanceFromCentre = 300f, radiusPx = 4000f, discRadiusPx = disc))
    }
}
