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

#include <QPointer>
#include <QProperty>
#include <QQmlEngine>
#include <QTimer>

#include <vector>

#include "GlobalObject.h"
#include "companion/Snapshot.h"

namespace GeoMaps
{
    class VACLibrary;
} // namespace GeoMaps

namespace Companion
{

    class HttpTransport;
    class MapAssets;

    /*! \brief Publishes route and navigation state to companion devices
     *
     *  This class watches the flight route, the remaining route info and the
     *  position, encodes them into the documents described in
     *  doc/companion-protocol.md, and hands the encoded bytes to a transport.
     *
     *  It is entirely inert until the feature is switched on in the settings. While
     *  disabled it holds a single connection to GlobalSettings, installs no
     *  observers, runs no timers and opens no socket, so a user who never enables
     *  it pays nothing.
     *
     *  Encoding happens at most once per second no matter how fast position
     *  updates arrive, and only when something actually changed. One encoding
     *  serves every connected client.
     */

    class CompanionServer : public GlobalObject
    {
        Q_OBJECT
        QML_ELEMENT
        QML_SINGLETON

    public:
        /*! \brief Standard constructor
         *
         *  @param parent The standard QObject parent pointer
         */
        explicit CompanionServer(QObject* parent = nullptr);

        // deferred initialization
        void deferredInitialization() override;

        // No default constructor, important for QML singleton
        explicit CompanionServer() = delete;

        /*! \brief Standard destructor */
        ~CompanionServer() override = default;

        /*! \brief Factory function for QML singleton */
        static Companion::CompanionServer* create(QQmlEngine* /*unused*/, QJSEngine* /*unused*/)
        {
            return GlobalObject::companionServer();
        }


        //
        // Properties
        //

        /*! \brief Six-digit code that a client must present
         *
         *  Generated when the feature is first enabled and stored in the settings.
         *  It is the only thing standing between a device on the same network and
         *  the pilot's live position, so it is shown in the user interface and can
         *  be regenerated there.
         */
        Q_PROPERTY(QString pairingCode READ pairingCode NOTIFY pairingCodeChanged)

        /*! \brief URLs under which the server is presently reachable
         *
         *  One entry per non-loopback network interface, in the form
         *  "http://192.168.1.42:8973". Empty while the feature is disabled.
         */
        Q_PROPERTY(QStringList serverUrls READ serverUrls NOTIFY serverUrlsChanged)

        /*! \brief Human-readable, translated description of the current state */
        Q_PROPERTY(QString statusString READ statusString NOTIFY statusStringChanged)

        /*! \brief Human-readable, translated error message, empty if no error */
        Q_PROPERTY(QString errorString READ errorString NOTIFY errorStringChanged)


        //
        // Getter Methods
        //

        /*! \brief Getter function for the property with the same name
         *
         *  @returns Property pairingCode
         */
        [[nodiscard]] QString pairingCode() const;

        /*! \brief Getter function for the property with the same name
         *
         *  @returns Property serverUrls
         */
        [[nodiscard]] QStringList serverUrls() const;

        /*! \brief Getter function for the property with the same name
         *
         *  @returns Property statusString
         */
        [[nodiscard]] QString statusString() const;

        /*! \brief Getter function for the property with the same name
         *
         *  @returns Property errorString
         */
        [[nodiscard]] QString errorString() const {return m_errorString;}


        //
        // Methods used by transports
        //

        /*! \brief Cached capability document
         *
         *  @returns The encoded document, or an empty array while disabled
         */
        [[nodiscard]] QByteArray helloDocument() const {return m_helloDocument;}

        /*! \brief Cached route snapshot
         *
         *  @returns The encoded document, or an empty array while disabled
         */
        [[nodiscard]] QByteArray routeDocument() const {return m_routeDocument;}

        /*! \brief Cached navigation frame, including formatted strings
         *
         *  @returns The encoded document, or an empty array while disabled
         */
        [[nodiscard]] QByteArray navDocument() const {return m_navDocument;}

        /*! \brief Cached navigation frame, without formatted strings
         *
         *  @returns The encoded document, or an empty array while disabled
         */
        [[nodiscard]] QByteArray navDocumentCompact() const {return m_navDocumentCompact;}

        /*! \brief Encoded NOTAM document, or empty while the feature is off */
        [[nodiscard]] QByteArray notamDocument() const {return m_notamDocument;}

