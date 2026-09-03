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

#include <QFile>
#include <QHttpHeaders>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QRegularExpression>

#include "GlobalObject.h"
#include "GlobalSettings.h"
#include "companion/MapAssets.h"
#include "dataManagement/DataManager.h"
#include "dataManagement/Downloadable_SingleFile.h"
#include "geomaps/GeoMapProvider.h"

using namespace Qt::Literals::StringLiterals;


namespace
{

    //
    // The allow-list. Everything this class serves from the resource system is
    // checked against one of these, because the alternative -- looking a path up in
    // the resource system and serving whatever is found -- is what makes
    // GeoMaps::TileServer safe only on loopback.
    //

    // sprite.json, sprite.png, sprite@2x.json, sprite@2x.png, and nothing else.
    bool isSpriteName(const QString& name)
    {
        static const QRegularExpression pattern(u"^sprite(@2x)?\\.(json|png)$"_s);
        return pattern.match(name).hasMatch();
    }

    // A glyph range as MapLibre asks for it: "0-255.pbf".
    bool isGlyphRange(const QString& name)
    {
        static const QRegularExpression pattern(u"^\\d{1,6}-\\d{1,6}\\.pbf$"_s);
        return pattern.match(name).hasMatch();
    }

    /*! \brief Whether a font stack names a directory in our own resources
     *
     *  The allow-list for font stacks is the set of stacks the app actually ships,
     *  which is a question the resource system can answer. The character check comes
     *  first, so that no stack name can escape the directory even though the answer
     *  would still come from inside the binary.
     */
    bool isFontStack(const QString& stack)
    {
        static const QRegularExpression pattern(u"^[A-Za-z0-9 _-]{1,64}$"_s);
        if (!pattern.match(stack).hasMatch())
        {
            return false;
        }
        return QFile::exists(u":/flightMap/fonts/"_s + stack);
    }

    void writeResource(const QString& resourcePath,
                       const char* contentType,
                       QHttpServerResponder& responder)
    {
        QFile file(resourcePath);
        if (!file.open(QIODevice::ReadOnly))
        {
            responder.write(QHttpServerResponder::StatusCode::NotFound);
            return;
        }
        responder.write(file.readAll(), contentType);
    }

    /*! \brief Content type for a tile of the given MBTiles format
     *
     *  Vector tiles are stored gzipped inside the MBTiles file and are handed on in
     *  that form, which is why the caller adds Content-Encoding for them.
     */
    const char* contentTypeForFormat(const QString& format)
    {
        if (format == u"png"_s)
        {
            return "image/png";
        }
        if (format == u"jpg"_s)
        {
            return "image/jpeg";
        }
        if (format == u"webp"_s)
        {
            return "image/webp";
        }
        return "application/octet-stream";
    }

    QVector<QSharedPointer<FileFormats::MBTILES>> openFiles(
        DataManagement::Downloadable_MultiFile* multiFile)
    {
        QVector<QSharedPointer<FileFormats::MBTILES>> result;
        if (multiFile == nullptr)
        {
            return result;
        }
        for (auto* downloadableX : multiFile->downloadables())
        {
            auto* downloadable = qobject_cast<DataManagement::Downloadable_SingleFile*>(downloadableX);
            if (downloadable == nullptr)
            {
                continue;
            }
            if (!downloadable->hasFile())
            {
                continue;
            }
            result.append(QSharedPointer<FileFormats::MBTILES>(
                new FileFormats::MBTILES(downloadable->fileName())));
        }
        return result;
    }

} // namespace


Companion::MapAssets::MapAssets(QObject* parent)
    : QObject(parent)
{
    // Nothing here touches GlobalObject. The caller decides when this class may
    // start reading the data manager, which is what keeps a disabled feature from
    // opening a dozen SQLite databases.
}


void Companion::MapAssets::refresh()
{
    m_baseMap = openFiles(GlobalObject::dataManager()->baseMapsVector());
    m_terrain = openFiles(GlobalObject::dataManager()->terrainMaps());

    m_revision++;
    emit revisionChanged();
}


QString Companion::MapAssets::baseMapPath() const
{
    return u"base-"_s + QString::number(m_revision);
}


QString Companion::MapAssets::terrainPath() const
{
    return u"terrain-"_s + QString::number(m_revision);
}


