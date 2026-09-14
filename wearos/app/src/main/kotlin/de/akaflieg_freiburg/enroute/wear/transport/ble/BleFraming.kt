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

import java.security.MessageDigest
import java.util.zip.Inflater

/**
 * The wire format of Transport 2, without any Bluetooth in it.
 *
 * Separated out because this is the half that can be wrong in a way no log line
 * reports: a reassembler that loses a fragment produces JSON that fails to parse, and
 * the cause looks like a radio problem. All of it is pure, so it is tested against
 * fragments built by the same rules the phone uses.
 *
 * The format is specified in doc/companion-protocol.md and is not this file's to
 * change.
 */

/** Bit 7 of a fragment header marks the last fragment of a document. */
const val LAST_FRAGMENT_MASK = 0x80

/** Bits 0 to 6 carry the fragment index modulo 128. */
const val FRAGMENT_INDEX_MASK = 0x7F

/**
 * Reassembles a document from notification fragments.
 *
 * Fragments arrive in order on a GATT connection, so this trusts the order but not the
 * completeness: an index that does not continue the sequence means something was
 * dropped, and a document assembled from what did arrive would be silently wrong.
 * Rather than guess, the partial document is discarded and the caller asks again.
 *
 * The index is modulo 128, so it wraps on a long transfer. Counting arrivals rather
 * than trusting the index is what makes the wrap a non-event.
 */
class Reassembler {

    private val buffer = StringBuilder()
    private val bytes = mutableListOf<Byte>()
    private var expectedIndex = 0

    /**
     * Feeds one notification.
     *
     * @return the complete document once the last fragment arrives, otherwise null
     */
    fun accept(fragment: ByteArray): ByteArray? {
        if (fragment.isEmpty()) {
            return null
        }
        val header = fragment[0].toInt()
        val index = header and FRAGMENT_INDEX_MASK
        val isLast = (header and LAST_FRAGMENT_MASK) != 0

        if (index != expectedIndex % 128) {
            // A gap. Everything held so far belongs to a document that can no longer
            // be completed, and keeping it would corrupt the next one.
            reset()
            return null
        }

        for (position in 1 until fragment.size) {
            bytes.add(fragment[position])
        }
        expectedIndex += 1

        if (!isLast) {
            return null
        }
        val document = bytes.toByteArray()
        reset()
        return document
    }

    /** Forgets a partial document, after a disconnect or a gap. */
    fun reset() {
        buffer.setLength(0)
        bytes.clear()
        expectedIndex = 0
    }
}

/**
 * Inflates what `qCompress` produced.
 *
 * **Not gzip, and not a bare zlib stream either.** Qt prefixes four big-endian bytes
 * of uncompressed length and then the zlib stream, so a client skips those four bytes
 * and inflates the remainder. Handing the whole thing to GZIPInputStream fails, and
 * handing it to Inflater unskipped fails differently; both look like a corrupt
 * transfer.
 *
 * @return the document, or null if the stream does not inflate
 */
fun inflateQCompressed(compressed: ByteArray): ByteArray? {
    if (compressed.size <= 4) {
        return null
    }
    // The declared length is used to size the output, not trusted as truth: a claim of
    // hundreds of megabytes from a damaged transfer must not become an allocation.
    val declared = ((compressed[0].toInt() and 0xFF) shl 24) or
        ((compressed[1].toInt() and 0xFF) shl 16) or
        ((compressed[2].toInt() and 0xFF) shl 8) or
        (compressed[3].toInt() and 0xFF)
    if (declared < 0 || declared > MAX_DOCUMENT_BYTES) {
        return null
    }

    val inflater = Inflater()
    return try {
        inflater.setInput(compressed, 4, compressed.size - 4)
        val out = ByteArray(declared.coerceAtLeast(1))
        var written = 0
        while (!inflater.finished() && written < out.size) {
            val produced = inflater.inflate(out, written, out.size - written)
            if (produced == 0) {
                break
            }
            written += produced
        }
        if (written == 0) null else out.copyOf(written)
    } catch (malformed: java.util.zip.DataFormatException) {
        null
    } finally {
        inflater.end()
    }
}

/**
 * Whether a document matches the hash the phone announced.
 *
 * The hash is the first four bytes of the SHA-1 of the **uncompressed** document, hex
 * encoded. Checking it is what separates "the transfer was damaged" from "the phone
 * sent something this version cannot read" -- two failures that need different
 * responses and look identical without it.
 */
fun matchesHash(document: ByteArray, announced: String?): Boolean {
    if (announced.isNullOrBlank()) {
        // Nothing to check against. Treated as a match rather than a failure: an older
        // phone that does not announce a hash is not a damaged transfer.
        return true
    }
    val digest = MessageDigest.getInstance("SHA-1").digest(document)
    val hex = digest.take(4).joinToString("") { byte ->
        ((byte.toInt() and 0xFF) + 0x100).toString(16).substring(1)
    }
    return hex.equals(announced.trim(), ignoreCase = true)
}

/**
 * A ceiling on a single document.
 *
 * A hundred-waypoint route is about nine kilobytes. A megabyte is far past anything
 * this protocol carries and well short of anything that would trouble a watch, which
 * is what a sanity limit should be.
 */
const val MAX_DOCUMENT_BYTES = 1_048_576

/**
 * The documents this client fetches over Bluetooth, in the order it wants them.
 *
 * Preferences first, because they decide which screens exist at all and in what order,
 * and arriving after them means rearranging under the pilot's finger. Then the route,
 * which every navigation frame refers to, then what a pilot looks at soonest. Traffic is last on purpose: it changes every second and a slow link that
 * spent itself keeping traffic current would never finish anything else.
 */
val DOCUMENT_ORDER =
    listOf("prefs", "route", "notams", "nearby", "weather", "log", "vacs", "traffic")

/**
 * Which documents are out of date, in the order they should be asked for.
 *
 * @param published what the phone says each document is at
 * @param held what this client last received, by name
 *
 * A document the phone has never published (revision zero) is not requested: there is
 * nothing behind it, and asking would spend a round trip to be told so. A document the
 * client has never held is requested as soon as the phone has one.
 */
fun staleDocuments(published: Map<String, Long>, held: Map<String, Long>): List<String> =
    DOCUMENT_ORDER.filter { name ->
        val theirs = published[name] ?: 0L
        theirs > 0L && held[name] != theirs
    }
