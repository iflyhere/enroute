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
import de.akaflieg_freiburg.enroute.wear.ui.orderedPages
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
        // The relative order of the pages the pilot placed, which is the actual
        // contract. Their absolute positions can move, because a page added in a
        // later version is inserted among them rather than appended.
        val stored = listOf("notam", "data", "map")
        val pages = visiblePages(stored, emptySet())
        assertEquals(stored, pages.map { page -> page.id }.filter { id -> id in stored })
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
    fun `a page added later lands beside its neighbour, not on the end`() {
        // The order this watch actually had: saved before the traffic page existed.
        // Appending put Traffic after the flight log, where nobody would look for it.
        val stored = listOf("map", "data", "notam", "weather", "log", "settings")
        val pages = orderedPages(stored)
        assertEquals(
            listOf(
                WearPage.Map, WearPage.Data,
                // Instruments and Traffic come before Notam in the enum, so they go
                // in front of it rather than on the end.
                WearPage.Instruments, WearPage.Traffic, WearPage.Notam,
                WearPage.Nearby, WearPage.Weather, WearPage.Log, WearPage.Settings,
            ),
            pages,
        )
    }

    @Test
    fun `a page added before everything stored goes to the front`() {
        val pages = orderedPages(listOf("notam", "weather"))
        assertEquals(WearPage.Data, pages.first())
    }

    @Test
    fun `an identifier the code no longer knows is dropped`() {
        val pages = visiblePages(listOf("map", "from-a-later-version", "data"), emptySet())
        assertEquals(
            listOf("map", "data"),
            pages.map { page -> page.id }.filter { id -> id == "map" || id == "data" },
        )
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
    fun `a hidden page is still listed for the settings screen`() {
        // The bug this exists to stop: the settings list was built from the visible
        // pages, so switching one off removed the row that could switch it back on.
        // A page could be hidden exactly once, permanently.
        val hidden = setOf("notam", "weather")
        val listed = orderedPages(emptyList())
        assertTrue(WearPage.Notam in listed)
        assertTrue(WearPage.Weather in listed)
        assertTrue(WearPage.Notam !in visiblePages(emptyList(), hidden))
    }

    @Test
    fun `every page can be reached from the settings list whatever is hidden`() {
        val everythingHidden = WearPage.entries.map { page -> page.id }.toSet()
        assertEquals(WearPage.entries.size, orderedPages(emptyList()).size)
        assertEquals(WearPage.entries.toSet(), orderedPages(emptyList()).toSet())
        // ...even when the pager itself is down to one page.
        assertEquals(listOf(WearPage.Settings), visiblePages(emptyList(), everythingHidden))
    }

    @Test
    fun `moving counts steps past a hidden neighbour`() {
        // Moves are applied to the full order. Applying them to the visible list would
        // make a page jump two places the next time a hidden neighbour came back.
        val all = orderedPages(emptyList())
        val moved = movePage(all, WearPage.Traffic, -1)
        assertEquals(listOf("data", "map", "traffic", "instruments"), moved.take(4))
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
        assertEquals(
            listOf(
                "map", "data", "instruments", "traffic",
                "notam", "nearby", "weather", "log", "settings",
            ),
            moved,
        )
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
            listOf(
                WearPage.Data, WearPage.Map, WearPage.Instruments,
                WearPage.Traffic, WearPage.Notam, WearPage.Weather, WearPage.Nearby,
            ),
            again.take(7),
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
