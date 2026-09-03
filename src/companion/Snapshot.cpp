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

#include <cmath>

#include <QCoreApplication>
#include <QDateTime>
#include <QJsonArray>
#include <QMetaEnum>
#include <QMetaProperty>
#include <QRegularExpression>

#include "GlobalObject.h"
#include "companion/Protocol.h"
#include "companion/Snapshot.h"
#include "config.h"
#include "navigation/Leg.h"
#include "navigation/Navigator.h"
#include "notam/NOTAMProvider.h"
#include "positioning/PositionProvider.h"
#include "weather/METAR.h"
#include "weather/Observer.h"
#include "weather/ObserverList.h"
#include "weather/TAF.h"
#include "weather/WeatherDataProvider.h"

using namespace Qt::Literals::StringLiterals;


namespace Companion
{

    /*! \brief Read access to the members of Navigation::RemainingRouteInfo
     *
     *  RemainingRouteInfo exposes its members to QML as properties declared with
     *  MEMBER, which means there are no getter functions, and the members
     *  themselves are private. This struct is befriended by that class so that the
     *  encoder can read them with compile-time checking.
     *
     *  The alternative, should this friendship ever be unwelcome, is to read the
     *  same values through the gadget meta-object, which is public API but
     *  stringly typed:
     *
     *      const auto& mo = Navigation::RemainingRouteInfo::staticMetaObject;
     *      mo.property(mo.indexOfProperty("nextWP_DIST")).readOnGadget(&rri);
     */
    struct SnapshotAccess
    {
        using Info = Navigation::RemainingRouteInfo;

        [[nodiscard]] static Info::Status status(const Info& i) { return i.status; }
        [[nodiscard]] static QString note(const Info& i) { return i.note; }

        [[nodiscard]] static GeoMaps::Waypoint nextWP(const Info& i) { return i.nextWP; }
        [[nodiscard]] static Units::Distance nextDIST(const Info& i) { return i.nextWP_DIST; }
        [[nodiscard]] static Units::Timespan nextETE(const Info& i) { return i.nextWP_ETE; }
        [[nodiscard]] static QDateTime nextETA(const Info& i) { return i.nextWP_ETA; }
        [[nodiscard]] static Units::Angle nextTC(const Info& i) { return i.nextWP_TC; }

        [[nodiscard]] static GeoMaps::Waypoint finalWP(const Info& i) { return i.finalWP; }
        [[nodiscard]] static Units::Distance finalDIST(const Info& i) { return i.finalWP_DIST; }
        [[nodiscard]] static Units::Timespan finalETE(const Info& i) { return i.finalWP_ETE; }
        [[nodiscard]] static QDateTime finalETA(const Info& i) { return i.finalWP_ETA; }
    };

} // namespace Companion


namespace
{

    //
    // Helpers that implement the two rules running through the whole protocol:
    // a value that is not known is left out entirely, and enumerations travel as
    // stable strings rather than as their numeric values.
    //

    void insertIfFinite(QJsonObject& object, QLatin1StringView key, Units::Distance value)
    {
        if (value.isFinite())
        {
            object.insert(key, qRound(value.toM()));
        }
    }

    void insertIfFinite(QJsonObject& object, QLatin1StringView key, Units::Speed value)
    {
        if (value.isFinite())
        {
            object.insert(key, std::round(value.toMPS() * 10.0) / 10.0);
        }
    }

    void insertIfFinite(QJsonObject& object, QLatin1StringView key, Units::Angle value)
    {
        if (value.isFinite())
        {
            object.insert(key, std::round(value.toDEG() * 10.0) / 10.0);
        }
    }

    void insertIfFinite(QJsonObject& object, QLatin1StringView key, Units::Timespan value)
    {
        if (value.isFinite())
        {
            object.insert(key, qRound64(value.toS()));
        }
    }

    /*! \brief Coordinate as a GeoJSON position, longitude first */
    QJsonValue toJSON(const QGeoCoordinate& coordinate)
    {
        if (!coordinate.isValid())
        {
            return {};
        }
        const auto factor = std::pow(10.0, Companion::coordinatePrecision);
        QJsonArray position;
        position.append(std::round(coordinate.longitude() * factor) / factor);
        position.append(std::round(coordinate.latitude() * factor) / factor);
        return position;
    }

