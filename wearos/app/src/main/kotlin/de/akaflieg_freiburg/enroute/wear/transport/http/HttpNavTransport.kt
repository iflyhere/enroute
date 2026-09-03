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

package de.akaflieg_freiburg.enroute.wear.transport.http

import de.akaflieg_freiburg.enroute.wear.data.WireJson
import de.akaflieg_freiburg.enroute.wear.data.dto.HelloDto
import de.akaflieg_freiburg.enroute.wear.data.dto.NavFrameDto
import de.akaflieg_freiburg.enroute.wear.data.dto.NotamBoardDto
import de.akaflieg_freiburg.enroute.wear.domain.GeoPoint
import de.akaflieg_freiburg.enroute.wear.data.dto.RouteDto
import de.akaflieg_freiburg.enroute.wear.data.parseStyleColour
import de.akaflieg_freiburg.enroute.wear.data.toDomain
import de.akaflieg_freiburg.enroute.wear.transport.FailureReason
import de.akaflieg_freiburg.enroute.wear.transport.NavTransport
import de.akaflieg_freiburg.enroute.wear.transport.PeerInfo
import de.akaflieg_freiburg.enroute.wear.transport.TransportEvent
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Stage 1 of the companion protocol: JSON over HTTP on the local network.
 *
 * Polls the navigation frame with a conditional GET, which costs a bare 304 whenever
 * nothing has changed, and refetches the route only when the frame reports a different
 * revision or a different session.
 *
 * Polling rather than a streamed response is deliberate. A watch radio wakes for each
 * poll either way; a poll is its own reconnect logic, so a dropped connection needs no
 * separate recovery path; and it keeps this class dependency-free -- java.net is enough,
 * which matters for a project that has to justify every dependency it links.
 */