        /*! \brief Encoded weather document, or empty while the feature is off */
        [[nodiscard]] QByteArray weatherDocument() const {return m_weatherDocument;}

        /*! \brief Encoded approach chart document, or empty while the feature is off */
        [[nodiscard]] QByteArray vacDocument() const {return m_vacDocument;}

        /*! \brief Encoded flight log document, or empty while the feature is off */
        [[nodiscard]] QByteArray logDocument() const {return m_logDocument;}

        /*! \brief Encoded traffic document, or empty while the feature is off */
        [[nodiscard]] QByteArray trafficDocument() const {return m_trafficDocument;}

        /*! \brief Encoded nearby waypoint document, or empty while off */
        [[nodiscard]] QByteArray nearbyDocument() const {return m_nearbyDocument;}

        /*! \brief Hands over the QML engine
         *
         *  GeoMaps::VACLibrary is a QML singleton, so an engine is the only way to
         *  reach the pilot's approach charts from C++. Called once from main(),
         *  before any client can connect.
         *
         *  @param engine The application's QML engine
         */
        void setQmlEngine(QQmlEngine* engine);

        /*! \brief The pilot's own map, for a client that renders one itself
         *
         *  Defined out of line, because QPointer needs a complete type and pulling
         *  MapAssets.h in here would drag the MBTiles reader into every translation
         *  unit that only wants to talk to this server.
         *
         *  Null while the feature is off. Owned here rather than by the transport,
         *  because it holds open SQLite handles on the map files and must therefore
         *  be created and destroyed with the session and not with a request.
         *
         *  @returns The map asset server, or nullptr
         */
        [[nodiscard]] Companion::MapAssets* mapAssets() const;

        /*! \brief Current document revisions */
        [[nodiscard]] Companion::Revisions revisions() const {return m_revisions;}

        /*! \brief Compares a candidate against the pairing code
         *
         *  The comparison takes the same time whether or not the code is right, so
         *  that a client cannot learn the code one digit at a time.
         *
         *  @param candidate The code presented by a client
         *
         *  @returns True if the candidate matches
         */
        [[nodiscard]] bool checkPairingCode(QByteArrayView candidate) const;


    public slots:
        /*! \brief Generates a new pairing code, invalidating the old one */
        void regeneratePairingCode();


    signals:
        /*! \brief Notification signal for the property with the same name */
        void pairingCodeChanged();

        /*! \brief Notification signal for the property with the same name */
        void serverUrlsChanged();

        /*! \brief Notification signal for the property with the same name */
        void statusStringChanged();

        /*! \brief Notification signal for the property with the same name */
        void errorStringChanged();

        /*! \brief Emitted after routeDocument() has been updated */
        void routeDocumentChanged();

        /*! \brief Emitted after navDocument() has been updated */
        void navDocumentChanged();

        /*! \brief Emitted after notamDocument() has been updated */
        void notamDocumentChanged();

        /*! \brief Emitted after weatherDocument() has been updated */
        void weatherDocumentChanged();

        /*! \brief Emitted after vacDocument() has been updated */
        void vacDocumentChanged();

        /*! \brief Emitted after logDocument() has been updated */
        void logDocumentChanged();

        /*! \brief Emitted after trafficDocument() has been updated */
        void trafficDocumentChanged();

        /*! \brief Emitted after nearbyDocument() has been updated */
        void nearbyDocumentChanged();


    private slots:
        // Marks the navigation frame as needing re-encoding. Cheap on purpose: the
        // actual work happens in publishNav(), at most once per second.
        void markNavDirty() {m_navDirty = true;}

        // Coalesces bursts of waypointsChanged. Importing a route emits that signal
        // once per waypoint, and re-encoding a hundred-waypoint route each time
        // would be wasteful.
        void markRouteDirty();

        void publishNav();
        void publishRoute();

        // Rebuilds the NOTAM document and, if its content actually moved,
        // increments the NOTAM revision. Cheap enough to call speculatively,
        // which is why there is no dirty flag for it.
        void publishNotams();

        void markNotamsDirty();

        // Same shape as publishNotams(): rebuild, compare, and only then move the
        // revision. Weather arrives in bursts -- one download updates every station
        // at once -- so the comparison saves a client a great many identical
        // refetches.
        void publishWeather();