    QLatin1StringView toString(Navigation::RemainingRouteInfo::Status status)
    {
        switch (status)
        {
        case Navigation::RemainingRouteInfo::NoRoute:
            return "noRoute"_L1;
        case Navigation::RemainingRouteInfo::PositionUnknown:
            return "positionUnknown"_L1;
        case Navigation::RemainingRouteInfo::OffRoute:
            return "offRoute"_L1;
        case Navigation::RemainingRouteInfo::NearDestination:
            return "nearDestination"_L1;
        case Navigation::RemainingRouteInfo::OnRoute:
            return "onRoute"_L1;
        }
        return "noRoute"_L1;
    }

    QLatin1StringView toString(Navigation::Navigator::FlightStatus status)
    {
        switch (status)
        {
        case Navigation::Navigator::Ground:
            return "ground"_L1;
        case Navigation::Navigator::Flight:
            return "flight"_L1;
        case Navigation::Navigator::Unknown:
            return "unknown"_L1;
        }
        return "unknown"_L1;
    }

    QLatin1StringView toString(Navigation::Aircraft::HorizontalDistanceUnit unit)
    {
        switch (unit)
        {
        case Navigation::Aircraft::NauticalMile:
            return "nm"_L1;
        case Navigation::Aircraft::Kilometer:
            return "km"_L1;
        case Navigation::Aircraft::StatuteMile:
            // The suffix that Aircraft itself emits is "mil", not "mi".
            return "mil"_L1;
        }
        return "nm"_L1;
    }

    QLatin1StringView toString(Navigation::Aircraft::VerticalDistanceUnit unit)
    {
        switch (unit)
        {
        case Navigation::Aircraft::Feet:
            return "ft"_L1;
        case Navigation::Aircraft::Meters:
            return "m"_L1;
        }
        return "ft"_L1;
    }

    /*! \brief Strips the rich text that FlightRoute::summary() can contain
     *
     *  That property appends "<p><font color='red'>Computation incomplete…</font></p>"
     *  when wind or aircraft data are missing. A client would render the tags
     *  literally, so they are removed here. The markup is known and contains no
     *  entities, so a tag strip is enough and this stays free of QtGui.
     */
    QString plainText(const QString& richText)
    {
        if (!richText.contains(u'<'))
        {
            return richText;
        }
        static const QRegularExpression tag(u"<[^>]*>"_s);
        // QString::replace mutates, so this needs its own copy.
        auto result = richText;
        return result.replace(tag, u" "_s).simplified();
    }

    /*! \brief The translated message that belongs to a route status
     *
     *  Uses the translation context of the moving map's own status bar, so that the
     *  companion device shows exactly the wording, and exactly the translation,
     *  that the app shows on its own screen. These strings already exist in every
     *  language the app ships.
     */
    QString statusText(Navigation::RemainingRouteInfo::Status status,
                       const Navigation::Aircraft& aircraft)
    {
        switch (status)
        {
        case Navigation::RemainingRouteInfo::PositionUnknown:
            return QCoreApplication::translate("RemainingRouteBar", "Position unknown.");
        case Navigation::RemainingRouteInfo::OffRoute:
            return QCoreApplication::translate("RemainingRouteBar", "More than %1 off route.")
                .arg(aircraft.horizontalDistanceToString(Navigation::Leg::nearThreshold));
        case Navigation::RemainingRouteInfo::NearDestination:
            return QCoreApplication::translate("RemainingRouteBar", "Near destination.");
        case Navigation::RemainingRouteInfo::NoRoute:
        case Navigation::RemainingRouteInfo::OnRoute:
            break;
        }
        return {};
    }

