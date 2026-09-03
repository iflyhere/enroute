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

#include <QHttpServerResponder>
#include <QObject>
#include <QSharedPointer>

#include "fileFormats/MBTILES.h"

namespace Companion
{

    /*! \brief Serves the pilot's own map to a companion device
     *
     *  A companion device with a real map renderer needs exactly what the app's own
     *  renderer needs: a style document, vector tiles, a terrain layer for
     *  hillshading, the aviation data overlay, the sprite sheet, and glyph ranges.
     *  All of that already exists on the device, and none of it comes from a remote
     *  service, so a watch talking to this class needs no internet connection of its
     *  own.
     *
     *  Nothing is re-rendered and nothing is re-styled here. The style served is the
     *  app's own style document with its three URL placeholders pointed at this
     *  server, which means a companion device draws the same map the pilot sees,
     *  including their night-mode preference.
     *
     *  This is deliberately **not** built on GeoMaps::TileHandler, even though that
     *  class answers almost the same requests. TileHandler bakes its base URL into
     *  the TileJSON at construction, and the URL a companion device must be given is
     *  the address it happened to reach us on -- a phone answers on several
     *  interfaces and only the request knows which one was used. Reuse therefore
     *  happens one level down, at FileFormats::MBTILES, which is the class that
     *  actually understands the file format.
     *
     *  Equally deliberately, this does not reuse GeoMaps::TileServer. That server
     *  answers a request for any path by looking it up in the app's resource system,
     *  which is safe on loopback and would be a disclosure of the entire resource
     *  system on a local network. Everything served here comes from an allow-list.
     */
    class MapAssets : public QObject
    {
        Q_OBJECT

    public:
        /*! \brief Standard constructor
         *
         *  @param parent The standard QObject parent
         */
        explicit MapAssets(QObject* parent = nullptr);

        /*! \brief Rebuilds the list of map files from the data manager
         *
         *  Also increments revision(), which changes every tile URL. That is what
         *  stops a companion device from serving stale tiles out of its own cache
         *  after the pilot has updated their maps -- the same trick the app plays on
         *  its own renderer by renaming its tile sets.
         */
        void refresh();

        /*! \brief Counter that changes whenever the set of map files changes
         *
         *  @returns A number that appears in every tile URL
         */
        [[nodiscard]] quint32 revision() const { return m_revision; }

        /*! \brief Whether a base map is available at all
         *
         *  @returns True if the pilot has downloaded at least one vector base map
         */
        [[nodiscard]] bool hasBaseMap() const { return !m_baseMap.isEmpty(); }

        /*! \brief Answers one request below the map path prefix
         *
         *  @param pathElements The path below the map prefix, split at '/'
         *
         *  @param baseUrl Absolute URL of the map prefix as the client reached it,
         *  without a trailing slash. Used to build every URL inside the style and
         *  the TileJSON documents.
         *
         *  @param responder Responder used to send the reply
         *
         *  @returns True if the request was answered, false if the path is not one
         *  this class serves
         */
        [[nodiscard]] bool handle(const QStringList& pathElements,
                                  const QString& baseUrl,
                                  QHttpServerResponder& responder);

    signals:
        /*! \brief Emitted after refresh() changed the set of map files */
        void revisionChanged();

    private:
        Q_DISABLE_COPY_MOVE(MapAssets)

        // The app's own style document with %URL%, %URLT% and %URL2% substituted.
        // Not cached: it depends on the request's own address and on the night-mode
        // setting, and a style document is a few tens of kilobytes fetched once per
        // session.
        [[nodiscard]] QByteArray styleDocument(const QString& baseUrl) const;

        // A set with no files still gets a valid document. The style always declares
        // its terrain source, but the terrain map is a separate optional download, so
        // the common case is a pilot who has a base map and no hillshading -- and a
        // client should be told "this source is empty" rather than left to interpret
        // a 404 on a source its style requires.
        [[nodiscard]] static QByteArray tileJSON(
            const QVector<QSharedPointer<FileFormats::MBTILES>>& files,
            const QString& tileUrlTemplate,
            const QString& fallbackFormat);

        // Path segment that carries the revision, so that a map update changes every
        // tile URL a client has cached.
        [[nodiscard]] QString baseMapPath() const;
        [[nodiscard]] QString terrainPath() const;

        [[nodiscard]] static bool writeTile(
            const QVector<QSharedPointer<FileFormats::MBTILES>>& files,
            const QStringList& coordinates,
            QHttpServerResponder& responder);

        QVector<QSharedPointer<FileFormats::MBTILES>> m_baseMap;
        QVector<QSharedPointer<FileFormats::MBTILES>> m_terrain;

        quint32 m_revision {0};
    };

} // namespace Companion
