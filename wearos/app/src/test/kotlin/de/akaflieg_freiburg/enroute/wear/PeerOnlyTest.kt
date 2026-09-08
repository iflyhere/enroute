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

package de.akaflieg_freiburg.enroute.wear

import de.akaflieg_freiburg.enroute.wear.ui.map.PeerOnly
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watch's map renderer may fetch from the paired phone and from nowhere else.
 *
 * This is the guarantee behind two separate promises. The map is the pilot's own
 * downloaded copy, under a licence that does not make a watch a second client of a map
 * service; and the map has to keep working with the watch's Wi-Fi switched off, which it
 * cannot if the renderer is quietly reaching the internet for part of it.
 *
 * A renderer follows whatever addresses its style hands it, so the promise cannot rest
 * on the style being right. It rests on this.
 */
class PeerOnlyTest {

    private val phone = "192.168.1.50"

    @Test
    fun `a request to the phone is passed on, carrying the pairing code`() {
        val chain = FakeChain("http://192.168.1.50:8973/enroute/v1/map/base-1/9/268/177.pbf")
        val refused = mutableListOf<String>()
        val response = PeerOnly(phone, "914430") { where -> refused.add(where) }.intercept(chain)

        assertTrue(refused.isEmpty())

        assertEquals(200, response.code)
        assertTrue(chain.proceeded)
        assertEquals("Bearer 914430", chain.forwarded?.header("Authorization"))
    }

    @Test
    fun `a request anywhere else is refused before it reaches a socket`() {
        // The shape of the failure this exists to stop: a style that names a tile
        // server. Nothing about it looks wrong until a watch with no Wi-Fi shows no map,
        // or a map service starts seeing traffic that was never meant to reach it.
        val chain = FakeChain("https://tiles.example.com/v1/9/268/177.pbf")
        val refused = mutableListOf<String>()
        val response = PeerOnly(phone, "914430") { where -> refused.add(where) }.intercept(chain)

        assertEquals(403, response.code)
        assertFalse(chain.proceeded)
        // Reported, not silent: a map that quietly stops asking for part of itself is
        // indistinguishable from one that has everything it needs.
        assertEquals(listOf("tiles.example.com"), refused)
    }

    @Test
    fun `the pairing code never leaves for another host`() {
        val chain = FakeChain("https://tiles.example.com/anything")
        PeerOnly(phone, "914430") {}.intercept(chain)
        assertNull(chain.forwarded)
    }

    @Test
    fun `a host that differs only in case is still the phone`() {
        // Hostnames are case insensitive, and the phone's address can arrive from the
        // beacon, from a handover or from what the pilot typed.
        val chain = FakeChain("http://Phone.Local:8973/enroute/v1/map/style.json")
        val response = PeerOnly("phone.local", "914430") {}.intercept(chain)
        assertEquals(200, response.code)
        assertTrue(chain.proceeded)
    }

    /**
     * Just enough of an OkHttp chain to drive one interceptor.
     *
     * Everything an interceptor is not allowed to touch here throws, so a future change
     * that starts using it fails loudly in this test rather than quietly on a wrist.
     */
    private class FakeChain(url: String) : Interceptor.Chain {
        private val request = Request.Builder().url(url).build()
        var proceeded = false
        var forwarded: Request? = null

        override fun request(): Request = request

        override fun proceed(request: Request): Response {
            proceeded = true
            forwarded = request
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ByteArray(0).toResponseBody(null))
                .build()
        }

        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        override fun readTimeoutMillis(): Int = 0
        override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        override fun writeTimeoutMillis(): Int = 0
        override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
    }
}