    /*! \brief Pressure altitude as a flight level, the way the moving map shows it
     *
     *  Three characters after "FL", zero padded, rounded to the nearest hundred feet:
     *  the same shape as src/qml/items/NavBar.qml produces, so the watch and the phone
     *  never disagree about which flight level the aircraft is at.
     *
     *  A dash when there is nothing to show, which the map also does for a reading that
     *  is absent or negative -- and here also for one the app has flagged implausible.
     */
    QString flightLevelToString(Units::Distance pressureAltitude, bool trusted)
    {
        if (!trusted)
        {
            return u"-"_s;
        }
        const auto hundredsOfFeet = qRound(pressureAltitude.toFeet() / 100.0);
        return u"FL"_s + QString::number(hundredsOfFeet).rightJustified(3, u'0');
    }


    /*! \brief A true course, formatted the way the moving map's status bar does */
    QString courseToString(Units::Angle course)
    {
        if (!course.isFinite())
        {
            return u"-"_s;
        }
        return u"%1°"_s.arg(qRound(course.toDEG()));
    }

    QJsonObject unitsObject(const Navigation::Aircraft& aircraft)
    {
        QJsonObject units;
        units.insert("hDist"_L1, toString(aircraft.horizontalDistanceUnit()));
        units.insert("vDist"_L1, toString(aircraft.verticalDistanceUnit()));
        return units;
    }

    /*! \brief Slim wire representation of a waypoint */
    QJsonObject toJSON(const GeoMaps::Waypoint& waypoint)
    {
        QJsonObject object;
        object.insert("n"_L1, waypoint.shortName());

        const auto extendedName = waypoint.extendedName();
        if (extendedName != waypoint.shortName())
        {
            object.insert("en"_L1, extendedName);
        }

        object.insert("c"_L1, toJSON(waypoint.coordinate()));

        // Altitude does not round-trip through the GeoJSON geometry, so it travels
        // as its own member, in metres above mean sea level.
        if (waypoint.coordinate().type() == QGeoCoordinate::Coordinate3D)
        {
            object.insert("e"_L1, qRound(waypoint.coordinate().altitude()));
        }

        object.insert("t"_L1, waypoint.type());
        object.insert("cat"_L1, waypoint.category());
        return object;
    }


    /*! \brief Section heading that the app itself puts above a NOTAM
     *
     *  NOTAM declares this one with MEMBER rather than READ, so there is no getter
     *  and the member is private. Reading it through the gadget meta-object is
     *  public API and needs no change to NOTAM.h, which keeps this feature out of
     *  a file it has no other reason to touch.
     *
     *  The value is filled in by NOTAMList::restricted(), which is where the app
     *  computes it too, so a client sees exactly the grouping the phone shows.
     */
    QString sectionTitleOf(const NOTAM::NOTAM& notam)
    {
        static const auto index = NOTAM::NOTAM::staticMetaObject.indexOfProperty("sectionTitle");
        if (index < 0)
        {
            return {};
        }
        return NOTAM::NOTAM::staticMetaObject.property(index).readOnGadget(&notam).toString();
    }

    /*! \brief Instant as ISO 8601 in UTC, or nothing if it is not known
     *
     *  A NOTAM that is permanent carries an invalid effectiveEnd, and one whose
     *  start could not be parsed carries an invalid effectiveStart. Both are
     *  omitted rather than encoded, following the rule for the rest of the
     *  protocol.
     */
    void insertIfValid(QJsonObject& object, QLatin1StringView key, const QDateTime& instant)
    {
        if (instant.isValid())
        {
            object.insert(key, instant.toUTC().toString(Qt::ISODate));
        }
    }

    QJsonObject toJSON(const NOTAM::NOTAM& notam)
    {
        QJsonObject object;
        object.insert("n"_L1, notam.number());
        object.insert("txt"_L1, notam.text());
        object.insert("cat"_L1, notam.category());
        object.insert("read"_L1, GlobalObject::notamProvider()->isRead(notam.number()));

        const auto section = sectionTitleOf(notam);
        if (!section.isEmpty())
        {
            object.insert("sect"_L1, section);
        }
        if (!notam.icaoLocation().isEmpty())
        {
            object.insert("icao"_L1, notam.icaoLocation());
        }
        if (!notam.traffic().isEmpty())
        {
            object.insert("traffic"_L1, notam.traffic());
        }

        insertIfValid(object, "from"_L1, notam.effectiveStart());
        insertIfValid(object, "to"_L1, notam.effectiveEnd());

        // The affected area, so that a client can draw it. Centre and radius
        // rather than the app's own GeoJSON, which encodes the circle as a
        // polygon of many points.
        const auto region = notam.region();
        if (region.isValid())
        {
            QJsonObject area;
            area.insert("c"_L1, toJSON(region.center()));
            area.insert("r"_L1, qRound(region.radius()));
            object.insert("area"_L1, area);
        }

        return object;
    }

} // namespace


