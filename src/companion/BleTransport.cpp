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

#include <QCoreApplication>
#include <QCryptographicHash>
#include <QJsonDocument>
#include <QJsonObject>
#include <QLowEnergyAdvertisingData>
#include <QTimer>
#include <QLowEnergyAdvertisingParameters>
#include <QLowEnergyCharacteristicData>
#include <QLowEnergyDescriptorData>
#include <QLowEnergyService>

#include "GlobalObject.h"
#include "GlobalSettings.h"
#include "companion/BleTransport.h"
#include "companion/CompanionServer.h"
#include "companion/Protocol.h"

using namespace Qt::Literals::StringLiterals;


namespace
{
    // The UUIDs from doc/companion-protocol.md. Not this class's to choose: a client
    // written against that document scans for exactly these.
    constexpr auto serviceUuidString = "e5c0a000-9b6f-4a1e-8d3c-1f7a2b6d4e10";
    constexpr auto infoUuidString = "e5c0a001-9b6f-4a1e-8d3c-1f7a2b6d4e10";
    constexpr auto navUuidString = "e5c0a002-9b6f-4a1e-8d3c-1f7a2b6d4e10";
    constexpr auto metaUuidString = "e5c0a003-9b6f-4a1e-8d3c-1f7a2b6d4e10";
    constexpr auto dataUuidString = "e5c0a004-9b6f-4a1e-8d3c-1f7a2b6d4e10";
    constexpr auto controlUuidString = "e5c0a005-9b6f-4a1e-8d3c-1f7a2b6d4e10";

    // The fragment payload, in bytes. The specification guarantees an MTU of 23, of
    // which three are ATT overhead and one is the fragment header; Qt does not expose
    // the negotiated MTU in the peripheral role, so this is the floor that always
    // works rather than the best case that sometimes does.
    constexpr int payloadBytes = 23 - 3 - 1;

    // Fragments per request. The document's number, and the reason for it: writing a
    // hundred notifications in a loop overflows the Android Bluetooth queue.
    constexpr int windowFragments = 8;

    // A ceiling on a single transfer. The index in the one-byte header wraps every
    // 128 fragments, which a client tracks, but a document that needs more than this
    // many is a mistake somewhere else.
    constexpr int maxFragments = 4096;

    QBluetoothUuid uuidOf(const char* text)
    {
        return QBluetoothUuid(QString::fromLatin1(text));
    }
} // namespace


QBluetoothUuid Companion::BleTransport::serviceUuid()
{
    return uuidOf(serviceUuidString);
}


Companion::BleTransport::BleTransport(Companion::CompanionServer* server, QObject* parent)
    : QObject(parent), m_server(server)
{
    // Access alone is what every other Bluetooth user in this app asks for, because
    // they are all centrals. A peripheral additionally needs Advertise, and without it
    // Android 12 and later refuse to advertise while reporting success -- the failure
    // mode this class is written to avoid.
    m_bluetoothPermission.setCommunicationModes(
        QBluetoothPermission::Access | QBluetoothPermission::Advertise);

    // Half a second, measured against the emulator's stack: immediately fails with
    // ALREADY_STARTED because the advertiser that was just connected to has not been
    // torn down yet. Long enough for that, short enough that a watch reconnecting
    // after walking out of range and back does not notice the gap.
    m_advertisingTimer.setSingleShot(true);
    m_advertisingTimer.setInterval(500);
    connect(&m_advertisingTimer, &QTimer::timeout, this, [this]()
    {
        // In this order, and both every time: a fresh GATT server needs the service
        // adding to it, and there is no point being findable without one.
        publishService();
        beginAdvertising();
    });
}


Companion::BleTransport::~BleTransport()
{
    stop();
}


void Companion::BleTransport::start()
{
    if (!m_controller.isNull())
    {
        return;
    }

    const auto status = qApp->checkPermission(m_bluetoothPermission);
    if (status == Qt::PermissionStatus::Denied)
    {
        setErrorString(tr("Necessary permissions have been denied."));
        return;
    }
    if (status == Qt::PermissionStatus::Undetermined)
    {
        // Requested, and then this method runs again. Advertising cannot begin before
        // the answer, and guessing would advertise nothing while looking fine.
        qApp->requestPermission(m_bluetoothPermission, this,
                                [this](const QPermission& /*unused*/) {start();});
        return;
    }

    m_controller = QLowEnergyController::createPeripheral(this);
    if (m_controller.isNull())
    {
        setErrorString(tr("This device cannot act as a Bluetooth peripheral."));
        return;
    }

    connect(m_controller, &QLowEnergyController::stateChanged,
            this, &Companion::BleTransport::onStateChanged);
    connect(m_controller, &QLowEnergyController::errorOccurred,
            this, &Companion::BleTransport::onControllerError);

    publishService();
    if (m_service.isNull())
    {
        return;
    }

    if (!m_server.isNull())
    {
        connect(m_server, &Companion::CompanionServer::navDocumentChanged,
                this, &Companion::BleTransport::publishNav);

        // Every document a client may be holding. It is told that one changed and
        // asks for it again if it cares; pushing them all would spend the radio on
        // documents nothing is looking at.
        for (const auto signal : {&Companion::CompanionServer::routeDocumentChanged,
                                  &Companion::CompanionServer::notamDocumentChanged,
                                  &Companion::CompanionServer::weatherDocumentChanged,
                                  &Companion::CompanionServer::vacDocumentChanged,
                                  &Companion::CompanionServer::logDocumentChanged,
                                  &Companion::CompanionServer::nearbyDocumentChanged})
        {
            connect(m_server, signal, this, &Companion::BleTransport::publishDocumentMeta);
        }
    }

    setErrorString({});
    beginAdvertising();
}