QByteArray Companion::MapAssets::styleDocument(const QString& baseUrl) const
{
    // The same three files and the same three placeholders the app uses for its own
    // renderer, so that a companion device draws what the pilot sees -- night mode
    // included -- rather than an approximation maintained separately.
    QString resource = u":/flightMap/empty.json"_s;
    if (GlobalObject::dataManager()->baseMaps()->hasFile())
    {
        resource = GlobalObject::globalSettings()->nightMode()
                       ? u":/flightMap/osm-liberty-dark.json"_s
                       : u":/flightMap/osm-liberty.json"_s;
    }

    QFile file(resource);
    if (!file.open(QIODevice::ReadOnly))
    {
        return {};
    }
    auto data = file.readAll();

    // %URL% and %URLT% are TileJSON URLs, %URL2% is the root under which the style
    // asks for the sprite sheet, the glyph ranges and the aviation data. Keeping the
    // app's own path shapes below %URL2% is what lets the style be used unchanged.
    data.replace("%URL%", (baseUrl + u"/"_s + baseMapPath()).toUtf8());
    data.replace("%URLT%", (baseUrl + u"/"_s + terrainPath()).toUtf8());
    data.replace("%URL2%", baseUrl.toUtf8());

    return data;
}


QByteArray Companion::MapAssets::tileJSON(
    const QVector<QSharedPointer<FileFormats::MBTILES>>& files,
    const QString& tileUrlTemplate,
    const QString& fallbackFormat)
{
    QString name;
    QString encoding;
    QString description;
    QString version;
    QString attribution;
    QString format;

    // Widened rather than replaced as the files are walked, because a pilot with
    // Germany and Switzerland downloaded has two files whose zoom ranges need not
    // agree, and a TileJSON that understates the range makes MapLibre stop asking
    // for tiles that exist.
    int maxzoom {-1};
    int minzoom {-1};

    for (const auto& filePtr : files)
    {
        if (filePtr.isNull())
        {
            continue;
        }
        const auto metaData = filePtr->metaData();
        name = metaData.value(u"name"_s);
        encoding = metaData.value(u"encoding"_s);
        format = metaData.value(u"format"_s);
        description = metaData.value(u"description"_s);
        version = metaData.value(u"version"_s);
        attribution = metaData.value(u"attribution"_s);

        bool ok = false;
        const auto fileMax = metaData.value(u"maxzoom"_s).toInt(&ok);
        if (ok)
        {
            maxzoom = (maxzoom < 0) ? fileMax : qMax(maxzoom, fileMax);
        }
        const auto fileMin = metaData.value(u"minzoom"_s).toInt(&ok);
        if (ok)
        {
            minzoom = (minzoom < 0) ? fileMin : qMin(minzoom, fileMin);
        }
    }

    if (format.isEmpty())
    {
        format = fallbackFormat;
    }
    if (minzoom < 0)
    {
        minzoom = 0;
    }
    if (maxzoom < 0)
    {
        maxzoom = 0;
    }

    QJsonObject document;
    document.insert(u"tilejson"_s, u"2.2.0"_s);

    QJsonArray tiles;
    tiles.append(tileUrlTemplate + u"/{z}/{x}/{y}."_s + format);
    document.insert(u"tiles"_s, tiles);

    document.insert(u"format"_s, format);
    if (!name.isEmpty())
    {
        document.insert(u"name"_s, name);
    }
    if (!description.isEmpty())
    {
        document.insert(u"description"_s, description);
    }
    if (!encoding.isEmpty())
    {
        document.insert(u"encoding"_s, encoding);
    }
    if (!version.isEmpty())
    {
        document.insert(u"version"_s, version);
    }
    // Carried on purpose. The map data is licensed for non-commercial use with an
    // attribution requirement, so a client that renders these tiles has to be able
    // to render the notice with them.
    if (!attribution.isEmpty())
    {
        document.insert(u"attribution"_s, attribution);
    }
    if (minzoom >= 0)
    {
        document.insert(u"minzoom"_s, minzoom);
    }
    if (maxzoom >= 0)
    {
        document.insert(u"maxzoom"_s, maxzoom);
    }

    return QJsonDocument(document).toJson(QJsonDocument::Compact);
}


