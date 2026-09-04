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
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * One-time setup for the map renderer.
 *
 * Two things have to happen before a MapView is constructed. The library needs its
 * own initialisation, and it needs an HTTP client that presents the pairing code --
 * every tile, glyph and sprite comes from the phone and the phone refuses a request
 * without it.
 *
 * The interceptor attaches the code **only** to requests aimed at the configured
 * phone. The renderer will never ask anywhere else, since the style names no remote
 * service, but a credential that is added unconditionally is one that leaks the first
 * time that assumption stops holding.
 */
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

        val client = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                val outgoing = if (request.url.host == host) {
                    request.newBuilder()
                        .header("Authorization", "Bearer $pairingCode")
                        .build()
                } else {
                    request
                }
                chain.proceed(outgoing)
            }
            .build()

        HttpRequestUtil.setOkHttpClient(client)
    }

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