QJsonObject Companion::Snapshot::hello(const Companion::Revisions& revisions,
                                       const QString& mapAttribution,
                                       const QJsonArray& mapCentre,
                                       const QJsonObject& mapOverlayColours)
{
    const auto aircraft = GlobalObject::navigator()->aircraft();

    QJsonObject document;
    document.insert("v"_L1, Companion::protocolVersion);
    document.insert("app"_L1, QLatin1StringView(ENROUTE_VERSION_STRING));
    document.insert("sid"_L1, static_cast<qint64>(revisions.session));
    document.insert("routeRev"_L1, static_cast<qint64>(revisions.route));

    // Advertised so that a client knows whether the app can serve it a map at all,
    // and knows to fetch a fresh style when the pilot's maps change underneath it.
    if (revisions.map != 0)
    {
        document.insert("mapRev"_L1, static_cast<qint64>(revisions.map));

        // Carried so a client too small for the renderer's own attribution widget
        // can still show the notice the licence requires.
        if (!mapAttribution.isEmpty())
        {
            document.insert("mapAttribution"_L1, mapAttribution);
        }
        if (!mapCentre.isEmpty())
        {
            document.insert("mapCentre"_L1, mapCentre);
        }
        if (!mapOverlayColours.isEmpty())
        {
            document.insert("mapOverlay"_L1, mapOverlayColours);
        }
    }
    document.insert("navPeriodMs"_L1, 1000);
    document.insert("units"_L1, unitsObject(aircraft));
    return document;
}


QJsonObject Companion::Snapshot::route(const Companion::Revisions& revisions)
{
    auto* const flightRoute = GlobalObject::navigator()->flightRoute();
    const auto aircraft = GlobalObject::navigator()->aircraft();

    QJsonObject document;
    document.insert("v"_L1, Companion::protocolVersion);
    document.insert("sid"_L1, static_cast<qint64>(revisions.session));
    document.insert("routeRev"_L1, static_cast<qint64>(revisions.route));
    document.insert("name"_L1, flightRoute->suggestedFilename());
    document.insert("summary"_L1, plainText(flightRoute->summary()));
    document.insert("units"_L1, unitsObject(aircraft));

    QJsonArray waypoints;
    for (const auto& waypoint : flightRoute->waypoints())
    {
        waypoints.append(toJSON(waypoint));
    }
    document.insert("wp"_L1, waypoints);

    QJsonArray legs;
    for (const auto& leg : flightRoute->legs())
    {
        QJsonObject legObject;
        insertIfFinite(legObject, "d"_L1, leg.distance());
        // Leg::TC() is NaN for legs shorter than 100 m, where a course is
        // meaningless. insertIfFinite leaves the member out in that case.
        insertIfFinite(legObject, "tc"_L1, leg.TC());
        legs.append(legObject);
    }
    document.insert("legs"_L1, legs);

    return document;
}


