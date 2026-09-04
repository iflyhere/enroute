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

#pragma once

#include <QLatin1StringView>

/*! \brief Export of route and navigation state to companion devices
 *
 *  This namespace contains the machinery that publishes the current flight route
 *  and the live navigation state to a companion device, such as a smartwatch.
 *
 *  The wire format is specified in doc/companion-protocol.md, which is normative.
 *  Nothing here depends on a library that the app does not already link: the
 *  documents are built with QJsonDocument and served over Qt HttpServer, which is
 *  already used for map tiles. In particular, the Google Play Services Wearable
 *  Data Layer is deliberately not used, since it is distributed under proprietary
 *  terms.
 *
 *  The design separates encoding from transport. Companion::Snapshot builds the
 *  documents and knows nothing about how they travel; Companion::CompanionServer
 *  owns the revision counters and the publication throttle; and a transport class
 *  merely hands out the cached bytes. A second transport therefore adds a file
 *  rather than changing the encoder.
 */

namespace Companion
{
    /*! \brief Version of the wire protocol implemented here
     *
     *  Adding a member to a document is not a breaking change and does not
     *  increment this: clients are required to ignore members they do not know.
     *  Removing or reinterpreting a member does increment it, and also changes the
     *  path prefix below.
     */
    constexpr int protocolVersion = 1;

    /*! \brief TCP port of the companion HTTP server
     *
     *  Fixed rather than ephemeral, so that the address can be written down once
     *  and typed in by hand on a device whose text entry is unpleasant, and so
     *  that it survives a restart of the app.
     */
    constexpr quint16 defaultPort = 8973;

    /*! \brief Path prefix of every endpoint, including the protocol version */
    constexpr auto pathPrefix = QLatin1StringView("/enroute/v1");

    /*! \brief Number of decimal digits used for coordinates on the wire
     *
     *  Five digits is about 1.1 m, which is far below the accuracy of any satnav
     *  receiver and keeps a hundred-waypoint route compact.
     */
    constexpr int coordinatePrecision = 5;

    /*! \brief Upper bound on the number of NOTAMs in one document
     *
     *  A route that crosses a busy region can accumulate hundreds of NOTAMs, and
     *  a NOTAM text runs to a few hundred characters, so an unbounded document
     *  could reach several hundred kilobytes. The document says when it hit this
     *  limit, so that a client can tell the pilot the list was shortened rather
     *  than silently show a subset.
     */
    constexpr int maximumNotams = 60;

} // namespace Companion
