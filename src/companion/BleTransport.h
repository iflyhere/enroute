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

#include <QBluetoothPermission>
#include <QLowEnergyController>
#include <QLowEnergyServiceData>
#include <QPointer>

namespace Companion
{

    class CompanionServer;

    /*! \brief Publishes the companion documents over Bluetooth Low Energy
     *
     *  The transport for actual flying, where the Wi-Fi one is a transport for the
     *  bench. Wear OS powers its Wi-Fi radio down whenever a Bluetooth companion link
     *  is available, and away from a known network there is no shared IP network for
     *  an HTTP link to run over at all -- which is what a pilot finds the first time
     *  they leave the house with the watch on.
     *
     *  The wire format is the one specified under "Transport 2" in
     *  doc/companion-protocol.md, down to the UUIDs and the one-byte fragment header.
     *  That document was written before this class and its framing and flow control
     *  are not this class's inventions to change: a first implementation here used a
     *  three-byte header and wrote every fragment in a loop, which is precisely the
     *  mistake the document warns about.
     *
     *  The Info characteristic carries the Wi-Fi address and the pairing code as well
     *  as the capability document. That is deliberate and useful beyond bootstrapping:
     *  a watch that has lost the phone's address because the network changed can read
     *  the current one over Bluetooth instead of asking the pilot to type it.
     */

    class BleTransport : public QObject
    {
        Q_OBJECT

    public:
        /*! \brief Standard constructor
         *
         *  @param server The companion server whose documents are published. Read
         *  only; this class never asks it to encode anything, it forwards what the
         *  server has already published.
         *
         *  @param parent Standard QObject parent
         */
        explicit BleTransport(Companion::CompanionServer* server, QObject* parent = nullptr);

        ~BleTransport() override;

        /*! \brief Starts advertising
         *
         *  Idempotent. On a stack that refuses the peripheral role this reports the
         *  reason through errorString() and leaves the Wi-Fi transport untouched.
         */
        void start();

        /*! \brief Stops advertising and drops any connected central */
        void stop();

        /*! \brief Human-readable description of the last runtime failure, or empty */
        [[nodiscard]] QString errorString() const { return m_errorString; }

        /*! \brief Number of connected centrals, which is at most one */
        [[nodiscard]] int clientCount() const { return m_connected ? 1 : 0; }

        /*! \brief The service UUID a client scans for */
        [[nodiscard]] static QBluetoothUuid serviceUuid();

    signals:
        /*! \brief Emitted when errorString() changes */
        void errorStringChanged();

        /*! \brief Emitted when a central connects or disconnects */
        void clientCountChanged();

    private slots:
        // Pushes the current navigation frame. Driven by the server's own publish
        // signal, so the rate is the server's and not ours.
        void publishNav();

        // Announces that a document a client may be holding has changed.
        void publishDocumentMeta();

        void onCharacteristicWritten(const QLowEnergyCharacteristic& characteristic,
                                     const QByteArray& value);

        void onStateChanged(QLowEnergyController::ControllerState state);

        void onControllerError(QLowEnergyController::Error error);

    private:
        Q_DISABLE_COPY_MOVE(BleTransport)

        void setErrorString(const QString& newErrorString);

        // Builds the service definition. Every notify characteristic gets a client
        // characteristic configuration descriptor initialised to two zero bytes:
        // without it notifications silently never fire, which is the single most
        // common mistake with a Qt peripheral.
        [[nodiscard]] static QLowEnergyServiceData serviceDefinition();

        // Advertising data is 31 bytes and a 128-bit service UUID alone eats 18 of
        // them. The name goes in the advertising packet and the UUID in the scan
        // response, or the advertisement is rejected outright.
        void beginAdvertising();

        // The capability document plus the Wi-Fi address and pairing code, so a client
        // can move to the faster transport, or recover an address that changed,
        // without the pilot typing anything.
        [[nodiscard]] QByteArray infoDocument() const;

        // What a client is currently allowed to ask for, and the bytes behind it.
        [[nodiscard]] QByteArray documentByName(const QString& name) const;

        // Compresses and describes a document, so a client knows what it is about to
        // receive and can tell a complete transfer from a truncated one.
        void prepareDocument(const QString& name);

        // Sends at most one window of fragments, starting at the requested one, and
        // then stops. A hundred notifications written in a loop overflow the Android
        // Bluetooth queue; the client asks for the next window when it is ready.
        void sendWindow(int from);

        // One byte: bit 7 marks the last fragment, bits 0 to 6 the index modulo 128.
        [[nodiscard]] static char fragmentHeader(int index, int total);

        QPointer<Companion::CompanionServer> m_server;
        QPointer<QLowEnergyController> m_controller;
        QPointer<QLowEnergyService> m_service;

        QBluetoothPermission m_bluetoothPermission;

        QString m_errorString;
        bool m_connected {false};

        quint32 m_lastNavRevision {0};

        // The document a client is being sent, already compressed. Held rather than
        // recompressed per window, so that a transfer cannot see two different
        // versions of the same document halfway through.
        QString m_preparedName;
        QByteArray m_prepared;
        int m_preparedFragments {0};
    };

} // namespace Companion
