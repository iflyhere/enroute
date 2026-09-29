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

import de.akaflieg_freiburg.enroute.wear.transport.ble.DOCUMENT_ORDER
import de.akaflieg_freiburg.enroute.wear.transport.ble.LAST_FRAGMENT_MASK
import de.akaflieg_freiburg.enroute.wear.transport.ble.Reassembler
import de.akaflieg_freiburg.enroute.wear.transport.ble.inflateQCompressed
import de.akaflieg_freiburg.enroute.wear.transport.ble.matchesHash
import de.akaflieg_freiburg.enroute.wear.transport.ble.staleDocuments
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Bluetooth wire format, against fragments built the way the phone builds them.
 *
 * This is the half of the transport that fails silently. A reassembler that loses a
 * fragment yields JSON that will not parse, and the symptom looks like a radio
 * problem rather than an arithmetic one.
 */
class BleFramingTest {

    /** Fragments a document exactly as BleTransport::sendWindow does. */
    private fun fragment(document: ByteArray, payload: Int = 19): List<ByteArray> {
        val total = (document.size + payload - 1) / payload
        return (0 until total).map { index ->
            val marker = if (index == total - 1) LAST_FRAGMENT_MASK else 0
            val header = (marker or (index % 128)).toByte()
            byteArrayOf(header) +
                document.copyOfRange(index * payload, minOf((index + 1) * payload, document.size))
        }
    }

    /** What Qt's qCompress produces: four big-endian length bytes, then zlib. */
    private fun qCompress(document: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(document)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(1024)
        while (!deflater.finished()) {
            out.write(chunk, 0, deflater.deflate(chunk))
        }
        deflater.end()
        val size = document.size
        return byteArrayOf(
            (size ushr 24).toByte(),
            (size ushr 16).toByte(),
            (size ushr 8).toByte(),
            size.toByte(),
        ) + out.toByteArray()
    }

    @Test
    fun `a single-fragment document is complete at once`() {
        val document = "{}".toByteArray()
        val fragments = fragment(document)
        assertEquals(1, fragments.size)
        // The specification says a single-fragment frame begins with 0x80.
        assertEquals(0x80, fragments[0][0].toInt() and 0xFF)
        assertArrayEquals(document, Reassembler().accept(fragments[0]))
    }

    @Test
    fun `a document spanning many fragments comes back byte for byte`() {
        val document = ("{\"wp\":[" + (1..200).joinToString(",") { "\"$it\"" } + "]}").toByteArray()
        val reassembler = Reassembler()
        var result: ByteArray? = null
        val fragments = fragment(document)
        // Enough to exercise the sequence without claiming a number the document does
        // not produce: two hundred short entries come to about sixty fragments at
        // nineteen payload bytes each. The wrap past 128 has its own test.
        assertTrue("expected many fragments, got " + fragments.size, fragments.size > 40)
        fragments.forEach { piece -> result = reassembler.accept(piece) ?: result }
        assertArrayEquals(document, result)
    }

    @Test
    fun `the index wrapping at 128 is not mistaken for a gap`() {
        // The header carries the index modulo 128, so a transfer of more than 128
        // fragments repeats it. Counting arrivals rather than trusting the number is
        // what makes the wrap a non-event -- and 111 fragments is a real route.
        val document = ByteArray(19 * 300) { (it % 251).toByte() }
        val reassembler = Reassembler()
        var result: ByteArray? = null
        fragment(document).forEach { piece -> result = reassembler.accept(piece) ?: result }
        assertArrayEquals(document, result)
    }

    @Test
    fun `a dropped fragment discards the document instead of corrupting it`() {
        val document = ByteArray(19 * 5) { it.toByte() }
        val fragments = fragment(document)
        val reassembler = Reassembler()
        reassembler.accept(fragments[0])
        // Fragment 1 never arrives.
        reassembler.accept(fragments[2])
        reassembler.accept(fragments[3])
        // Even the last fragment must not complete a document with a hole in it.
        assertNull(reassembler.accept(fragments[4]))
    }

    @Test
    fun `a reset makes the next document start cleanly`() {
        val first = ByteArray(19 * 3) { 1 }
        val second = ByteArray(19 * 2) { 2 }
        val reassembler = Reassembler()
        reassembler.accept(fragment(first)[0])
        reassembler.reset()
        var result: ByteArray? = null
        fragment(second).forEach { piece -> result = reassembler.accept(piece) ?: result }
        assertArrayEquals(second, result)
    }

