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

import de.akaflieg_freiburg.enroute.wear.data.parseStyleColour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The colour parser exists to stop one specific bug coming back: white text on a white
 * map. If it silently failed, the caller would keep its cockpit-palette white and the
 * label would vanish into a daylight base map -- which is how the bug looked the first
 * time. So a value it cannot read must come back as null, never as a guess.
 */
class StyleColourTest {

    @Test
    fun `named colours the style files actually use`() {
        assertEquals(0xFF000000L, parseStyleColour("black"))
        assertEquals(0xFFFFFFFFL, parseStyleColour("white"))
        assertEquals(0xFF0000FFL, parseStyleColour("blue"))
    }

    @Test
    fun `six digit hex, which is what night mode sends`() {
        assertEquals(0xFFE0E0E0L, parseStyleColour("#e0e0e0"))
        assertEquals(0xFF4F7BADL, parseStyleColour("#4f7bad"))
    }

    @Test
    fun `case and surrounding space do not matter`() {
        assertEquals(0xFFE0E0E0L, parseStyleColour("  #E0E0E0 "))
        assertEquals(0xFF000000L, parseStyleColour("BLACK"))
    }

    @Test
    fun `three digit shorthand expands the way CSS does`() {
        assertEquals(0xFFAABBCCL, parseStyleColour("#abc"))
    }

    @Test
    fun `eight digits keep their alpha`() {
        assertEquals(0x80FF0000L, parseStyleColour("#80ff0000"))
    }

    @Test
    fun `anything it cannot read is null, not a guess`() {
        assertNull(parseStyleColour(null))
        assertNull(parseStyleColour(""))
        assertNull(parseStyleColour("   "))
        assertNull(parseStyleColour("rgb(1,2,3)"))
        assertNull(parseStyleColour("#12345"))
        assertNull(parseStyleColour("#gggggg"))
        // A colour name the style files do not use. Adding it would imply this
        // understands CSS, which it does not.
        assertNull(parseStyleColour("rebeccapurple"))
    }
}
