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

#include <chrono>

#include <QJsonArray>
#include <QJsonDocument>
#include <QNetworkInterface>
#include <QRandomGenerator>

#include "GlobalSettings.h"
#include "companion/CompanionServer.h"
#include "companion/HttpTransport.h"
#include "companion/MapAssets.h"
#include "companion/Protocol.h"
#include "navigation/Navigator.h"
#include "notam/NOTAMProvider.h"
#include "dataManagement/DataManager.h"
#include "positioning/PositionProvider.h"

using namespace Qt::Literals::StringLiterals;

namespace
{
    // Position updates arrive at satnav rate, which is one per second from the
    // built-in receiver but can be several per second from a traffic data receiver.
    // Encoding is therefore throttled to this period, and skipped entirely when
    // nothing changed.
    constexpr auto navPeriod = std::chrono::milliseconds(1000);

    // A client on Bluetooth cannot poll, so a frame is published this often even
    // when the aircraft is parked. It is how such a client tells a live link from a
    // dead one.
    constexpr auto navKeepAlivePeriod = std::chrono::seconds(10);

    // Importing a flight plan emits waypointsChanged once per waypoint.
    constexpr auto routeCoalescePeriod = std::chrono::milliseconds(250);

    // NOTAMs arrive from the network every few hours at most, so this beat is not
    // about catching new data -- a notifier does that. It exists because whether a
    // NOTAM is in force depends on the wall clock, so the document goes stale on
    // its own with nothing having happened.
    constexpr auto notamPeriod = std::chrono::minutes(5);

    // Both triggers for a NOTAM rebuild fire in bursts: a route import emits
    // waypointsChanged repeatedly, and a batch of downloads finishing moves
    // lastUpdate once per reply.
    constexpr auto notamCoalescePeriod = std::chrono::seconds(2);

    constexpr int pairingCodeDigits = 6;
    constexpr quint32 pairingCodeModulus = 1000000;
} // namespace


Companion::CompanionServer::CompanionServer(QObject* parent)
    : GlobalObject(parent)
{
    // GlobalObject forbids calling its static accessors from a constructor, so
    // nothing here may touch Navigator, PositionProvider or GlobalSettings. The
    // wiring happens in deferredInitialization() and updateTransport().

    m_revisions.session = QRandomGenerator::global()->generate();

    m_navTimer.setInterval(navPeriod);
    m_navTimer.setTimerType(Qt::CoarseTimer);
    connect(&m_navTimer, &QTimer::timeout, this, &Companion::CompanionServer::publishNav);

    m_navKeepAliveTimer.setInterval(navKeepAlivePeriod);
    m_navKeepAliveTimer.setTimerType(Qt::CoarseTimer);
    connect(&m_navKeepAliveTimer, &QTimer::timeout, this, &Companion::CompanionServer::markNavDirty);

    m_routeTimer.setInterval(routeCoalescePeriod);
    m_routeTimer.setSingleShot(true);
    connect(&m_routeTimer, &QTimer::timeout, this, &Companion::CompanionServer::publishRoute);

    m_notamTimer.setInterval(notamPeriod);
    m_notamTimer.setTimerType(Qt::VeryCoarseTimer);
    connect(&m_notamTimer, &QTimer::timeout, this, &Companion::CompanionServer::publishNotams);

    m_notamCoalesceTimer.setInterval(notamCoalescePeriod);
    m_notamCoalesceTimer.setSingleShot(true);
    connect(&m_notamCoalesceTimer, &QTimer::timeout, this, &Companion::CompanionServer::publishNotams);
}


void Companion::CompanionServer::deferredInitialization()
{
    connect(GlobalObject::globalSettings(), &GlobalSettings::companionNetworkEnabledChanged,
            this, &Companion::CompanionServer::updateTransport);

    updateTransport();
}


//
// Getter Methods
//

QString Companion::CompanionServer::pairingCode() const
{
    return GlobalObject::globalSettings()->companionPairingCode();
}


QStringList Companion::CompanionServer::serverUrls() const
{
    if (m_httpTransport.isNull())
    {
        return {};
    }

    QStringList result;
    const auto addresses = QNetworkInterface::allAddresses();
    for (const auto& address : addresses)
    {
        if (address.isLoopback() || address.protocol() != QAbstractSocket::IPv4Protocol)
        {
            continue;
        }
        result += u"http://%1:%2"_s.arg(address.toString()).arg(Companion::defaultPort);
    }
    return result;
}


QString Companion::CompanionServer::statusString() const
{
    if (!GlobalObject::globalSettings()->companionNetworkEnabled())
    {
        return tr("Off");
    }
    if (!m_errorString.isEmpty())
    {
        return m_errorString;
    }
    if (m_httpTransport.isNull())
    {
        return tr("Starting…");
    }
    return tr("Waiting for companion devices");
}


