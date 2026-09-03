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

import de.akaflieg_freiburg.enroute.wear.ui.BezelAction
import de.akaflieg_freiburg.enroute.wear.ui.ChartMode
import de.akaflieg_freiburg.enroute.wear.ui.WearPage
import de.akaflieg_freiburg.enroute.wear.ui.movePage
import de.akaflieg_freiburg.enroute.wear.ui.visiblePages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page layout, which is the one piece of this app that can lock a pilot out.
 *
 * Every case here is a way a stored preference and the code can disagree, and each one
 * would show up as a black screen or a missing page rather than as an exception.
 */
class PageLayoutTest {

    @Test
    fun `no stored preference gives the default arrangement`() {
        val pages = visiblePages(emptyList(), emptySet())
        assertEquals(WearPage.entries.toList(), pages)
    }

    @Test
    fun `a stored order is honoured`() {
        val pages = visiblePages(listOf("notam", "data", "map"), emptySet())
        assertEquals(
            listOf(WearPage.Notam, WearPage.Data, WearPage.Map),
            pages.take(3),
        )
    }

    @Test
    fun `a page the stored order never mentions still appears`() {
        // The case that matters when a new version adds a page: without this it would
        // be invisible until the pilot reset their settings, and they would have no
        // reason to suspect it existed.
        val pages = visiblePages(listOf("map", "data"), emptySet())
        assertTrue(WearPage.Weather in pages)
        assertTrue(WearPage.Log in pages)
    }

    @Test
    fun `an identifier the code no longer knows is dropped`() {
        val pages = visiblePages(listOf("map", "traffic-from-a-later-version", "data"), emptySet())
        assertEquals(listOf(WearPage.Map, WearPage.Data), pages.take(2))
        assertEquals(WearPage.entries.size, pages.size)
    }

    @Test
    fun `a duplicate in the stored order is kept once`() {
        val pages = visiblePages(listOf("map", "map", "data"), emptySet())
        assertEquals(pages.size, pages.distinct().size)
    }

    @Test
    fun `hidden pages are left out`() {
        val pages = visiblePages(emptyList(), setOf("notam", "weather"))
        assertTrue(WearPage.Notam !in pages)
        assertTrue(WearPage.Weather !in pages)
        assertTrue(WearPage.Data in pages)
    }

    @Test
    fun `settings is always present and always last`() {
        // The property the whole design rests on: hiding it, or ordering it away from
        // the end, would strand a pilot who hid everything else.
        val everythingHidden = WearPage.entries.map { page -> page.id }.toSet()
        val pages = visiblePages(listOf("settings", "map"), everythingHidden)
        assertEquals(listOf(WearPage.Settings), pages)
    }

    @Test
    fun `settings stays last even when the stored order puts it first`() {
        val pages = visiblePages(listOf("settings", "data", "map"), emptySet())
        assertEquals(WearPage.Settings, pages.last())
    }

    @Test
    fun `moving a page down swaps it with its neighbour`() {
        val pages = visiblePages(emptyList(), emptySet())
        val moved = movePage(pages, WearPage.Data, 1)
        assertEquals(listOf("map", "data", "notam", "weather", "log", "settings"), moved)
    }

    @Test
    fun `moving off either end changes nothing`() {
        val pages = visiblePages(emptyList(), emptySet())
        val ids = pages.map { page -> page.id }
        assertEquals(ids, movePage(pages, WearPage.Data, -1))
        assertEquals(ids, movePage(pages, WearPage.Log, 1))
    }

    @Test
    fun `settings cannot be moved`() {
        val pages = visiblePages(emptyList(), emptySet())
        val ids = pages.map { page -> page.id }
        assertEquals(ids, movePage(pages, WearPage.Settings, -1))
    }

    @Test
    fun `a move survives a round trip through visiblePages`() {
        val pages = visiblePages(emptyList(), emptySet())
        val stored = movePage(pages, WearPage.Weather, -1)
        val again = visiblePages(stored, emptySet())
        assertEquals(
            listOf(WearPage.Data, WearPage.Map, WearPage.Weather, WearPage.Notam),
            again.take(4),
        )
    }

    @Test
    fun `an unknown stored choice falls back rather than throwing`() {
        assertEquals(BezelAction.Pages, BezelAction.byId(null))
        assertEquals(BezelAction.Pages, BezelAction.byId("from-a-later-version"))
        assertEquals(BezelAction.Content, BezelAction.byId("content"))
        assertEquals(ChartMode.Automatic, ChartMode.byId(null))
        assertEquals(ChartMode.Off, ChartMode.byId("off"))
    }
}