QJsonObject Companion::Snapshot::nav(const Companion::Revisions& revisions,
                                     bool withFormattedStrings)
{
    auto* const navigator = GlobalObject::navigator();
    const auto aircraft = navigator->aircraft();
    const auto info = navigator->remainingRouteInfo();
    const auto positionInfo = GlobalObject::positionProvider()->positionInfo();

    const auto status = Companion::SnapshotAccess::status(info);

    QJsonObject document;
    document.insert("v"_L1, Companion::protocolVersion);
    document.insert("sid"_L1, static_cast<qint64>(revisions.session));
    document.insert("navRev"_L1, static_cast<qint64>(revisions.nav));
    document.insert("routeRev"_L1, static_cast<qint64>(revisions.route));
    document.insert("t"_L1, QDateTime::currentSecsSinceEpoch());
    document.insert("status"_L1, toString(status));
    document.insert("flightStatus"_L1, toString(navigator->flightStatus()));
    document.insert("note"_L1, Companion::SnapshotAccess::note(info));

    // Deliberately not inside "own": a barometer reads without a satellite in sight,
    // so the flight level survives a lost fix and must not be nested under a member
    // that disappears with it.
    //
    // Only sent when the app itself believes the reading. It keeps a plausibility flag
    // on this sensor, and a client showing a flight level the app is distrusting would
    // be claiming more than the app does. When there is a reading and it is distrusted,
    // that is said out loud -- a disbelieved barometer is not the same as none.
    auto* const positionProvider = GlobalObject::positionProvider();
    const auto pressureAltitude = positionProvider->pressureAltitude();
    const auto pressureAltitudeTrusted = pressureAltitude.isFinite()
                                         && !pressureAltitude.isNegative()
                                         && !positionProvider->pressureAltitudeImplausible();
    if (pressureAltitudeTrusted)
    {
        insertIfFinite(document, "pAlt"_L1, pressureAltitude);
    }
    else if (pressureAltitude.isFinite())
    {
        document.insert("pAltImplausible"_L1, true);
    }

    const auto currentLeg = navigator->flightRoute()->currentLeg(positionInfo);
    if (currentLeg >= 0)
    {
        document.insert("leg"_L1, static_cast<qint64>(currentLeg));
    }

    if (positionInfo.isValid())
    {
        QJsonObject own;
        own.insert("c"_L1, toJSON(positionInfo.coordinate()));
        insertIfFinite(own, "alt"_L1, positionInfo.trueAltitudeAMSL());
        insertIfFinite(own, "agl"_L1, positionInfo.trueAltitudeAGL());
        insertIfFinite(own, "gs"_L1, positionInfo.groundSpeed());
        insertIfFinite(own, "tt"_L1, positionInfo.trueTrack());
        insertIfFinite(own, "vs"_L1, positionInfo.verticalSpeed());
        document.insert("own"_L1, own);
    }

    // RemainingRouteInfo guarantees its nextWP members only while OnRoute, so
    // anything else and these are left out rather than sent as plausible-looking
    // stale values.
    const auto onRoute = (status == Navigation::RemainingRouteInfo::OnRoute);

    const auto nextWP = Companion::SnapshotAccess::nextWP(info);
    const auto nextDIST = Companion::SnapshotAccess::nextDIST(info);
    const auto nextETE = Companion::SnapshotAccess::nextETE(info);
    const auto nextETA = Companion::SnapshotAccess::nextETA(info);

    const auto finalWP = Companion::SnapshotAccess::finalWP(info);
    const auto finalDIST = Companion::SnapshotAccess::finalDIST(info);
    const auto finalETE = Companion::SnapshotAccess::finalETE(info);
    const auto finalETA = Companion::SnapshotAccess::finalETA(info);

    if (onRoute)
    {
        QJsonObject next;
        next.insert("n"_L1, nextWP.shortName());
        insertIfFinite(next, "dist"_L1, nextDIST);
        insertIfFinite(next, "ete"_L1, nextETE);
        if (nextETA.isValid() && nextETE.isFinite())
        {
            next.insert("eta"_L1, nextETA.toSecsSinceEpoch());
        }
        insertIfFinite(next, "tc"_L1, Companion::SnapshotAccess::nextTC(info));
        document.insert("next"_L1, next);

        // finalWP is only filled in when it differs from the next waypoint.
        if (finalWP.isValid())
        {
            QJsonObject finalObject;
            finalObject.insert("n"_L1, finalWP.shortName());
            insertIfFinite(finalObject, "dist"_L1, finalDIST);
            insertIfFinite(finalObject, "ete"_L1, finalETE);
            if (finalETA.isValid() && finalETE.isFinite())
            {
                finalObject.insert("eta"_L1, finalETA.toSecsSinceEpoch());
            }
            document.insert("final"_L1, finalObject);
        }
    }

    if (withFormattedStrings)
    {
        QJsonObject formatted;
        formatted.insert("alt"_L1, aircraft.verticalDistanceToString(positionInfo.trueAltitudeAMSL()));
        formatted.insert("gs"_L1, aircraft.horizontalSpeedToString(positionInfo.groundSpeed()));
        formatted.insert("pAlt"_L1,
                         flightLevelToString(pressureAltitude, pressureAltitudeTrusted));
        formatted.insert("statusText"_L1, statusText(status, aircraft));

        if (onRoute)
        {
            formatted.insert("nextName"_L1, nextWP.shortName());
            formatted.insert("nextDist"_L1, aircraft.horizontalDistanceToString(nextDIST));
            formatted.insert("nextETE"_L1, nextETE.toHoursAndMinutes());
            formatted.insert("nextETA"_L1, info.nextWP_ETAAsUTCString());
            formatted.insert("nextTC"_L1, courseToString(Companion::SnapshotAccess::nextTC(info)));

            if (finalWP.isValid())
            {
                formatted.insert("finalName"_L1, finalWP.shortName());
                formatted.insert("finalDist"_L1, aircraft.horizontalDistanceToString(finalDIST));
                formatted.insert("finalETE"_L1, finalETE.toHoursAndMinutes());
                formatted.insert("finalETA"_L1, info.finalWP_ETAAsUTCString());
            }
        }
        document.insert("fmt"_L1, formatted);
    }

    return document;
}