void Companion::BleTransport::stop()
{
    if (m_controller.isNull())
    {
        return;
    }
    m_advertisingTimer.stop();
    m_controller->stopAdvertising();
    m_controller->disconnectFromDevice();
    delete m_controller;
    m_controller = nullptr;
    m_service = nullptr;

    m_prepared.clear();
    m_preparedName.clear();
    m_preparedFragments = 0;

    if (m_connected)
    {
        m_connected = false;
        emit clientCountChanged();
    }
}


QLowEnergyServiceData Companion::BleTransport::serviceDefinition()
{
    // The descriptor every notify characteristic needs. Two zero bytes is the
    // specification's "notifications and indications off", which the client turns on
    // by writing to it; omit the descriptor and the client has nothing to write to, so
    // notifications never start and nothing anywhere reports a problem.
    QLowEnergyDescriptorData clientConfiguration(
        QBluetoothUuid::DescriptorType::ClientCharacteristicConfiguration,
        QByteArray(2, 0));

    QLowEnergyCharacteristicData info;
    info.setUuid(uuidOf(infoUuidString));
    info.setProperties(QLowEnergyCharacteristic::Read);

    QLowEnergyCharacteristicData nav;
    nav.setUuid(uuidOf(navUuidString));
    nav.setProperties(QLowEnergyCharacteristic::Read | QLowEnergyCharacteristic::Notify);
    nav.addDescriptor(clientConfiguration);

    QLowEnergyCharacteristicData meta;
    meta.setUuid(uuidOf(metaUuidString));
    meta.setProperties(QLowEnergyCharacteristic::Read | QLowEnergyCharacteristic::Notify);
    meta.addDescriptor(clientConfiguration);

    QLowEnergyCharacteristicData data;
    data.setUuid(uuidOf(dataUuidString));
    data.setProperties(QLowEnergyCharacteristic::Notify);
    data.addDescriptor(clientConfiguration);

    // Write rather than WriteNoResponse: a request that silently failed would leave a
    // watch waiting for a document that is never coming.
    QLowEnergyCharacteristicData control;
    control.setUuid(uuidOf(controlUuidString));
    control.setProperties(QLowEnergyCharacteristic::Write);

    QLowEnergyServiceData service;
    service.setType(QLowEnergyServiceData::ServiceTypePrimary);
    service.setUuid(serviceUuid());
    service.addCharacteristic(info);
    service.addCharacteristic(nav);
    service.addCharacteristic(meta);
    service.addCharacteristic(data);
    service.addCharacteristic(control);
    return service;
}


void Companion::BleTransport::beginAdvertising()
{
    if (m_controller.isNull())
    {
        return;
    }
    if (m_controller->state() == QLowEnergyController::AdvertisingState)
    {
        // Already on the air. Asking again is answered with ALREADY_STARTED, and a
        // failure here is what drives the retry loop this guard exists to stop.
        return;
    }

    // Thirty-one bytes, and a 128-bit UUID takes eighteen of them, so the UUID goes in
    // the scan response where there is room. A client filters on it and that is what
    // makes the phone findable.
    //
    // The local name asked for here does not reach the air on Android: measured in an
    // emulator, the advertisement carried the adapter's own name and not this one, so
    // a scan list shows the phone's Bluetooth name. That is arguably the better label
    // anyway -- a pilot recognises their own phone -- and it is set here so that a
    // platform which does honour it says something useful.
    QLowEnergyAdvertisingData advertising;
    advertising.setDiscoverability(QLowEnergyAdvertisingData::DiscoverabilityGeneral);
    advertising.setLocalName(u"Enroute"_s);

    QLowEnergyAdvertisingData scanResponse;
    scanResponse.setServices({serviceUuid()});

    m_controller->startAdvertising(QLowEnergyAdvertisingParameters(),
                                   advertising, scanResponse);
}