class HttpNavTransport(
    private val host: String,
    private val port: Int,
    private val pairingCode: String,
    private val pollPeriodMs: Long = 1_000,
) : NavTransport {

    override val displayName: String = "$host:$port"

    private val base = "http://$host:$port/enroute/v1"

    override fun session(): Flow<TransportEvent> = flow {
        emit(TransportEvent.Connecting)

        val hello = try {
            request(HELLO)?.let { WireJson.json.decodeFromString<HelloDto>(it.body) }
        } catch (unauthorized: UnauthorizedException) {
            Log.w(TAG, "hello rejected: " + unauthorized.message)
            emit(TransportEvent.Failed(FailureReason.Unauthorized, unauthorized.message))
            return@flow
        } catch (io: IOException) {
            Log.w(TAG, "hello failed: " + io.javaClass.simpleName + ": " + io.message)
            emit(TransportEvent.Failed(FailureReason.Unreachable, io.message))
            return@flow
        }
        if (hello == null) {
            emit(TransportEvent.Failed(FailureReason.Unreachable, "no response to hello"))
            return@flow
        }
        if (hello.version != WireJson.PROTOCOL_VERSION) {
            emit(
                TransportEvent.Failed(
                    FailureReason.ProtocolMismatch,
                    "peer speaks protocol ${hello.version}",
                ),
            )
            return@flow
        }

        // Logged because everything downstream depends on it and none of it is visible
        // from the outside: whether a map is offered, where the camera starts, which
        // notice to display. A wrong assumption here costs a whole test round.
        Log.i(TAG, "peer " + hello.appVersion + " v" + hello.version +
            " mapRev=" + hello.mapRevision +
            " centre=" + hello.mapCentre +
            " navPeriod=" + hello.navPeriodMs)

        emit(TransportEvent.Connected(peerOf(hello)))

        var knownRoute: Pair<Long, Long>? = null   // session id to route revision
        var navETag: String? = null
        var notamETag: String? = null

        // Zero, not "now", so the first pass fetches NOTAMs instead of leaving the
        // screen empty for a minute after connecting.
        var notamsFetchedAt = 0L

        while (true) {
            try {
                val response = request(NAV, ifNoneMatch = navETag)
                if (response != null) {
                    navETag = response.etag
                    val frame = WireJson.json.decodeFromString<NavFrameDto>(response.body).toDomain()
                    emit(TransportEvent.Nav(frame))

                    // One field carries the whole caching protocol: refetch the route
                    // when either the revision or the session identifier moves.
                    val wanted = frame.sessionId to frame.routeRevision
                    if (knownRoute != wanted) {
                        val route = request(ROUTE)
                            ?.let { WireJson.json.decodeFromString<RouteDto>(it.body) }
                            ?.toDomain()
                        if (route != null) {
                            emit(TransportEvent.RouteUpdate(route))
                            knownRoute = wanted
                        }

                        // The capability document is rebuilt in lockstep with the route
                        // document, so this is also the moment its contents can have
                        // changed. Refetching it closes a window that cost a whole test
                        // round: a client that connects in the fraction of a second
                        // before the phone first publishes its map revision otherwise
                        // spends the entire session believing no map is on offer,
                        // because the capability document is fetched exactly once.
                        request(HELLO)
                            ?.let { WireJson.json.decodeFromString<HelloDto>(it.body) }
                            ?.let { fresh -> emit(TransportEvent.Connected(peerOf(fresh))) }
                    }
                }

                // NOTAMs are on their own slow beat, and deliberately not tied to the
                // nav revision: they change when the phone downloads data, a few times
                // a day, not once a second. Polling them at the nav rate would cost a
                // radio wake and a few kilobytes every second for data that is hours
                // old. A 304 is the normal answer here.
                val now = System.currentTimeMillis()
                if (now - notamsFetchedAt >= NOTAM_PERIOD_MS) {
                    notamsFetchedAt = now
                    val notams = request(NOTAMS, ifNoneMatch = notamETag)
                    if (notams != null) {
                        notamETag = notams.etag
                        emit(
                            TransportEvent.NotamUpdate(
                                WireJson.json.decodeFromString<NotamBoardDto>(notams.body).toDomain(),
                            ),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (unauthorized: UnauthorizedException) {
                Log.w(TAG, "poll rejected: " + unauthorized.message)
                emit(TransportEvent.Failed(FailureReason.Unauthorized, unauthorized.message))
                return@flow
            } catch (io: IOException) {
                Log.w(TAG, "poll failed: " + io.javaClass.simpleName + ": " + io.message)
                emit(TransportEvent.Failed(FailureReason.PeerClosed, io.message))
                return@flow
            }

            delay(pollPeriodMs)
        }
    }.flowOn(Dispatchers.IO)

    private fun peerOf(hello: HelloDto) = PeerInfo(
        appVersion = hello.appVersion,
        protocolVersion = hello.version,
        sessionId = hello.sessionId,
        navPeriodMs = hello.navPeriodMs,
        mapRevision = hello.mapRevision,
        mapAttribution = hello.mapAttribution,
        mapCentre = hello.mapCentre.takeIf { it.size >= 2 }
            ?.let { GeoPoint(latDeg = it[1], lonDeg = it[0]) },
        mapCentreZoom = hello.mapCentre.getOrElse(2) { 0.0 },
        mapLabelColour = parseStyleColour(hello.mapOverlay?.label),
        mapHaloColour = parseStyleColour(hello.mapOverlay?.halo),
    )

    private class Response(val body: String, val etag: String?)

    private class UnauthorizedException(message: String) : IOException(message)

    /** Returns null for 304 Not Modified, meaning "unchanged, nothing to do". */
    private fun request(path: String, ifNoneMatch: String? = null): Response? {
        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            // The header, never the query parameter: a pairing code in a URL would end
            // up in logs and in browser history.
            setRequestProperty("Authorization", "Bearer $pairingCode")
            setRequestProperty("Accept", "application/json")
            ifNoneMatch?.let { setRequestProperty("If-None-Match", it) }
        }
        try {
            return when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK ->
                    Response(
                        body = connection.inputStream.bufferedReader().use { it.readText() },
                        etag = connection.getHeaderField("ETag"),
                    )

                HttpURLConnection.HTTP_NOT_MODIFIED -> null

                HttpURLConnection.HTTP_UNAUTHORIZED ->
                    throw UnauthorizedException("pairing code rejected")

                else -> throw IOException("HTTP $code")
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TAG = "EnrouteWear"
        const val HELLO = "/hello"
        const val NAV = "/nav"
        const val ROUTE = "/route"
        const val NOTAMS = "/notams"

        // The phone rebuilds its NOTAM document every five minutes at the slowest, so
        // a minute here means a client is never more than about a minute behind while
        // costing one request per minute.
        const val NOTAM_PERIOD_MS = 60_000L
        const val CONNECT_TIMEOUT_MS = 3_000
        const val READ_TIMEOUT_MS = 5_000
    }
}
