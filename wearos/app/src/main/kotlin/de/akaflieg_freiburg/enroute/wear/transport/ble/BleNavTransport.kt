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

package de.akaflieg_freiburg.enroute.wear.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import de.akaflieg_freiburg.enroute.wear.data.WireJson
import de.akaflieg_freiburg.enroute.wear.data.dto.DocMetaDto
import de.akaflieg_freiburg.enroute.wear.data.dto.FlightLogDto
import de.akaflieg_freiburg.enroute.wear.data.dto.HelloDto
import de.akaflieg_freiburg.enroute.wear.data.dto.NavFrameDto
import de.akaflieg_freiburg.enroute.wear.data.dto.NearbyBoardDto
import de.akaflieg_freiburg.enroute.wear.data.dto.NotamBoardDto
import de.akaflieg_freiburg.enroute.wear.data.dto.RouteDto
import de.akaflieg_freiburg.enroute.wear.data.dto.TrafficBoardDto
import de.akaflieg_freiburg.enroute.wear.data.dto.VacBoardDto
import de.akaflieg_freiburg.enroute.wear.data.dto.WeatherBoardDto
import de.akaflieg_freiburg.enroute.wear.data.parseStyleColour
import de.akaflieg_freiburg.enroute.wear.data.toDomain
import de.akaflieg_freiburg.enroute.wear.domain.GeoPoint
import de.akaflieg_freiburg.enroute.wear.transport.FailureReason
import de.akaflieg_freiburg.enroute.wear.transport.NavTransport
import de.akaflieg_freiburg.enroute.wear.transport.PeerInfo
import de.akaflieg_freiburg.enroute.wear.transport.TransportEvent
import java.util.UUID
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * The link to the phone over Bluetooth Low Energy.
 *
 * The transport for flying. The Wi-Fi one needs both devices on one IP network, which
 * away from a known network does not exist -- and Wear OS powers its Wi-Fi radio down
 * whenever a Bluetooth companion link is available, so even where a network exists the
 * watch would rather not use it.
 *
 * The wire format is Transport 2 in doc/companion-protocol.md: the specified UUIDs,
 * fragments with a one-byte header, documents compressed with `qCompress` and pulled in
 * windows of eight that this side asks for. The framing itself lives in BleFraming.kt
 * and is tested there.
 *
 * Scanning is filtered on the service UUID and not on a device name. The phone's
 * advertised name is the adapter's, not the app's -- measured, not assumed -- so a name
 * would find nothing.
 *
 * There is no pairing code to present here. Over Wi-Fi the code is what keeps a stranger
 * on the same network from reading the aircraft's position; over Bluetooth the phone
 * hands the code out in its info document instead, because being connected to that GATT
 * server already means being in the aircraft. That is what lets a pilot who only ever
 * flies with Bluetooth type nothing at all, and what lets this link bootstrap the faster
 * one -- the same document carries the phone's address.
 */
