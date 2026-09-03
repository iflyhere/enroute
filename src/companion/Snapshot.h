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

#include <QJsonArray>
#include <QJsonObject>

namespace GeoMaps
{
    class VACLibrary;
} // namespace GeoMaps

namespace Weather
{
    class ObserverList;
} // namespace Weather

namespace Companion
{

    /*! \brief Document revisions
     *
     *  These three counters carry all cache coherency in the protocol. A client
     *  caches the route document keyed on session and route revision, and refetches
     *  when either moves, which means a client needs to persist nothing.
     */
    struct Revisions
    {
        /*! \brief Random identifier, regenerated at every start of the app
         *
         *  Tells a client that the app restarted and that its cached revision
         *  numbers are therefore meaningless.
         */
        quint32 session {0};

        /*! \brief Incremented whenever the route document changes
         *
         *  Also changes when the pilot's unit preferences change, because those
         *  alter the units member and every formatted string.
         */
        quint32 route {0};

        /*! \brief Incremented for every published navigation frame */
        quint32 nav {0};

        /*! \brief Incremented whenever the NOTAM document changes */
        quint32 notam {0};

        /*! \brief Incremented whenever the weather document changes */
        quint32 weather {0};

        /*! \brief Incremented whenever the set of approach charts changes */
        quint32 vac {0};

        /*! \brief Incremented whenever the flight log document changes */
        quint32 log {0};

        /*! \brief Changes whenever the set of downloaded map files changes
         *
         *  Every tile URL contains this, so that a client's own tile cache cannot
         *  outlive the maps it was filled from. A client that sees this move must
         *  reload the style document, because the URLs inside the one it holds no
         *  longer resolve.
         */
        quint32 map {0};
    };


    /*! \brief Encoders for the companion protocol documents
     *
     *  These functions read the current state from GlobalObject::navigator() and
     *  GlobalObject::positionProvider() and build the documents described in
     *  doc/companion-protocol.md. They hold no state of their own and know nothing
     *  about how the documents are transported.
     *
     *  A value that is not known is left out of the document rather than encoded as
     *  null: the Units classes use NaN as their invalid marker, JSON cannot carry
     *  NaN, and omission gives a client a single unambiguous rule.
     */
    namespace Snapshot
    {
        /*! \brief Capability document
         *
         *  Lets a client confirm that it is talking to this app rather than to some
         *  other server on the same network, and learn how often frames are
         *  published.
         *
         *  @param revisions Current document revisions
         *
         *  @returns The document described under "Capability document" in
         *  doc/companion-protocol.md
         */
        [[nodiscard]] QJsonObject hello(const Companion::Revisions& revisions,
                                        const QString& mapAttribution = {},
                                        const QJsonArray& mapCentre = {},
                                        const QJsonObject& mapOverlayColours = {});

        /*! \brief Route snapshot
         *
         *  A slim representation, not the app's own GeoJSON: Waypoint::toJSON()
         *  serialises every property it holds, including the multi-line airfield
         *  information, communication and runway strings, which would make a
         *  hundred-waypoint route tens of kilobytes of data a watch never shows.
         *
         *  @param revisions Current document revisions
         *
         *  @returns The document described under "Route document" in
         *  doc/companion-protocol.md
         */
        [[nodiscard]] QJsonObject route(const Companion::Revisions& revisions);

        /*! \brief Navigation state frame
         *
         *  @param revisions Current document revisions
         *
         *  @param withFormattedStrings If true, the document contains the member
         *  "fmt" with the strings that Navigation::Aircraft would display. This is
         *  suppressed for transports where a frame has to fit into a single
         *  Bluetooth notification; nothing is lost, because the SI members are a
         *  complete description.
         *
         *  @returns The document described under "Navigation frame" in
         *  doc/companion-protocol.md
         */
        [[nodiscard]] QJsonObject nav(const Companion::Revisions& revisions,
                                      bool withFormattedStrings = true);

        /*! \brief NOTAMs along the route
         *
         *  Mirrors what the app already computes and shows: for every waypoint of the
         *  route, NOTAMProvider::notams() restricted to that waypoint. No new
         *  relevance logic is invented here, because that would be a safety claim the
         *  app itself does not make.
         *
         *  The filter the app applies is horizontal only, a fixed radius around a
         *  waypoint, and it does not use the flight level band that a NOTAM carries.
         *  The document says so, so that a client cannot present this as more than it
         *  is.
         *
         *  @param revisions Current document revisions. Unlike the other
         *  encoders, this one does not stamp the NOTAM revision into the
         *  document: whether that counter moved is decided by comparing two
         *  encodings, so the caller adds it once it knows the answer.
         *
         *  @returns The document described under "NOTAM document" in
         *  doc/companion-protocol.md, without its notamRev member
         */
        [[nodiscard]] QJsonObject notams(const Companion::Revisions& revisions);

        /*! \brief METAR and TAF for the stations the app knows about
         *
         *  Mirrors what the app's own weather page shows, and in the same order:
         *  Weather::ObserverList keeps its list sorted by distance to the last known
         *  position, so a client rendering the array as it arrives gets the nearest
         *  station first without doing any geometry.
         *
         *  Both the raw report and the app's own decoded summary travel. The raw text
         *  because a pilot reads METAR verbatim and it is already short; the summary
         *  because Weather::METAR::summary() applies the pilot's unit preferences and
         *  is translated, and a second implementation on a watch would eventually
         *  disagree with the phone about the same weather.
         *
         *  @param revisions Current document revisions
         *
         *  @param observers The app's station list. Passed in rather than constructed
         *  here, because Weather::ObserverList owns one Weather::Observer per station
         *  and rebuilding it per request would discard that cache on every poll.
         *
         *  @returns The document described under "Weather document" in
         *  doc/companion-protocol.md, without its weatherRev member
         */
        [[nodiscard]] QJsonObject weather(const Companion::Revisions& revisions,
                                          Weather::ObserverList* observers);

        /*! \brief The pilot's approach charts
         *
         *  Lists every chart the app has, manually imported and from a downloaded
         *  collection alike, with the four corner coordinates each one is drawn on.
         *  The image bytes are not in here; each entry carries the path they are
         *  served from.
         *
         *  No chart is singled out as the relevant one. The app's own rule for that
         *  is GeoMaps::VACLibrary::vacs4Point(), which is plain bounding-box
         *  containment, so a client can apply the identical rule to the boxes in
         *  this document and stay in step with the phone without polling.
         *
         *  @param revisions Current document revisions
         *
         *  @param library The app's chart library, or nullptr if it could not be
         *  reached, in which case the document lists no charts and says so.
         *
         *  @returns The document described under "Approach chart document" in
         *  doc/companion-protocol.md, without its vacRev member
         */
        [[nodiscard]] QJsonObject vacs(const Companion::Revisions& revisions,
                                       GeoMaps::VACLibrary* library);

        /*! \brief The pilot's flight log
         *
         *  The app's own entries in the app's own order, newest first, together with
         *  what its flight detector currently believes. Only the most recent entries
         *  travel; the rest are counted, not sent, because a log grows without bound
         *  and a watch scrolls a list with a finger in a cockpit.
         *
         *  Read-only. A flight is started, ended and edited on the phone, which is
         *  where the record belongs; nothing here can change one.
         *
         *  @param revisions Current document revisions
         *
         *  @param limit Number of entries to send
         *
         *  @returns The document described under "Flight log document" in
         *  doc/companion-protocol.md, without its logRev member
         */
        [[nodiscard]] QJsonObject flightLog(const Companion::Revisions& revisions,
                                            int limit);

    } // namespace Snapshot

} // namespace Companion