bool Companion::CompanionServer::checkPairingCode(QByteArrayView candidate) const
{
    const auto expected = pairingCode().toLatin1();

    // Comparing lengths first leaks only the length, which is fixed and public.
    // The digits themselves are compared without an early exit, so that the time
    // taken does not reveal how many of them were right.
    if (candidate.size() != expected.size() || expected.isEmpty())
    {
        return false;
    }

    unsigned char difference = 0;
    for (qsizetype i = 0; i < expected.size(); ++i)
    {
        difference |= static_cast<unsigned char>(candidate.at(i) ^ expected.at(i));
    }
    return difference == 0;
}


//
// Public Slots
//

void Companion::CompanionServer::regeneratePairingCode()
{
    const auto code = QRandomGenerator::system()->bounded(pairingCodeModulus);
    GlobalObject::globalSettings()->setCompanionPairingCode(
        u"%1"_s.arg(code, pairingCodeDigits, 10, QChar(u'0')));
    emit pairingCodeChanged();
}


//
// Private Slots
//

void Companion::CompanionServer::markRouteDirty()
{
    m_routeTimer.start();

    // A changed route also changes the remaining route info, and a changed set of
    // unit preferences changes every formatted string in the frame.
    markNavDirty();
}


void Companion::CompanionServer::publishNav()
{
    if (!m_navDirty)
    {
        return;
    }
    m_navDirty = false;

    m_revisions.nav++;
    m_navDocument = QJsonDocument(Companion::Snapshot::nav(m_revisions, true))
                        .toJson(QJsonDocument::Compact);
    m_navDocumentCompact = QJsonDocument(Companion::Snapshot::nav(m_revisions, false))
                               .toJson(QJsonDocument::Compact);

    emit navDocumentChanged();
}


void Companion::CompanionServer::publishRoute()
{
    m_revisions.route++;
    m_routeDocument = QJsonDocument(Companion::Snapshot::route(m_revisions))
                          .toJson(QJsonDocument::Compact);
    const auto attribution = m_mapAssets.isNull() ? QString() : m_mapAssets->attribution();
    const auto centre = m_mapAssets.isNull() ? QJsonArray() : m_mapAssets->centreHint();
    m_helloDocument = QJsonDocument(Companion::Snapshot::hello(m_revisions, attribution, centre))
                          .toJson(QJsonDocument::Compact);

    emit routeDocumentChanged();
}


Companion::MapAssets* Companion::CompanionServer::mapAssets() const
{
    return m_mapAssets;
}


void Companion::CompanionServer::publishNotams()
{
    // The encoder leaves notamRev out, which is what makes this comparison
    // possible: a document carrying its own revision would differ on every
    // rebuild and every rebuild would look like a change.
    auto document = Companion::Snapshot::notams(m_revisions);

    const auto fingerprint = QJsonDocument(document).toJson(QJsonDocument::Compact);
    if (fingerprint == m_notamFingerprint)
    {
        return;
    }
    m_notamFingerprint = fingerprint;

    m_revisions.notam++;
    document.insert("notamRev"_L1, static_cast<qint64>(m_revisions.notam));
    m_notamDocument = QJsonDocument(document).toJson(QJsonDocument::Compact);

    emit notamDocumentChanged();
}


void Companion::CompanionServer::markNotamsDirty()
{
    if (!m_notamCoalesceTimer.isActive())
    {
        m_notamCoalesceTimer.start();
    }
}