QJsonObject Companion::Snapshot::notams(const Companion::Revisions& revisions)
{
    auto* const provider = GlobalObject::notamProvider();
    const auto waypoints = GlobalObject::navigator()->flightRoute()->waypoints();

    QJsonObject document;
    document.insert("v"_L1, Companion::protocolVersion);
    document.insert("sid"_L1, static_cast<qint64>(revisions.session));

    // The app's own warning that its NOTAM data is not current. Already
    // translated into every language the app ships, because NOTAMProvider marks
    // it with tr(), and empty when there is nothing to warn about.
    const auto warning = provider->status();
    if (!warning.isEmpty())
    {
        document.insert("warning"_L1, warning);
    }

    // What the filter below does and, more importantly, what it does not do. A
    // client must be able to tell the pilot that this is not an airspace
    // clearance, and it can only do that if the limits travel with the data.
    QJsonObject filter;
    filter.insert("radius"_L1, qRound(NOTAM::NOTAMList::restrictionRadius.toM()));
    filter.insert("horizontalOnly"_L1, true);
    filter.insert("flightLevelApplied"_L1, false);
    document.insert("filter"_L1, filter);

    QJsonArray groups;
    int total = 0;
    int dropped = 0;
    QDateTime oldestRetrieval;

    for (qsizetype index = 0; index < waypoints.size(); ++index)
    {
        const auto& waypoint = waypoints.at(index);

        // Reading the provider is not what causes NOTAMs to be downloaded: it
        // fetches the whole flight route by itself, at start, on every route
        // change, on every coarse position change and once an hour. So this loop
        // sees data the app already holds and adds no network traffic. Should a
        // waypoint be uncovered anyway, notams() starts a request and returns an
        // empty list, exactly as it does for the app's own NOTAM page.
        const auto list = provider->notams(waypoint);
        const auto entriesForWaypoint = list.notams();

        QJsonObject group;
        group.insert("wp"_L1, static_cast<qint64>(index));
        group.insert("n"_L1, waypoint.shortName());

        // The distinction a client must not lose: a list that was retrieved and
        // is empty means there are no NOTAMs here, while a list that was never
        // retrieved means nothing is known yet. Showing the second as the first
        // would be an assurance the app never gave.
        group.insert("data"_L1, list.isValid());
        if (list.isValid())
        {
            group.insert("retrieved"_L1, list.retrieved().toUTC().toString(Qt::ISODate));
            if (!oldestRetrieval.isValid() || list.retrieved() < oldestRetrieval)
            {
                oldestRetrieval = list.retrieved();
            }
        }

        QJsonArray entries;
        for (const auto& notam : entriesForWaypoint)
        {
            if (total >= Companion::maximumNotams)
            {
                break;
            }
            entries.append(toJSON(notam));
            ++total;
        }
        if (!entries.isEmpty())
        {
            group.insert("notams"_L1, entries);
        }

        // Said per group and not only once for the document, because otherwise a
        // group that the cap emptied would be indistinguishable from a waypoint
        // that genuinely has no NOTAMs -- which is the one confusion this
        // document must not create. A client may read an absent notams member as
        // "none here" only when cut is absent as well.
        const auto cut = entriesForWaypoint.size() - entries.size();
        if (cut > 0)
        {
            group.insert("cut"_L1, static_cast<qint64>(cut));
            dropped += static_cast<int>(cut);
        }

        groups.append(group);
    }

    document.insert("groups"_L1, groups);
    document.insert("n"_L1, total);

    if (dropped > 0)
    {
        document.insert("dropped"_L1, dropped);
    }

    // The oldest retrieval among the groups, not the newest: a document is only
    // as current as its stalest part, and a client showing an age should show the
    // pessimistic one.
    if (oldestRetrieval.isValid())
    {
        document.insert("retrieved"_L1, oldestRetrieval.toUTC().toString(Qt::ISODate));
    }

    return document;
}


