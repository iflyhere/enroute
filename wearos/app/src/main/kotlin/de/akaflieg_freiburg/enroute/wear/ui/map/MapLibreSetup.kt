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

package de.akaflieg_freiburg.enroute.wear.ui.map

import android.content.Context
import android.util.Log
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import okhttp3.Cache
import java.io.File

/**
 * One-time setup for the map renderer.
 *
 * Two things have to happen before a MapView is constructed. The library needs its
 * own initialisation, and it needs an HTTP client that presents the pairing code --
 * every tile, glyph and sprite comes from the phone and the phone refuses a request
 * without it.
 *
 * The client refuses to speak to anything but that phone. Every address inside the
 * style points back at it today, but that is a property of the style rather than of
 * this app: a renderer asks for whatever addresses it is handed, and a style naming a
 * tile server would have the watch pulling map data straight off the internet. The map
 * is the pilot's own downloaded copy, under a licence that does not make the watch a
 * second client of a map service, and it has to keep working with the watch's Wi-Fi
 * switched off. Neither survives an unguarded client.
 *
 * The pairing code goes to that phone and nowhere else, for the same reason: a
 * credential attached unconditionally is one that leaks the first time an assumption
 * about where requests go stops holding.
 */
/**
 * Lets the paired phone through, refuses everything else.
 *
 * The refusal is a synthetic response rather than an exception: the renderer treats a
 * failed request as a tile it does not have and carries on drawing, which is what a
 * pilot wants, while an exception would be logged as a crash in the map thread. Nothing
 * reaches a socket either way.
 */
internal class PeerOnly(
    private val host: String,
    private val pairingCode: String,
    // Reported rather than logged directly, so that the refusal can be asserted in a
    // test: android.util.Log is not available off a device.
    private val onRefused: (String) -> Unit = { where -> Log.w(TAG, "refused a map request to " + where) },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.host.equals(host, ignoreCase = true)) {
            onRefused(request.url.host)
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(HTTP_FORBIDDEN)
                .message("the watch talks only to the paired phone")
                .body(ByteArray(0).toResponseBody(null))
                .build()
        }
        return chain.proceed(
            request.newBuilder()
                .header("Authorization", "Bearer " + pairingCode)
                .build(),
        )
    }

    private companion object {
        const val TAG = "EnrouteWear"
        const val HTTP_FORBIDDEN = 403
    }
}

object MapLibreSetup {

    private var initialised = false
    private var currentHost: String? = null
    private var currentCode: String = ""

    @Synchronized
    fun ensure(context: Context, host: String, pairingCode: String) {
        if (!initialised) {
            MapLibre.getInstance(context.applicationContext)
            initialised = true
        }

        // Rebuilt only when the peer changes: replacing the client discards the
        // renderer's connection pool, which on a watch is not free.
        if (currentHost == host && currentCode == pairingCode) {
            return
        }
        currentHost = host
        currentCode = pairingCode

        // A disk cache of its own, on top of the renderer's.
        //
        // The renderer keeps an ambient cache and honours the headers the phone sends,
        // so in principle this is a second copy of the same bytes. It is here because
        // the failure it guards against was reported from a real flight -- ten to
        // twenty seconds of map after every screen change -- and because the renderer's
        // cache is native, undocumented in its details, and not something this project
        // can test. Thirty-two megabytes on a watch is a real cost; a map that reloads
        // for twenty seconds in the air is a worse one.
        val cache = Cache(File(context.cacheDir, TILE_CACHE_DIR), TILE_CACHE_BYTES)

        HttpRequestUtil.setOkHttpClient(mapClient(cache, host, pairingCode))
    }

    /**
     * The renderer's HTTP client, built apart from the platform so it can be tested.
     *
     * @param cache Where fetched tiles are kept, or null in a test
     *
     * @param host The paired phone, and the only host this client will speak to
     *
     * @param pairingCode Presented to that phone on every request
     */
    fun mapClient(cache: Cache?, host: String, pairingCode: String): OkHttpClient =
        OkHttpClient.Builder()
            .apply { if (cache != null) cache(cache) }
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .addInterceptor(PeerOnly(host, pairingCode))
            .build()

    private const val TILE_CACHE_DIR = "map-tiles"
    private const val TILE_CACHE_BYTES = 32L * 1024L * 1024L

    private const val CONNECT_TIMEOUT_S = 5L
    private const val READ_TIMEOUT_S = 15L
}

/**
 * Converts "how many metres from the centre to the edge of the screen" into the zoom
 * number the renderer wants.
 *
 * The two do not resemble each other -- one is linear in metres, the other logarithmic
 * in tile subdivisions -- so this is the one piece of the map page that can be silently
 * wrong, and it is a pure function so that it can be tested.
 *
 * Web Mercator's scale depends on latitude, which is why the aircraft's latitude comes
 * in: the same zoom number covers roughly half as much ground at 60 degrees north as it
 * does at the equator.
 *
 * @param halfSpanMetres Distance from the centre of the screen to its edge
 *
 * @param radiusPixels Half the width of the map view, in pixels
 *
 * @param latitudeDeg Latitude at the centre of the view
 *
 * @return A zoom level clamped to what the renderer accepts
 */
fun zoomForHalfSpan(halfSpanMetres: Double, radiusPixels: Float, latitudeDeg: Double): Double {
    if (halfSpanMetres <= 0.0 || radiusPixels <= 0f) {
        return DEFAULT_ZOOM
    }
    val metresPerPixel = halfSpanMetres / radiusPixels
    val equatorial = EQUATOR_METRES_PER_PIXEL * cos(Math.toRadians(latitudeDeg))
    if (metresPerPixel <= 0.0 || equatorial <= 0.0) {
        return DEFAULT_ZOOM
    }
    val zoom = ln(equatorial / metresPerPixel) / ln(2.0)
    return min(MAX_ZOOM, max(MIN_ZOOM, zoom))
}

// Metres per pixel at zoom 0 on the equator, for a 256 pixel tile.
private const val EQUATOR_METRES_PER_PIXEL = 156543.03392804097

// The downloaded maps hold zoom 6 to 10. Below 6 there is nothing to draw; above 10 the
// renderer overzooms the deepest tiles, which is what a pilot wants when looking closely
// at an aerodrome.
private const val MIN_ZOOM = 4.0
private const val MAX_ZOOM = 14.0
private const val DEFAULT_ZOOM = 9.0
