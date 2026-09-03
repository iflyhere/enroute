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

#include <QBuffer>
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
#include "positioning/PositionProvider.h"

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

    /*! \brief Sends a body that may be larger than a socket buffer
     *
     *  QHttpServerResponder::write() with a QByteArray hands the bytes to the socket
     *  and returns, while the responder's own lifetime ends when the request handler
     *  does. The QIODevice overload exists for the case where that is not enough: the
     *  responder keeps feeding the socket afterwards, and takes ownership of the
     *  device. The aviation data is four and a half megabytes, which is well past the
     *  point where relying on the socket buffer is a reasonable thing to do.
     *
     *  Written after chasing a truncated response that turned out not to be this at
     *  all -- it was the development machine's WSL network boundary cutting the body,
     *  and the app was serving all of it. So this is a precaution rather than a fix,
     *  and no observed bug is claimed for it.
     */
    void writeLarge(const QByteArray& data,
                    const QByteArray& mimeType,
                    QHttpServerResponder& responder)
    {
        auto* buffer = new QBuffer;
        buffer->setData(data);
        buffer->open(QIODevice::ReadOnly);
        responder.write(buffer, mimeType);
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
        writeLarge(file.readAll(), contentType, responder);
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

    // Close enough to read an aerodrome, for the case where the app knows where the
    // aircraft is.
    constexpr double nearZoom = 10.0;

    // Wide enough to see which country was downloaded, for the case where it does not.
    constexpr double overviewZoom = 6.0;

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
    auto newBaseMap = openFiles(GlobalObject::dataManager()->baseMapsVector());
    auto newTerrain = openFiles(GlobalObject::dataManager()->terrainMaps());

    // The revision is in every tile URL, so moving it makes a client throw away its
    // whole tile cache and refetch the style. The signals that bring us here can fire
    // without the set of files having changed at all, and paying that price for
    // nothing is worse on a watch than on a phone.
    const auto changed = [](const QVector<QSharedPointer<FileFormats::MBTILES>>& before,
                            const QVector<QSharedPointer<FileFormats::MBTILES>>& after)
    {
        if (before.size() != after.size())
        {
            return true;
        }
        for (qsizetype i = 0; i < before.size(); ++i)
        {
            if (before.at(i).isNull() || after.at(i).isNull())
            {
                return true;
            }
            if (before.at(i)->fileName() != after.at(i)->fileName())
            {
                return true;
            }
        }
        return false;
    };

    const auto moved = m_revision == 0
                       || changed(m_baseMap, newBaseMap)
                       || changed(m_terrain, newTerrain);

    m_baseMap = std::move(newBaseMap);
    m_terrain = std::move(newTerrain);

    if (!moved)
    {
        return;
    }

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


QJsonArray Companion::MapAssets::centreHint() const
{
    double lon = 0.0;
    double lat = 0.0;
    double zoom = 0.0;
    if (!baseMapCentre(lon, lat, zoom))
    {
        return {};
    }
    QJsonArray hint;
    hint.append(lon);
    hint.append(lat);
    hint.append(zoom);
    return hint;
}


QString Companion::MapAssets::attribution() const
{
    // Composed from the same conditions GeoMapProvider::copyrightNotice() uses, and
    // not from the base map's own metadata, which credits OpenStreetMap alone. The
    // style draws the aviation data layer too, so a client that showed only the
    // metadata's notice would be rendering openAIP and open flightmaps data while
    // crediting neither.
    //
    // Compact rather than complete: copyrightNotice() is several paragraphs of HTML
    // with links, which belongs on a device where a link can be followed. The full
    // notice stays on the phone; this is the line a watch can carry.
    QStringList sources;
    auto* const dataManager = GlobalObject::dataManager();

    if (dataManager->aviationMaps()->hasFile())
    {
        sources << u"openAIP"_s << u"open flightmaps"_s;
    }
    if (dataManager->baseMaps()->hasFile())
    {
        sources << u"OpenStreetMap contributors"_s;
    }
    if (dataManager->terrainMaps()->hasFile())
    {
        sources << u"Terrain Tiles"_s;
    }

    if (sources.isEmpty())
    {
        return {};
    }
    return u"© "_s + sources.join(u" · "_s);
}


bool Companion::MapAssets::baseMapCentre(double& lon, double& lat, double& zoom) const
{
    // Nothing to centre on without a map, so do not even ask where the aircraft is.
    if (m_baseMap.isEmpty())
    {
        return false;
    }

    // Where the pilot last was, if the app knows. Far more useful than anything
    // derived from the map files: a client that has no route and no position of its
    // own then opens where the aircraft is parked instead of in the middle of a
    // country. It is only a default -- the client moves the camera itself the moment
    // a real position arrives.
    const auto lastKnown = GlobalObject::positionProvider()->approximateLastValidCoordinate();
    if (lastKnown.isValid())
    {
        lon = lastKnown.longitude();
        lat = lastKnown.latitude();
        zoom = nearZoom;
        return true;
    }

    // Otherwise the middle of everything that has been downloaded. Taking the first
    // file's own centre would be arbitrary: a pilot with Germany and Switzerland
    // would open over whichever one happened to be listed first.
    double minLon = 0.0;
    double minLat = 0.0;
    double maxLon = 0.0;
    double maxLat = 0.0;
    bool any = false;

    for (const auto& filePtr : m_baseMap)
    {
        if (filePtr.isNull())
        {
            continue;
        }
        // "bounds" in an MBTiles file is "minLon,minLat,maxLon,maxLat", and is
        // optional by the specification.
        const auto parts = filePtr->metaData().value(u"bounds"_s).split(u',');
        if (parts.size() < 4)
        {
            continue;
        }
        bool ok = true;
        double values[4] = {0.0, 0.0, 0.0, 0.0};
        for (int i = 0; i < 4; ++i)
        {
            bool one = false;
            values[i] = parts[i].toDouble(&one);
            ok = ok && one;
        }
        if (!ok)
        {
            continue;
        }

        if (!any)
        {
            minLon = values[0];
            minLat = values[1];
            maxLon = values[2];
            maxLat = values[3];
            any = true;
        }
        else
        {
            minLon = qMin(minLon, values[0]);
            minLat = qMin(minLat, values[1]);
            maxLon = qMax(maxLon, values[2]);
            maxLat = qMax(maxLat, values[3]);
        }
    }

    if (!any)
    {
        return false;
    }
    lon = (minLon + maxLon) / 2.0;
    lat = (minLat + maxLat) / 2.0;
    zoom = overviewZoom;
    return true;
}


namespace
{

    /*! \brief Resolves one "@marker" from the generated aviation layers
     *
     *  The generator cannot bake in what the running app decides -- the pilot's
     *  altitude filter, their font size, and every colour and opacity that depends on
     *  night mode -- so those appear as markers. The day and night alternatives are
     *  extracted from the same QML the layers come from and travel in the generated
     *  file, so nothing is written down twice.
     */
    QJsonValue resolveMarker(const QString& marker, const QJsonObject& values)
    {
        auto* const settings = GlobalObject::globalSettings();

        if (marker == u"@altitudeLimitFt"_s)
        {
            const auto limit = settings->airspaceAltitudeLimit();
            // QML uses 10e6 for "no limit", and the filter compares against it, so an
            // unset limit has to be a number and not a null.
            return limit.isFinite() ? limit.toFeet() : 10e6;
        }

        if (marker.startsWith(u"@fontSize:"_s))
        {
            bool ok = false;
            const auto factor = marker.mid(10).toDouble(&ok);
            return (ok ? factor : 1.0) * settings->fontSize();
        }

        const auto name = marker.mid(1);
        const auto pair = values.value(name).toObject();
        if (pair.isEmpty())
        {
            return {};
        }
        return pair.value(settings->nightMode() ? u"night"_s : u"day"_s);
    }

    /*! \brief Replaces every marker in a JSON tree, in place */
    QJsonValue resolveMarkers(const QJsonValue& node, const QJsonObject& values)
    {
        if (node.isString())
        {
            const auto text = node.toString();
            return text.startsWith(u'@') ? resolveMarker(text, values) : node;
        }
        if (node.isArray())
        {
            QJsonArray result;
            const auto array = node.toArray();
            for (const auto& item : array)
            {
                result.append(resolveMarkers(item, values));
            }
            return result;
        }
        if (node.isObject())
        {
            QJsonObject result;
            const auto object = node.toObject();
            for (auto it = object.constBegin(); it != object.constEnd(); ++it)
            {
                result.insert(it.key(), resolveMarkers(it.value(), values));
            }
            return result;
        }
        return node;
    }

} // namespace


void Companion::MapAssets::addAviationLayers(QJsonObject& style)
{
    QFile file(u":/flightMap/aviation-layers.json"_s);
    if (!file.open(QIODevice::ReadOnly))
    {
        return;
    }
    const auto document = QJsonDocument::fromJson(file.readAll()).object();
    const auto values = document.value(u"values"_s).toObject();
    const auto layers = document.value(u"layers"_s).toArray();
    if (layers.isEmpty())
    {
        return;
    }

    // Appended, not merged in at a position: the generator preserves the order the QML
    // declares, which that file describes as sorted by importance from low to high, and
    // the whole aviation overlay belongs above the base map.
    auto existing = style.value(u"layers"_s).toArray();
    for (const auto& layer : layers)
    {
        existing.append(resolveMarkers(layer, values));
    }
    style.insert(u"layers"_s, existing);
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

    auto document = QJsonDocument::fromJson(data).object();
    if (document.isEmpty())
    {
        return data;
    }

    // The aviation overlay. Without it a client renders roads and towns and no
    // airspace, because the app declares the aviation-data source in its style file
    // but draws every layer from it in QML.
    addAviationLayers(document);

    // The app's own style has no centre, because its renderer is always told where to
    // look. A companion device is not, so one is added here when the map files know.
    double lon = 0.0;
    double lat = 0.0;
    double zoom = 0.0;
    if (baseMapCentre(lon, lat, zoom) && !document.contains(u"center"_s))
    {
        QJsonArray centre;
        centre.append(lon);
        centre.append(lat);
        document.insert(u"center"_s, centre);
        document.insert(u"zoom"_s, zoom);
    }

    return QJsonDocument(document).toJson(QJsonDocument::Compact);
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

        // Same precaution as writeLarge(): a vector tile runs to a couple of hundred
        // kilobytes, which is already past what a socket buffer holds.
        auto* buffer = new QBuffer;
        buffer->setData(tileData);
        buffer->open(QIODevice::ReadOnly);
        responder.write(buffer, headers);
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
        writeLarge(GlobalObject::geoMapProvider()->geoJSON(), "application/geo+json", responder);
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
        if (tail.constFirst() == u"fonts"_s && tail.size() == 3 && isGlyphRange(tail[2]))
        {
            // A client that asks for a stack this app does not ship gets a shipped one
            // instead of a 404. That is not politeness: a symbol layer whose font
            // never arrives stalls the entire style load in MapLibre, so the map goes
            // blank rather than merely unlabelled. Every renderer has its own default
            // stack and none of them is ours.
            const auto stack = isFontStack(tail[1]) ? tail[1] : u"Roboto Regular"_s;
            writeResource(u":/flightMap/fonts/"_s + stack + u"/"_s + tail[2],
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