void Companion::BleTransport::publishService()
{
    if (m_controller.isNull())
    {
        return;
    }

    // The previous one belongs to a GATT server that the backend has already closed,
    // so it is of no use to anybody. Deleting a QPointer that Qt has already cleared
    // is a no-op, which is the case after a disconnect.
    delete m_service.data();

    m_service = m_controller->addService(serviceDefinition(), m_controller);
    if (m_service.isNull())
    {
        setErrorString(tr("The Bluetooth service could not be published."));
        return;
    }
    connect(m_service, &QLowEnergyService::characteristicChanged,
            this, &Companion::BleTransport::onCharacteristicWritten);
}


void Companion::BleTransport::scheduleAdvertising()
{
    if (m_advertisingTimer.isActive())
    {
        return;
    }
    m_advertisingTimer.start();
}


void Companion::BleTransport::onStateChanged(QLowEnergyController::ControllerState state)
{
    const auto wasConnected = m_connected;
    m_connected = (state == QLowEnergyController::ConnectedState);

    if (m_connected != wasConnected)
    {
        emit clientCountChanged();
    }

    // Android stops advertising the moment a central connects, and does not resume.
    // Without this the phone accepts exactly one connection per app run, which looks
    // like a flaky watch rather than a missing line of code.
    if (state == QLowEnergyController::UnconnectedState)
    {
        m_lastNavRevision = 0;
        m_prepared.clear();
        m_preparedName.clear();
        m_preparedFragments = 0;
        scheduleAdvertising();
    }

    if (m_connected && !m_service.isNull())
    {
        // A client that has just connected holds nothing, so the readable
        // characteristics are filled before it asks and the first frame is pushed
        // without waiting for the server's next publish.
        m_service->writeCharacteristic(m_service->characteristic(uuidOf(infoUuidString)),
                                       infoDocument());
        publishNav();
    }
}


void Companion::BleTransport::onControllerError(QLowEnergyController::Error error)
{
    if (error == QLowEnergyController::NoError)
    {
        return;
    }
    // The controller's own words, which name the stack's objection -- an OEM that
    // refuses the peripheral role says so here, and a pilot needs to see that in the
    // settings page rather than wonder why the watch never finds the phone.
    setErrorString(m_controller.isNull() ? tr("Bluetooth error") : m_controller->errorString());
}


QByteArray Companion::BleTransport::infoDocument() const
{
    if (m_server.isNull())
    {
        return {};
    }

    auto document = QJsonDocument::fromJson(m_server->helloDocument()).object();

    // The address and the code, so a client can move to the faster transport without
    // the pilot typing either -- and so a client that lost the address when the
    // network changed can recover it here instead of guessing.
    const auto urls = m_server->serverUrls();
    if (!urls.isEmpty())
    {
        document.insert("ip"_L1, urls.constFirst());
    }
    document.insert("code"_L1, GlobalObject::globalSettings()->companionPairingCode());

    return QJsonDocument(document).toJson(QJsonDocument::Compact);
}


QByteArray Companion::BleTransport::documentByName(const QString& name) const
{
    if (m_server.isNull())
    {
        return {};
    }
    if (name == u"route"_s) { return m_server->routeDocument(); }
    if (name == u"notams"_s) { return m_server->notamDocument(); }
    if (name == u"weather"_s) { return m_server->weatherDocument(); }
    if (name == u"vacs"_s) { return m_server->vacDocument(); }
    if (name == u"log"_s) { return m_server->logDocument(); }
    if (name == u"traffic"_s) { return m_server->trafficDocument(); }
    if (name == u"nearby"_s) { return m_server->nearbyDocument(); }
    return {};
}


void Companion::BleTransport::prepareDocument(const QString& name)
{
    const auto plain = documentByName(name);
    if (plain.isEmpty())
    {
        m_prepared.clear();
        m_preparedName.clear();
        m_preparedFragments = 0;
        return;
    }

    // qCompress, advertised as "zlib" and documented as not being gzip: it emits a
    // four-byte big-endian uncompressed length followed by a raw zlib stream, so a
    // client skips four bytes and inflates the rest.
    m_prepared = qCompress(plain);
    m_preparedName = name;
    m_preparedFragments = static_cast<int>((m_prepared.size() + payloadBytes - 1) / payloadBytes);

    if (m_preparedFragments > maxFragments)
    {
        m_prepared.clear();
        m_preparedName.clear();
        m_preparedFragments = 0;
        return;
    }

    // The hash is of the *uncompressed* document, so a client that inflates and finds
    // a mismatch knows the transfer was damaged rather than the compression.
    const auto digest = QCryptographicHash::hash(plain, QCryptographicHash::Sha1);

    QJsonObject meta;
    meta.insert("doc"_L1, name);
    meta.insert("len"_L1, static_cast<qint64>(m_prepared.size()));
    meta.insert("enc"_L1, "zlib"_L1);
    meta.insert("hash"_L1, QString::fromLatin1(digest.left(4).toHex()));
    meta.insert("chunk"_L1, payloadBytes);
    meta.insert("frags"_L1, m_preparedFragments);

    if (!m_service.isNull())
    {
        m_service->writeCharacteristic(m_service->characteristic(uuidOf(metaUuidString)),
                                       QJsonDocument(meta).toJson(QJsonDocument::Compact));
    }
}