void Companion::CompanionServer::updateTransport()
{
    const auto enabled = GlobalObject::globalSettings()->companionNetworkEnabled();

    if (!enabled)
    {
        if (!m_httpTransport.isNull())
        {
            delete m_httpTransport;
        }
        // Before detaching, because it holds SQLite handles on every downloaded map
        // file and there is no reason to keep those open for a disabled feature.
        if (!m_mapAssets.isNull())
        {
            delete m_mapAssets;
        }
        detachFromDataSources();

        m_navTimer.stop();
        m_navKeepAliveTimer.stop();
        m_routeTimer.stop();
        m_notamTimer.stop();
        m_notamCoalesceTimer.stop();

        m_helloDocument.clear();
        m_routeDocument.clear();
        m_navDocument.clear();
        m_navDocumentCompact.clear();
        m_notamDocument.clear();
        m_notamFingerprint.clear();

        setErrorString();
        emit serverUrlsChanged();
        emit statusStringChanged();
        return;
    }

    // A code is needed before anything starts listening.
    if (pairingCode().size() != pairingCodeDigits)
    {
        regeneratePairingCode();
    }

    attachToDataSources();

    if (m_mapAssets.isNull())
    {
        m_mapAssets = new Companion::MapAssets(this);

        // The style's tile URLs carry this, so a client that sees it move knows the
        // style it holds is stale. publishRoute() is what rebuilds the capability
        // document that carries it.
        connect(m_mapAssets, &Companion::MapAssets::revisionChanged, this, [this]()
        {
            m_revisions.map = m_mapAssets->revision();
            markRouteDirty();
        });

        // Deferred to the next turn of the event loop. Reading the data manager
        // opens an SQLite handle on every downloaded map file, and doing that while
        // the app is still assembling itself buys nothing: no client can have
        // connected yet. The capability document picks the revision up when the
        // notifier fires, a moment later.
        QTimer::singleShot(0, m_mapAssets, [this]()
        {
            m_mapAssets->refresh();

            // The same signals GeoMapProvider watches, so that a map the pilot
            // downloads mid-flight reaches a companion device without a restart --
            // and so that the revision moves, which is what invalidates the
            // client's tile cache.
            connect(GlobalObject::dataManager()->baseMapsVector(),
                    &DataManagement::Downloadable_Abstract::filesChanged,
                    m_mapAssets, &Companion::MapAssets::refresh);
            connect(GlobalObject::dataManager()->terrainMaps(),
                    &DataManagement::Downloadable_Abstract::filesChanged,
                    m_mapAssets, &Companion::MapAssets::refresh);
        });
    }

    // Encode once up front, so that a client connecting immediately gets data
    // rather than an empty document. The map revision is not in this first one,
    // because reading the map files is deferred; it arrives with the next.
    publishRoute();
    m_navDirty = true;
    publishNav();

    publishNotams();

    m_navTimer.start();
    m_navKeepAliveTimer.start();
    m_notamTimer.start();

    if (m_httpTransport.isNull())
    {
        m_httpTransport = new Companion::HttpTransport(this, this);
        connect(m_httpTransport, &Companion::HttpTransport::errorStringChanged, this,
                [this]() {setErrorString(m_httpTransport.isNull() ? QString() : m_httpTransport->errorString());});
        m_httpTransport->start();
    }

    emit serverUrlsChanged();
    emit statusStringChanged();
}


//
// Private Methods
//

void Companion::CompanionServer::attachToDataSources()
{
    if (!m_notifiers.empty())
    {
        return;
    }

    auto* const navigator = GlobalObject::navigator();

    // RemainingRouteInfo is exposed as a bindable property with no notification
    // signal, so a notifier is the only way to observe it. The idiom is the same
    // one navigation/BaroCache.cpp uses.
    m_notifiers.push_back(navigator->bindableRemainingRouteInfo().addNotifier(
        [this]() {markNavDirty();}));
    m_notifiers.push_back(GlobalObject::positionProvider()->bindablePositionInfo().addNotifier(
        [this]() {markNavDirty();}));

    connect(navigator, &Navigation::Navigator::flightStatusChanged,
            this, &Companion::CompanionServer::markNavDirty);
    connect(navigator, &Navigation::Navigator::aircraftChanged,
            this, &Companion::CompanionServer::markRouteDirty);
    connect(navigator->flightRoute(), &Navigation::FlightRoute::waypointsChanged,
            this, &Companion::CompanionServer::markRouteDirty);
    connect(navigator->flightRoute(), &Navigation::FlightRoute::summaryChanged,
            this, &Companion::CompanionServer::markRouteDirty);

    // lastUpdate moves whenever NOTAM data lands, and unlike geoJSON it is cheap
    // to compute -- a maximum over the retrieval timestamps rather than a whole
    // feature collection. A notifier makes a bound property evaluate eagerly, so
    // which of the two is observed here is not a matter of taste.
    m_notifiers.push_back(GlobalObject::notamProvider()->bindableLastUpdate().addNotifier(
        [this]() {markNotamsDirty();}));

    // A changed route means different waypoints to ask about. markRouteDirty
    // deliberately does not imply this, so that a route edit does not rebuild a
    // document a client polls once a minute.
    connect(navigator->flightRoute(), &Navigation::FlightRoute::waypointsChanged,
            this, &Companion::CompanionServer::markNotamsDirty);
}


void Companion::CompanionServer::detachFromDataSources()
{
    m_notifiers.clear();

    if (!GlobalObject::canConstruct())
    {
        return;
    }
    auto* const navigator = GlobalObject::navigator();
    disconnect(navigator, nullptr, this, nullptr);
    disconnect(navigator->flightRoute(), nullptr, this, nullptr);
}


void Companion::CompanionServer::setErrorString(const QString& newErrorString)
{
    if (newErrorString == m_errorString)
    {
        return;
    }
    m_errorString = newErrorString;
    emit errorStringChanged();
    emit statusStringChanged();
}