bool Companion::MapAssets::writeTile(
    const QVector<QSharedPointer<FileFormats::MBTILES>>& files,
    const QStringList& coordinates,
    QHttpServerResponder& responder)
{
    if (coordinates.size() != 3)
    {
        return false;
    }

    bool zoomOk = false;
    bool xOk = false;
    bool yOk = false;
    const auto zoom = coordinates[0].toInt(&zoomOk);
    const auto tileX = coordinates[1].toInt(&xOk);
    const auto tileY = coordinates[2].section('.', 0, 0).toInt(&yOk);
    if (!zoomOk || !xOk || !yOk)
    {
        return false;
    }

    for (const auto& filePtr : files)
    {
        if (filePtr.isNull())
        {
            continue;
        }
        // MBTILES::tile() takes the y of an XYZ URL and flips it to the TMS row the
        // file is indexed by, so nothing is flipped here.
        const auto tileData = filePtr->tile(zoom, tileX, tileY);
        if (tileData.isEmpty())
        {
            continue;
        }

        const auto format = filePtr->metaData().value(u"format"_s);
        QHttpHeaders headers;
        headers.append(QHttpHeaders::WellKnownHeader::ContentType,
                       QByteArray(contentTypeForFormat(format)));
        if (format == u"pbf"_s)
        {
            // Vector tiles are stored gzipped and handed on untouched. Without this
            // header a client sees a corrupt protobuf.
            headers.append(QHttpHeaders::WellKnownHeader::ContentEncoding, "gzip");
        }
        headers.append("X-Content-Type-Options", "nosniff");
        responder.write(tileData, headers);
        return true;
    }

    // A tile the pilot has no map for. Not an error: MapLibre asks for tiles beyond
    // the edge of a downloaded region all the time.
    responder.write(QHttpServerResponder::StatusCode::NoContent);
    return true;
}


bool Companion::MapAssets::handle(const QStringList& pathElements,
                                  const QString& baseUrl,
                                  QHttpServerResponder& responder)
{
    if (pathElements.isEmpty())
    {
        return false;
    }
    const auto& head = pathElements.constFirst();
    const auto tail = pathElements.mid(1);

    if (head == u"style.json"_s && tail.isEmpty())
    {
        const auto document = styleDocument(baseUrl);
        if (document.isEmpty())
        {
            return false;
        }
        responder.write(document, "application/json");
        return true;
    }

    // The whole aviation data set, exactly as the app's own renderer receives it.
    // Not tiled, because a GeoJSON source is not: a client fetches it once and keeps
    // it. That is a megabyte or two for a few countries, which is why it lives behind
    // its own URL rather than inside the style.
    if (head == u"aviationData.geojson"_s && tail.isEmpty())
    {
        responder.write(GlobalObject::geoMapProvider()->geoJSON(), "application/geo+json");
        return true;
    }

    // Shape kept identical to the app's own tile server, because the style document
    // below %URL2% is used unchanged.
    if (head == u"flightMap"_s && tail.size() >= 2)
    {
        if (tail.constFirst() == u"sprites"_s && tail.size() == 2 && isSpriteName(tail[1]))
        {
            writeResource(u":/flightMap/sprites/"_s + tail[1],
                          tail[1].endsWith(u".png"_s) ? "image/png" : "application/json",
                          responder);
            return true;
        }
        if (tail.constFirst() == u"fonts"_s && tail.size() == 3
            && isFontStack(tail[1]) && isGlyphRange(tail[2]))
        {
            writeResource(u":/flightMap/fonts/"_s + tail[1] + u"/"_s + tail[2],
                          "application/x-protobuf",
                          responder);
            return true;
        }
        return false;
    }

    if (head == baseMapPath())
    {
        if (tail.isEmpty())
        {
            responder.write(tileJSON(m_baseMap, baseUrl + u"/"_s + baseMapPath(), u"pbf"_s),
                            "application/json");
            return true;
        }
        return writeTile(m_baseMap, tail, responder);
    }

    if (head == terrainPath())
    {
        if (tail.isEmpty())
        {
            responder.write(tileJSON(m_terrain, baseUrl + u"/"_s + terrainPath(), u"png"_s),
                            "application/json");
            return true;
        }
        return writeTile(m_terrain, tail, responder);
    }

    return false;
}