void Companion::BleTransport::sendWindow(int from)
{
    if (m_service.isNull() || m_prepared.isEmpty() || from < 0)
    {
        return;
    }

    const auto characteristic = m_service->characteristic(uuidOf(dataUuidString));
    if (!characteristic.isValid())
    {
        return;
    }

    const auto last = qMin(from + windowFragments, m_preparedFragments);
    for (auto index = from; index < last; ++index)
    {
        QByteArray fragment;
        fragment.reserve(1 + payloadBytes);
        fragment.append(fragmentHeader(index, m_preparedFragments));
        fragment.append(m_prepared.mid(index * payloadBytes, payloadBytes));
        m_service->writeCharacteristic(characteristic, fragment);
    }
}


char Companion::BleTransport::fragmentHeader(int index, int total)
{
    // Bit 7 marks the last fragment, bits 0 to 6 carry the index modulo 128. A
    // single-fragment document therefore begins with 0x80.
    const auto marker = (index == total - 1) ? 0x80 : 0x00;
    return static_cast<char>(marker | (index % 128));
}


void Companion::BleTransport::publishNav()
{
    if (!m_connected || m_service.isNull() || m_server.isNull())
    {
        return;
    }

    const auto revisions = m_server->revisions();
    if (revisions.nav == m_lastNavRevision)
    {
        return;
    }
    m_lastNavRevision = revisions.nav;

    // The compact frame: no formatted strings. They are a convenience on a link with
    // bandwidth to spare, and this one has to fit a frame into as few notifications as
    // possible. The SI members are a complete description, and the protocol document
    // says so.
    const auto frame = m_server->navDocumentCompact();
    if (frame.isEmpty())
    {
        return;
    }

    const auto characteristic = m_service->characteristic(uuidOf(navUuidString));
    if (!characteristic.isValid())
    {
        return;
    }

    // Fragmented with the same one-byte header, and small enough that a window is not
    // needed: a compact frame is a few hundred bytes at most.
    const auto total = static_cast<int>((frame.size() + payloadBytes - 1) / payloadBytes);
    for (auto index = 0; index < total; ++index)
    {
        QByteArray fragment;
        fragment.reserve(1 + payloadBytes);
        fragment.append(fragmentHeader(index, total));
        fragment.append(frame.mid(index * payloadBytes, payloadBytes));
        m_service->writeCharacteristic(characteristic, fragment);
    }
}


void Companion::BleTransport::publishDocumentMeta()
{
    // Only for the document a client is actually holding. Announcing a change to
    // something nobody asked for would be a notification the client has to decode and
    // discard.
    if (!m_connected || m_preparedName.isEmpty())
    {
        return;
    }
    prepareDocument(m_preparedName);
}


void Companion::BleTransport::onCharacteristicWritten(
    const QLowEnergyCharacteristic& characteristic, const QByteArray& value)
{
    if (m_service.isNull() || m_server.isNull())
    {
        return;
    }
    if (characteristic.uuid() != uuidOf(controlUuidString))
    {
        return;
    }

    const auto request = QJsonDocument::fromJson(value).object();

    // {"get":"route","from":0} -- a document by name, from a fragment index. The
    // index makes a resumed transfer possible and is what keeps the window small.
    const auto wanted = request.value("get"_L1).toString();
    if (!wanted.isEmpty())
    {
        const auto from = request.value("from"_L1).toInt();
        if (wanted != m_preparedName || from == 0)
        {
            prepareDocument(wanted);
        }
        sendWindow(from);
        return;
    }

    // {"rate":2000} -- accepted and answered honestly. The publish rate belongs to
    // the server and is shared with every other client, so this is recorded as a
    // request rather than obeyed; pretending otherwise would have a watch believe it
    // had slowed the link down when it had not.
    if (request.contains("rate"_L1))
    {
        return;
    }
}


void Companion::BleTransport::setErrorString(const QString& newErrorString)
{
    if (m_errorString == newErrorString)
    {
        return;
    }
    m_errorString = newErrorString;
    emit errorStringChanged();
}
