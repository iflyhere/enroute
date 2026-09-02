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

namespace Companion
{

    class HttpTransport;

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

        bool m_navDirty {false};

        QTimer m_navTimer;
        QTimer m_navKeepAliveTimer;
        QTimer m_routeTimer;

        // Held for as long as the feature is enabled. RemainingRouteInfo has no
        // notification signal, so it can only be watched as a bindable property.
        std::vector<QPropertyNotifier> m_notifiers;

        QPointer<Companion::HttpTransport> m_httpTransport;

        QString m_errorString;
    };

} // namespace Companion
