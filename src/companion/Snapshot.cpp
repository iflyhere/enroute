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
#include <QRegularExpression>

#include "GlobalObject.h"
#include "companion/Protocol.h"
#include "companion/Snapshot.h"
#include "config.h"
#include "navigation/Leg.h"
#include "navigation/Navigator.h"
#include "positioning/PositionProvider.h"

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

} // namespace


QJsonObject Companion::Snapshot::hello(const Companion::Revisions& revisions)
{
    const auto aircraft = GlobalObject::navigator()->aircraft();

    QJsonObject document;
    document.insert("v"_L1, Companion::protocolVersion);
    document.insert("app"_L1, QLatin1StringView(ENROUTE_VERSION_STRING));
    document.insert("sid"_L1, static_cast<qint64>(revisions.session));
    document.insert("routeRev"_L1, static_cast<qint64>(revisions.route));
    document.insert("navRev"_L1, static_cast<qint64>(revisions.nav));
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