QJsonObject Companion::Snapshot::weather(const Companion::Revisions& revisions,
                                         Weather::ObserverList* observers)
{
    const auto aircraft = GlobalObject::navigator()->aircraft();
    const auto now = QDateTime::currentDateTimeUtc();
    const auto here = Positioning::PositionProvider::lastValidCoordinate();

    QJsonObject document;
    document.insert("v"_L1, Companion::protocolVersion);
    document.insert("sid"_L1, static_cast<qint64>(revisions.session));

    auto* const provider = GlobalObject::weatherDataProvider();

    // Both are already composed sentences carrying the pilot's units, and both can
    // contain markup, so they go through the same stripper the route summary uses.
    const auto qnh = plainText(provider->QNHInfo());
    if (!qnh.isEmpty())
    {
        document.insert("qnh"_L1, qnh);
    }
    const auto sun = plainText(provider->sunInfo());
    if (!sun.isEmpty())
    {
        document.insert("sun"_L1, sun);
    }
    document.insert("downloading"_L1, provider->downloading());

    QJsonArray stations;
    if (observers != nullptr)
    {
        const auto list = observers->observers();
        for (auto* const observer : list)
        {
            if (observer == nullptr)
            {
                continue;
            }

            const auto metar = observer->metar();
            const auto taf = observer->taf();
            if (!metar.isValid() && !taf.isValid())
            {
                continue;
            }

            QJsonObject station;
            station.insert("wp"_L1, toJSON(observer->waypoint()));

            // The bearing and distance line the app puts under a station name.
            // Empty while the position is unknown, which is why it is conditional.
            const auto way = plainText(
                aircraft.describeWay(here, observer->waypoint().coordinate()));
            if (!way.isEmpty())
            {
                station.insert("way"_L1, way);
            }

            if (metar.isValid())
            {
                QJsonObject encoded;
                encoded.insert("raw"_L1, metar.rawText());
                encoded.insert("sum"_L1, plainText(metar.summary(aircraft, now)));

                // The category and the app's own colour for it. A client must not
                // derive the colour from the category itself: the app maps five
                // categories onto its own palette, and a watch inventing a second
                // mapping would show a different colour for the same weather.
                const auto category = QMetaEnum::fromType<Weather::METAR::FlightCategory>()
                                          .valueToKey(metar.flightCategory());
                if (category != nullptr)
                {
                    encoded.insert("cat"_L1, QLatin1StringView(category));
                }
                encoded.insert("col"_L1, metar.flightCategoryColor());

                if (metar.observationTime().isValid())
                {
                    encoded.insert("obs"_L1, metar.observationTime().toUTC().toString(Qt::ISODate));
                }
                station.insert("metar"_L1, encoded);
            }

            if (taf.isValid())
            {
                QJsonObject encoded;
                encoded.insert("raw"_L1, taf.rawText());
                if (taf.issueTime().isValid())
                {
                    encoded.insert("iss"_L1, taf.issueTime().toUTC().toString(Qt::ISODate));
                }
                station.insert("taf"_L1, encoded);
            }

            stations.append(station);
        }
    }
    document.insert("st"_L1, stations);

    return document;
}