    @Test
    fun `an empty notification is ignored rather than throwing`() {
        assertNull(Reassembler().accept(ByteArray(0)))
    }

    @Test
    fun `what qCompress produced inflates again`() {
        val document = ("{\"weather\":" + "x".repeat(4000) + "}").toByteArray()
        val compressed = qCompress(document)
        // Worth compressing at all, or the transport would be spending CPU for nothing.
        assertTrue(compressed.size < document.size / 2)
        assertArrayEquals(document, inflateQCompressed(compressed))
    }

    @Test
    fun `a bare zlib stream without Qt's length prefix does not inflate`() {
        // The trap this function exists for: skipping the four bytes is not optional,
        // and getting it wrong looks exactly like a damaged transfer.
        val document = "{\"a\":1}".toByteArray()
        val withPrefix = qCompress(document)
        val withoutPrefix = withPrefix.copyOfRange(4, withPrefix.size)
        assertNull(inflateQCompressed(withoutPrefix))
    }

    @Test
    fun `a damaged stream returns null rather than throwing`() {
        val compressed = qCompress("{\"a\":1}".toByteArray())
        compressed[compressed.size / 2] = (compressed[compressed.size / 2] + 7).toByte()
        // Either null or something that fails its hash. What it must not do is throw.
        val inflated = inflateQCompressed(compressed)
        if (inflated != null) {
            assertFalse(inflated.contentEquals("{\"a\":1}".toByteArray()))
        }
    }

    @Test
    fun `an absurd declared length is refused before it is allocated`() {
        val compressed = qCompress("{}".toByteArray())
        compressed[0] = 0x7F
        assertNull(inflateQCompressed(compressed))
    }

    @Test
    fun `the hash is the first four bytes of the SHA-1 of the uncompressed document`() {
        val document = "{\"routeRev\":7}".toByteArray()
        val digest = MessageDigest.getInstance("SHA-1").digest(document)
        val hex = digest.take(4).joinToString("") { byte ->
            ((byte.toInt() and 0xFF) + 0x100).toString(16).substring(1)
        }
        assertTrue(matchesHash(document, hex))
        assertTrue(matchesHash(document, hex.uppercase()))
        assertFalse(matchesHash(document, "deadbeef"))
    }

    @Test
    fun `a phone that announces no hash is not treated as a damaged transfer`() {
        val document = "{}".toByteArray()
        assertTrue(matchesHash(document, null))
        assertTrue(matchesHash(document, ""))
    }

    @Test
    fun `only the documents whose revision moved are asked for`() {
        val published = mapOf(
            "route" to 2L, "notams" to 7L, "weather" to 3L,
            "vacs" to 1L, "log" to 4L, "nearby" to 5L, "traffic" to 9L,
        )
        val held = mapOf("route" to 2L, "notams" to 6L, "weather" to 3L)
        // The route and the weather are current; the NOTAMs moved, and the rest have
        // never been held at all.
        assertEquals(
            listOf("notams", "nearby", "log", "vacs", "traffic"),
            staleDocuments(published, held),
        )
    }

    @Test
    fun `a document the phone has never published is not asked for`() {
        // Revision zero means there is nothing behind it. Asking would spend a round
        // trip on a slow link to be told so, once a second, forever.
        val published = mapOf("route" to 1L, "notams" to 0L, "weather" to 0L)
        assertEquals(listOf("route"), staleDocuments(published, emptyMap()))
    }

    @Test
    fun `nothing is asked for when everything is current`() {
        val published = mapOf("route" to 4L, "notams" to 2L)
        assertTrue(staleDocuments(published, mapOf("route" to 4L, "notams" to 2L)).isEmpty())
    }

    @Test
    fun `preferences come first, then the route, and traffic last`() {
        // Preferences decide which screens exist and in what order, so anything that
        // arrives before them gets rearranged under the pilot's finger. The route is
        // next because every navigation frame refers to it. Traffic is last because it
        // changes every second, and a slow link that kept it current would never finish
        // anything else.
        val published = DOCUMENT_ORDER.associateWith { 1L }
        val order = staleDocuments(published, emptyMap())
        assertEquals(listOf("prefs", "route"), order.take(2))
        assertEquals("traffic", order.last())
    }
}