@SuppressLint("MissingPermission")
class BleNavTransport(
    private val context: Context,
) : NavTransport {

    override val displayName: String get() = "Bluetooth"

    override fun session(): Flow<TransportEvent> = callbackFlow {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth is off")
            trySend(TransportEvent.Failed(FailureReason.Unreachable, "Bluetooth is off"))
            close()
            return@callbackFlow
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            trySend(TransportEvent.Failed(FailureReason.Unreachable, "no Bluetooth scanner"))
            close()
            return@callbackFlow
        }

        var gatt: BluetoothGatt? = null
        var refreshedCache = false

        // Android's GATT client carries out one operation at a time. A second request
        // issued before the first completes returns false and is then simply forgotten
        // -- no exception and no callback -- so subscribing to three characteristics
        // and reading a fourth in a straight line loses three of the four. Every
        // operation therefore goes through this queue, and every completion callback
        // starts the next one.
        val pending = ArrayDeque<Pair<String, () -> Boolean>>()
        var busy = false

        fun runNext() {
            if (busy) {
                return
            }
            while (pending.isNotEmpty()) {
                val (what, operation) = pending.removeFirst()
                busy = true
                if (runCatching { operation() }.getOrDefault(false)) {
                    return
                }
                // Rejected outright, so no callback is coming for it and waiting would
                // stall everything behind it.
                busy = false
                Log.w(TAG, "$what was refused by the Bluetooth stack")
            }
        }

        fun submit(what: String, operation: () -> Boolean) {
            pending.addLast(what to operation)
            runNext()
        }

        fun completed() {
            busy = false
            runNext()
        }

        fun requestDocument(
            name: String,
            client: BluetoothGatt,
            service: BluetoothGattService?,
            from: Int = 0,
        ) {
            val control = service?.getCharacteristic(CONTROL_UUID) ?: return
            submit("request $name from $from") {
                control.value = "{\"get\":\"$name\",\"from\":$from}".toByteArray()
                client.writeCharacteristic(control)
            }
        }

        // What this client holds, by document name, and what it is fetching now. The
        // link carries one transfer at a time, so a document is asked for only when the
        // previous one has arrived -- otherwise the second request re-prepares the
        // phone's buffer under the first, and neither completes.
        val held = mutableMapOf<String, Long>()
        var published = emptyMap<String, Long>()
        var fetching: String? = null

        // The revision each in-flight request was made against. Read back when the
        // document arrives, because by then `published` has been replaced by a newer
        // frame: recording that newer number against the older content would lose a
        // change that happened while the transfer was running, and it would stay lost.
        val requestedAt = mutableMapOf<String, Long>()

        val navFrames = Reassembler()
        val documents = Reassembler()
        var announcedHash: String? = null
        var announcedDocument: String? = null
        var nextFragment = 0

        fun fetchNextIfIdle(client: BluetoothGatt) {
            if (fetching != null) {
                return
            }
            val next = staleDocuments(published, held).firstOrNull() ?: return
            fetching = next
            requestedAt[next] = published[next] ?: 0L
            requestDocument(next, client, client.getService(SERVICE_UUID))
        }

        val gattCallback = object : BluetoothGattCallback() {

            override fun onConnectionStateChange(client: BluetoothGatt, status: Int, state: Int) {
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "connected, discovering services")
                    // Discovery is where the characteristics come from, so nothing can
                    // be read or subscribed to before it finishes.
                    client.discoverServices()
                    return
                }
                if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "disconnected, status $status")
                    navFrames.reset()
                    documents.reset()
                    fetching = null
                    requestedAt.clear()
                    pending.clear()
                    busy = false
                    trySend(TransportEvent.Failed(FailureReason.PeerClosed, "disconnected"))
                    close()
                }
            }

            override fun onMtuChanged(client: BluetoothGatt, mtu: Int, status: Int) {
                // Worth a line even when it works: everything downstream depends on it,
                // and a peer that refuses to grow the MTU is the one configuration in
                // which the metadata cannot arrive whole.
                Log.i(TAG, "MTU is now $mtu")
                completed()
            }

            override fun onDescriptorWrite(
                client: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                completed()
            }

            override fun onCharacteristicWrite(
                client: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                completed()
            }

            override fun onServicesDiscovered(client: BluetoothGatt, status: Int) {
                val service = client.getService(SERVICE_UUID)
                if (service == null) {
                    // Worth naming what was there instead. This fires when something
                    // else answered the advertisement -- a second copy of the app, or
                    // a stale server from one that was restarted -- and without the
                    // list the symptom is indistinguishable from a radio fault.
                    Log.w(
                        TAG,
                        "no companion service; the peer offers " +
                            client.services.joinToString { entry -> entry.uuid.toString() },
                    )
                    if (!refreshedCache && discardServiceCache(client)) {
                        // Android remembers a peer's service list and hands back the
                        // remembered one rather than asking again, so a phone whose
                        // services changed since the last connection -- an app updated,
                        // or a second app that answered once -- stays wrong forever. The
                        // cache is dropped here and the ordinary retry rediscovers. Once
                        // per session: if it is still missing after a fresh discovery,
                        // the service really is not there.
                        refreshedCache = true
                        Log.i(TAG, "discarded the cached service list, reconnecting")
                        trySend(
                            TransportEvent.Failed(
                                FailureReason.PeerClosed,
                                "stale service list",
                            ),
                        )
                        close()
                        return
                    }
                    trySend(
                        TransportEvent.Failed(
                            FailureReason.Unreachable,
                            "the phone does not offer the companion service",
                        ),
                    )
                    close()
                    return
                }

                Log.i(TAG, "companion service found, subscribing")

                // First, and before anything is subscribed to or read. The default ATT
                // MTU of 23 leaves 20 usable bytes, and the metadata document is around
                // a hundred: at the default it arrives truncated, fails to parse, and
                // the transfer stalls with no error anywhere. The stack negotiates down
                // to whatever the peer allows, so asking for the maximum is free.
                submit("request a larger MTU") { client.requestMtu(LARGEST_MTU) }

                // Subscribed before anything is asked for: a notification that arrives
                // while the descriptor is still off is simply not delivered, and the
                // watch would wait for a document that was already sent. Queued one at
                // a time, because that is the only way the stack accepts them.
                listOf(NAV_UUID, META_UUID, DATA_UUID).forEach { uuid ->
                    val characteristic = service.getCharacteristic(uuid) ?: return@forEach
                    val descriptor = characteristic.getDescriptor(CLIENT_CONFIG_UUID)
                    if (descriptor == null) {
                        // Without the descriptor the phone cannot be told to notify, so
                        // this characteristic will stay silent. Worth a line: it means
                        // the peer is not the server this client expects.
                        Log.w(TAG, "no configuration descriptor on $uuid")
                        return@forEach
                    }
                    submit("subscribe to $uuid") {
                        client.setCharacteristicNotification(characteristic, true)
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        client.writeDescriptor(descriptor)
                    }
                }

                val info = service.getCharacteristic(INFO_UUID)
                if (info == null) {
                    trySend(
                        TransportEvent.Failed(
                            FailureReason.Unreachable,
                            "the companion service has no info characteristic",
                        ),
                    )
                    close()
                    return
                }
                submit("read info") { client.readCharacteristic(info) }
            }

            override fun onCharacteristicRead(
                client: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (characteristic.uuid != INFO_UUID) {
                    completed()
                    return
                }
                val payload = characteristic.value
                completed()
                if (payload == null) {
                    return
                }
                // Seeds the route revision, so the request below is recorded against a
                // real number rather than zero -- otherwise the first navigation frame
                // would find the route stale and fetch all seven kilobytes of it again.
                published = published + ("route" to (helloOf(payload)?.routeRevision ?: 0L))
                emitHello(payload)

                // The route, before any revision counter has arrived: every navigation
                // frame refers to it, and waiting for the first frame to say so shows
                // an empty screen for as long as the phone's publish interval, which on
                // the ground is several seconds.
                fetching = "route"
                requestedAt["route"] = published["route"] ?: 0L
                requestDocument("route", client, service = client.getService(SERVICE_UUID))
            }

            override fun onCharacteristicChanged(
                client: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                val payload = characteristic.value ?: return
                when (characteristic.uuid) {
                    NAV_UUID -> navFrames.accept(payload)?.let { document ->
                        val decoded = runCatching {
                            WireJson.json.decodeFromString<NavFrameDto>(document.decodeToString())
                        }.getOrNull() ?: return
                        trySend(TransportEvent.Nav(decoded.toDomain()))

                        // The frame is where a Bluetooth client learns that anything
                        // else moved. Without this it would fetch the route once and
                        // never hear about the NOTAMs, the weather or anything else
                        // again -- which is most of what the watch shows.
                        published = decoded.revisions?.let { revisions ->
                            mapOf(
                                "route" to decoded.routeRevision,
                                "notams" to revisions.notam,
                                "weather" to revisions.weather,
                                "vacs" to revisions.vac,
                                "log" to revisions.log,
                                "nearby" to revisions.nearby,
                                "traffic" to revisions.traffic,
                            )
                        } ?: mapOf("route" to decoded.routeRevision)
                        fetchNextIfIdle(client)
                    }

                    META_UUID -> {
                        val meta = runCatching {
                            WireJson.json.decodeFromString<DocMetaDto>(payload.decodeToString())
                        }.getOrNull()
                        if (meta == null) {
                            // Almost always a truncated notification rather than a
                            // malformed document, so the length is the useful part.
                            Log.w(TAG, "unreadable metadata, " + payload.size + " bytes")
                            return
                        }
                        announcedDocument = meta.document.takeIf { name -> name.isNotBlank() }
                        announcedHash = meta.hash
                        documents.reset()
                        nextFragment = 0
                    }

                    DATA_UUID -> {
                        nextFragment += 1
                        val complete = documents.accept(payload)
                        if (complete == null) {
                            // The window is eight fragments; asking for the next one
                            // only when this one is exhausted is what keeps the phone
                            // from overflowing the Android Bluetooth queue.
                            if (nextFragment % WINDOW_FRAGMENTS == 0) {
                                // The name goes with every request. Sending an empty
                                // one stalled the transfer after the first window: the
                                // phone requires a name and ignores a request without
                                // one, and a `from` above zero is what tells it to
                                // continue rather than start again.
                                announcedDocument?.let { name ->
                                    requestDocument(
                                        name, client, client.getService(SERVICE_UUID),
                                        from = nextFragment,
                                    )
                                }
                            }
                            return
                        }
                        deliverDocument(complete)
                    }
                }
            }

            private fun helloOf(payload: ByteArray): HelloDto? = runCatching {
                WireJson.json.decodeFromString<HelloDto>(payload.decodeToString())
            }.getOrNull()

            private fun emitHello(payload: ByteArray) {
                val hello = runCatching {
                    WireJson.json.decodeFromString<HelloDto>(payload.decodeToString())
                }.getOrNull() ?: return
                trySend(TransportEvent.Connected(peerOf(hello)))
            }

            private fun deliverDocument(document: ByteArray) {
                val inflated = inflateQCompressed(document)
                if (inflated == null || !matchesHash(inflated, announcedHash)) {
                    // Damaged rather than unreadable: asked for again from the start
                    // instead of parsed into something plausible and wrong.
                    Log.w(TAG, "document failed its hash, asking again")
                    documents.reset()
                    nextFragment = 0
                    announcedDocument?.let { name ->
                        gatt?.let { client ->
                            requestDocument(name, client, client.getService(SERVICE_UUID))
                        }
                    }
                    return
                }
                Log.i(TAG, "received " + announcedDocument + ", " + inflated.size + " bytes")
                emitDocument(announcedDocument, inflated.decodeToString())

                // Recorded against what the phone said when the request went out, not
                // against what it says now: a document that changed again while this
                // one was in flight must stay stale, or the change is lost until the
                // next one.
                announcedDocument?.let { name ->
                    requestedAt.remove(name)?.let { revision -> held[name] = revision }
                    if (fetching == name) {
                        fetching = null
                    }
                }
                gatt?.let { client -> fetchNextIfIdle(client) }
            }

            private fun emitDocument(name: String?, text: String) {
                val event = runCatching {
                    when (name) {
                        "route" -> TransportEvent.RouteUpdate(
                            WireJson.json.decodeFromString<RouteDto>(text).toDomain(),
                        )
                        "notams" -> TransportEvent.NotamUpdate(
                            WireJson.json.decodeFromString<NotamBoardDto>(text).toDomain(),
                        )
                        "weather" -> TransportEvent.WeatherUpdate(
                            WireJson.json.decodeFromString<WeatherBoardDto>(text).toDomain(),
                        )
                        "vacs" -> TransportEvent.VacUpdate(
                            WireJson.json.decodeFromString<VacBoardDto>(text).toDomain(),
                        )
                        "log" -> TransportEvent.FlightLogUpdate(
                            WireJson.json.decodeFromString<FlightLogDto>(text).toDomain(),
                        )
                        "traffic" -> TransportEvent.TrafficUpdate(
                            WireJson.json.decodeFromString<TrafficBoardDto>(text).toDomain(),
                        )
                        "nearby" -> TransportEvent.NearbyUpdate(
                            WireJson.json.decodeFromString<NearbyBoardDto>(text).toDomain(),
                        )
                        else -> null
                    }
                }.getOrNull() ?: return
                trySend(event)
            }
        }

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val device = result?.device ?: return
                // One phone, and the first one found. Two aircraft advertising in one
                // hangar is a real possibility, and picking by signal strength would
                // pick whichever is closer rather than whichever is the pilot's -- the
                // pairing code is what settles that, on the documents themselves.
                Log.i(TAG, "found a phone advertising the companion service")
                scanner.stopScan(this)
                gatt = device.connectGatt(context, false, gattCallback)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "scan failed with $errorCode")
                trySend(
                    TransportEvent.Failed(FailureReason.Unreachable, "scan failed: $errorCode"),
                )
                close()
            }
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        Log.i(TAG, "scanning for the companion service")
        scanner.startScan(listOf(filter), settings, scanCallback)

        awaitClose {
            runCatching { scanner.stopScan(scanCallback) }
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
        }
    }

    private companion object {
        const val TAG = "EnrouteWearBle"

        // From doc/companion-protocol.md. A client filters on the service UUID.
        val SERVICE_UUID: UUID = UUID.fromString("e5c0a000-9b6f-4a1e-8d3c-1f7a2b6d4e10")
        val INFO_UUID: UUID = UUID.fromString("e5c0a001-9b6f-4a1e-8d3c-1f7a2b6d4e10")
        val NAV_UUID: UUID = UUID.fromString("e5c0a002-9b6f-4a1e-8d3c-1f7a2b6d4e10")
        val META_UUID: UUID = UUID.fromString("e5c0a003-9b6f-4a1e-8d3c-1f7a2b6d4e10")
        val DATA_UUID: UUID = UUID.fromString("e5c0a004-9b6f-4a1e-8d3c-1f7a2b6d4e10")
        val CONTROL_UUID: UUID = UUID.fromString("e5c0a005-9b6f-4a1e-8d3c-1f7a2b6d4e10")

        // The descriptor that turns notifications on. Standard, and the reason a
        // peripheral has to define it.
        val CLIENT_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val WINDOW_FRAGMENTS = 8

        /**
         * Makes Android forget what services a peer had.
         *
         * There is no public way to do this, and there needs to be: the platform caches
         * a peer's service list and returns the cached one from discoverServices, so a
         * phone whose GATT database changed since the watch last connected is invisible
         * until the pilot clears the watch's Bluetooth data. The method has been present
         * and unchanged since the first Android that had BLE at all.
         *
         * Best effort by construction. A platform that removes it makes this return
         * false, and the caller then reports the service as missing, which is the
         * honest answer rather than a silent retry loop.
         */
        fun discardServiceCache(client: BluetoothGatt): Boolean = runCatching {
            val refresh = client.javaClass.getMethod("refresh")
            refresh.invoke(client) as? Boolean ?: false
        }.getOrDefault(false)

        /**
         * The largest MTU Android will ask for.
         *
         * Asked for rather than assumed: the peer answers with what it can do, and the
         * only wrong move is not to ask. The phone still fragments to the 23-byte floor
         * because Qt does not expose the negotiated MTU in the peripheral role, so this
         * buys a whole metadata document rather than faster fragments.
         */
        const val LARGEST_MTU = 517

        fun peerOf(hello: HelloDto) = PeerInfo(
            appVersion = hello.appVersion,
            protocolVersion = hello.version,
            sessionId = hello.sessionId,
            navPeriodMs = hello.navPeriodMs,
            wifiUrl = hello.wifiUrl,
            pairingCode = hello.pairingCode,
            mapRevision = hello.mapRevision,
            mapAttribution = hello.mapAttribution,
            mapCentre = hello.mapCentre.takeIf { it.size >= 2 }
                ?.let { GeoPoint(latDeg = it[1], lonDeg = it[0]) },
            mapCentreZoom = hello.mapCentre.getOrElse(2) { 0.0 },
            verticalUnit = hello.units.verticalDistance,
            horizontalUnit = hello.units.horizontalDistance,
            mapLabelColour = parseStyleColour(hello.mapOverlay?.label),
            mapHaloColour = parseStyleColour(hello.mapOverlay?.halo),
        )
    }
}
