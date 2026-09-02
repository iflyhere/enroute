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

#include <QAbstractHttpServer>
#include <QDateTime>
#include <QHash>
#include <QPointer>
#include <QTimer>
#include <QUdpSocket>

namespace Companion
{

    class CompanionServer;

    /*! \brief Serves the companion protocol over HTTP on the local network
     *
     *  This is stage one of the two transports described in
     *  doc/companion-protocol.md. It is the transport that can be developed and
     *  tested on a desk, and it is useful in its own right for any client on the
     *  same network, but it is not the transport for use in the air: Wear OS keeps
     *  Wi-Fi powered down while its Bluetooth link to the phone is up.
     *
     *  A deliberate second server rather than an extension of GeoMaps::TileServer.
     *  That one answers any path that exists in the resource system, which is
     *  perfectly safe on loopback and would be quite wrong on a network; and it
     *  starts listening in its constructor, whereas this has to start and stop with
     *  a setting.
     *
     *  Because this publishes the aircraft's live position, every request must
     *  carry the pairing code, requests from globally routable addresses are
     *  refused outright, and repeated failures lock out the peer for a while.
     */

    class HttpTransport : public QAbstractHttpServer
    {
        Q_OBJECT

    public:
        /*! \brief Creates a transport
         *
         *  @param server The server that holds the documents and the pairing code
         *
         *  @param parent The standard QObject parent pointer
         */
        explicit HttpTransport(Companion::CompanionServer* server, QObject* parent = nullptr);

        /*! \brief Standard destructor */
        ~HttpTransport() override;


        //
        // Properties
        //

        /*! \brief Human-readable, translated error message, empty if no error */
        Q_PROPERTY(QString errorString READ errorString NOTIFY errorStringChanged)


        //
        // Getter Methods
        //

        /*! \brief Getter function for the property with the same name
         *
         *  @returns Property errorString
         */
        [[nodiscard]] QString errorString() const {return m_errorString;}


    public slots:
        /*! \brief Starts listening
         *
         *  On failure the transport stays inactive and errorString describes why.
         */
        void start();

        /*! \brief Stops listening and closes all connections */
        void stop();


    signals:
        /*! \brief Notification signal for the property with the same name */
        void errorStringChanged();


    private:
        Q_DISABLE_COPY_MOVE(HttpTransport)

        // Implemented pure virtual method from QAbstractHttpServer
        bool handleRequest(const QHttpServerRequest& request, QHttpServerResponder& responder) override;

        // Implemented pure virtual method from QAbstractHttpServer
        void missingHandler(const QHttpServerRequest& request, QHttpServerResponder& responder) override;

        // Advertises where this server is listening, so that a client does not have
        // to be told an address by hand. Deliberately carries no pairing code: a
        // client learns the address from the datagram and is paired with the code
        // the pilot read off the screen once.
        void broadcast();

        // Checks the pairing code and the brute-force lockout. Returns false and
        // records a failure if the request may not be answered.
        bool authorized(const QHttpServerRequest& request);

        void setErrorString(const QString& newErrorString = {});

        QPointer<Companion::CompanionServer> m_server;

        // Peer address to failure count and time of the first failure in the
        // current window. Without this, a six-digit code falls to brute force over
        // Wi-Fi in a matter of minutes.
        QHash<QString, QPair<int, QDateTime>> m_authFailures;

        QUdpSocket m_beaconSocket;
        QTimer m_beaconTimer;

        QString m_errorString;

#if defined(Q_OS_IOS)
        // iOS closes every socket when the app is suspended, so the server has to
        // be rebuilt on resume. GeoMaps::TileServer does the same.
        bool m_suspended {false};
#endif
    };

} // namespace Companion
