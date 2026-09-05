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

/**
 * Where to find the phone.
 *
 * Hard-coded for now. The connection screen -- discovery beacon, manual address entry
 * and a remembered-hosts list -- is a separate piece of work; until then these defaults
 * point at the mock server reached through "adb reverse tcp:8973 tcp:8973", which is how
 * the app is developed against a machine that cannot build the phone app.
 */
object Config {
    const val DEFAULT_HOST = "127.0.0.1"
    const val DEFAULT_PORT = 8973
    const val DEFAULT_PAIRING_CODE = "418302"
}
