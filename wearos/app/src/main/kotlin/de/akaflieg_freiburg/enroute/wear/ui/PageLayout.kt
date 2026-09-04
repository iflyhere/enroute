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

package de.akaflieg_freiburg.enroute.wear.ui

/**
 * The pages the pager can show, and their default order.
 *
 * The identifier is what gets stored, not the position in this enum. A stored order
 * has to survive a version that adds a page or drops one, and an index would silently
 * come back meaning something else.
 */
enum class WearPage(val id: String, val label: String) {
    Data("data", "Data"),
    Map("map", "Map"),
    Traffic("traffic", "Traffic"),
    Notam("notam", "NOTAM"),
    Weather("weather", "Weather"),
    Log("log", "Log"),

    /**
     * Always last, never hidden, never moved.
     *
     * Not a matter of taste: a pilot who hides every other page must still be able to
     * reach the screen that unhides them, and one who reorders their way into a corner
     * must still be able to get out. Pinning it is what makes the rest safe to change.
     */
    Settings("settings", "Settings"),
    ;

    val canBeHidden: Boolean get() = this != Settings
    val canBeMoved: Boolean get() = this != Settings

    companion object {
        fun byId(id: String): WearPage? = entries.firstOrNull { page -> page.id == id }
    }
}

/**
 * Every page in the pilot's order, hidden ones included.
 *
 * This is what the settings screen lists. It has to be every page: a hidden page that
 * vanished from the settings list too would be a page that could be switched off and
 * never back on, which is exactly what happened the first time this was written.
 *
 * Every rule here exists because the stored value and the code can disagree:
 *
 *  - an identifier the code no longer knows is dropped, so an old preference cannot
 *    crash a new version;
 *  - a page the stored order never mentions is inserted beside the neighbour it has
 *    in the enum, so a page added in a new version turns up where a pilot would look
 *    for it rather than on the end;
 *  - a duplicate is kept once;
 *  - and Settings is forced last whatever the stored order says.
 */
fun orderedPages(order: List<String>): List<WearPage> {
    val stored = LinkedHashSet<WearPage>()
    for (id in order) {
        WearPage.byId(id)?.let { page -> stored.add(page) }
    }

    val result = stored.toMutableList()

    // A page the stored order never mentions goes back where it belongs rather than on
    // the end. Measured on a real watch: an order saved before the traffic page
    // existed put Traffic after the flight log, which is nowhere a pilot would look
    // for it.
    //
    // It is inserted before the first stored page that follows it in this enum. The
    // other way round -- after the nearest preceding one -- was tried first and is
    // worse: it moves a new page ahead of a page the pilot deliberately placed,
    // whereas this keeps every relation the pilot never overrode. Where the pilot has
    // inverted the default order there is no correct answer, only a consistent one.
    for (page in WearPage.entries) {
        if (page in result) {
            continue
        }
        val successor = WearPage.entries
            .drop(WearPage.entries.indexOf(page) + 1)
            .firstOrNull { later -> later in result }
        val at = if (successor == null) result.size else result.indexOf(successor)
        result.add(at, page)
    }

    result.remove(WearPage.Settings)
    result.add(WearPage.Settings)
    return result
}

/**
 * The pages the pager will show.
 *
 * [orderedPages] without the hidden ones. Settings survives whatever the hidden set
 * says, so the result is never empty and there is always a way back.
 */
fun visiblePages(order: List<String>, hidden: Set<String>): List<WearPage> =
    orderedPages(order).filter { page -> page == WearPage.Settings || page.id !in hidden }

/**
 * Moves one page one step, within the pages that may move.
 *
 * Returns the new order as identifiers, ready to store. A move that would step past
 * the pinned Settings page, or off either end, returns the order unchanged rather
 * than clamping silently to something the pilot did not ask for.
 */
fun movePage(order: List<WearPage>, page: WearPage, delta: Int): List<String> {
    if (!page.canBeMoved) {
        return order.map { entry -> entry.id }
    }
    val movable = order.filter { entry -> entry.canBeMoved }.toMutableList()
    val from = movable.indexOf(page)
    val to = from + delta
    if (from < 0 || to < 0 || to >= movable.size) {
        return order.map { entry -> entry.id }
    }
    movable.removeAt(from)
    movable.add(to, page)
    return (movable + order.filter { entry -> !entry.canBeMoved }).map { entry -> entry.id }
}

/** What a turn of the bezel does. */
enum class BezelAction(val id: String, val label: String) {
    /** Move between pages. */
    Pages("pages", "Switch screens"),

    /** Zoom the map, scroll a list -- what the bezel did before it could do both. */
    Content("content", "Zoom and scroll"),
    ;

    companion object {
        fun byId(id: String?): BezelAction =
            entries.firstOrNull { action -> action.id == id } ?: Pages
    }
}

/** Whether, and how, an approach chart is put on the map. */
enum class ChartMode(val id: String, val label: String) {
    /** Show the chart covering the aircraft, the way the app selects one. */
    Automatic("auto", "Automatic"),

    /** Never put a chart on the map. */
    Off("off", "Off"),
    ;

    companion object {
        fun byId(id: String?): ChartMode =
            entries.firstOrNull { mode -> mode.id == id } ?: Automatic
    }
}