        void markWeatherDirty();

        void publishVacs();

        void markVacsDirty();

        void publishFlightLog();

        void markFlightLogDirty();

        // Unlike the other publishers this one does not compare before publishing.
        // A client has to be able to tell "no traffic" from "no data", and the only
        // thing that separates them is that frames keep arriving.
        void publishTraffic();

        void markTrafficDirty();

        void publishNearby();

        // Creates or destroys the transport in response to the settings.
        void updateTransport();

    private:
        Q_DISABLE_COPY_MOVE(CompanionServer)

        // Installs the observers on Navigator and PositionProvider. Called on the
        // first enable rather than from deferredInitialization(), so that a
        // disabled feature really does no work.
        void attachToDataSources();
        void detachFromDataSources();

        void setErrorString(const QString& newErrorString = {});

        Companion::Revisions m_revisions;

        QByteArray m_helloDocument;
        QByteArray m_routeDocument;
        QByteArray m_navDocument;
        QByteArray m_navDocumentCompact;
        QByteArray m_notamDocument;
        QByteArray m_weatherDocument;
        QByteArray m_vacDocument;
        QByteArray m_logDocument;
        QByteArray m_trafficDocument;
        QByteArray m_nearbyDocument;

        // The NOTAM document with its revision member left out, kept so that a
        // rebuild can tell whether anything changed. An exact comparison and not
        // a hash: a collision would mean the watch keeps showing NOTAMs that have
        // been superseded, and that is not a trade worth a few kilobytes.
        QByteArray m_notamFingerprint;
        QByteArray m_weatherFingerprint;
        QByteArray m_vacFingerprint;
        QByteArray m_logFingerprint;
        QByteArray m_nearbyFingerprint;

        bool m_navDirty {false};

        QTimer m_navTimer;
        QTimer m_navKeepAliveTimer;
        QTimer m_routeTimer;

        // NOTAMs are refreshed on a slow beat as well as on change, because
        // whether a NOTAM is current depends on the wall clock and not only on
        // the data: the app's own section headings move a NOTAM from "Next 24h"
        // to "Current" with no download involved.
        QTimer m_notamTimer;
        QTimer m_notamCoalesceTimer;

        // Weather ages on the wall clock as much as on new data: a METAR expires,
        // and the summary the app writes says how old the observation is. So this
        // one has a slow beat of its own too.
        QTimer m_weatherTimer;
        QTimer m_weatherCoalesceTimer;

        // No coalescing partner: the library emits one signal per import, not a
        // burst, and an import is a deliberate act rather than a data feed.
        QTimer m_vacTimer;

        // The flight log changes when a flight starts or ends, and the detector's
        // state changes a handful of times per flight. Coalesced, because a takeoff
        // moves the state and adds an entry within the same second.
        QTimer m_logCoalesceTimer;

        // Traffic is encoded on a beat rather than on change: a receiver reporting
        // eight aircraft moves something every few hundred milliseconds, and encoding
        // each of those would be a frame nobody reads. One per second matches the
        // navigation frame, which is the rate a client polls at anyway.
        QTimer m_trafficTimer;

        // The nearby list moves with the aircraft, so it is rebuilt on a beat rather
        // than on change: every position update would otherwise change every distance
        // line in it and republish sixty waypoints once a second.
        QTimer m_nearbyTimer;

        // The app's own station list, sorted by distance. Owned here rather than
        // created per request, because it caches one Weather::Observer per station
        // and its bindings are what keep that list current.
        QPointer<Weather::ObserverList> m_observers;

        // Resolved from the engine on first use rather than at start, so that a pilot
        // who never enables the companion never causes the chart library -- and the
        // file maintenance its constructor schedules -- to be built any earlier than
        // the app would have built it anyway.
        [[nodiscard]] GeoMaps::VACLibrary* vacLibrary();

        QPointer<QQmlEngine> m_qmlEngine;
        QPointer<GeoMaps::VACLibrary> m_vacLibrary;

        // Held for as long as the feature is enabled. RemainingRouteInfo has no
        // notification signal, so it can only be watched as a bindable property.
        std::vector<QPropertyNotifier> m_notifiers;

        QPointer<Companion::HttpTransport> m_httpTransport;
        QPointer<Companion::MapAssets> m_mapAssets;

        QString m_errorString;
    };

} // namespace Companion
