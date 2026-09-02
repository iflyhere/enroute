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

package de.akaflieg_freiburg.enroute.wear.data

import android.net.wifi.WifiManager
import android.util.Log
import de.akaflieg_freiburg.enroute.wear.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException

/** A phone that announced itself on the local network. */
data class DiscoveredPhone(
    val host: String,
    val port: Int,
    val protocolVersion: Int,
    val sessionId: Long,
)

@Serializable
internal data class BeaconDto(
    @SerialName("App") val app: String = "",
    @SerialName("companion") val companion: CompanionDto? = null,
)

@Serializable
internal data class CompanionDto(
    @SerialName("port") val port: Int = Config.DEFAULT_PORT,
    @SerialName("v") val protocolVersion: Int = 0,
    @SerialName("sid") val sessionId: Long = 0,
)

/**
 * Listens for the discovery datagram the phone broadcasts while its companion link is
 * up, so that a pilot does not have to type an address into a watch.
 *
 * Discovery is a convenience and never a requirement: many networks block broadcast
 * between clients, so manual entry has to keep working.
 */
class Discovery(private val wifiManager: WifiManager?) {

    fun phones(): Flow<DiscoveredPhone> = callbackFlow {
        // Without a multicast lock the Wi-Fi chip filters these frames before the
        // socket ever sees them, and nothing arrives while everything looks fine.
        val lock = wifiManager?.createMulticastLock(LOCK_TAG)?.apply {
            setReferenceCounted(false)
            acquire()
        }

        val socket = try {
            DatagramSocket(Config.DEFAULT_PORT).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = SOCKET_TIMEOUT_MS
            }
        } catch (failure: Exception) {
            Log.w(TAG, "cannot listen for beacons: " + failure.message)
            lock?.release()
            close()
            return@callbackFlow
        }

        val buffer = ByteArray(BUFFER_BYTES)
        try {
            while (!isClosedForSend) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (timeout: SocketTimeoutException) {
                    continue
                }

                val text = String(packet.data, 0, packet.length)
                // The address comes from the sender, never from the payload: a
                // datagram must not be able to point a client somewhere else.
                val host = packet.address.hostAddress ?: continue
                val phone = parseBeacon(text, host) ?: continue
                trySend(phone)
            }
        } finally {
            socket.close()
            lock?.release()
        }

        awaitClose { socket.close() }
    }.flowOn(Dispatchers.IO)

    internal companion object {
        /**
         * Turns a beacon datagram into a phone, or null if it is not one of ours.
         *
         * @param text The datagram payload
         *
         * @param host The address the datagram came from
         */
        internal fun parseBeacon(text: String, host: String): DiscoveredPhone? {
            val beacon = runCatching {
                WireJson.json.decodeFromString<BeaconDto>(text)
            }.getOrNull() ?: return null

            if (beacon.app != EXPECTED_APP) {
                return null
            }
            val companion = beacon.companion ?: return null

            return DiscoveredPhone(
                host = host,
                port = companion.port,
                protocolVersion = companion.protocolVersion,
                sessionId = companion.sessionId,
            )
        }

        const val TAG = "EnrouteWear"
        const val LOCK_TAG = "enroute:wear:discovery"
        const val EXPECTED_APP = "Enroute Flight Navigation"
        const val SOCKET_TIMEOUT_MS = 1_000
        const val BUFFER_BYTES = 512
    }
}
