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

import de.akaflieg_freiburg.enroute.wear.data.ConnectionState
import de.akaflieg_freiburg.enroute.wear.data.SessionState
import de.akaflieg_freiburg.enroute.wear.domain.FlightStatus
import de.akaflieg_freiburg.enroute.wear.domain.Measured
import de.akaflieg_freiburg.enroute.wear.domain.NavFrame
import de.akaflieg_freiburg.enroute.wear.domain.OwnPosition
import de.akaflieg_freiburg.enroute.wear.domain.RouteStatus
import de.akaflieg_freiburg.enroute.wear.transport.Backoff
import de.akaflieg_freiburg.enroute.wear.transport.FailureReason
import de.akaflieg_freiburg.enroute.wear.ui.data.Freshness
import de.akaflieg_freiburg.enroute.wear.ui.data.formatAge
import de.akaflieg_freiburg.enroute.wear.ui.data.freshnessOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FreshnessTest {

    private val frame = NavFrame(
        sessionId = 1,
        navRevision = 1,
        routeRevision = 1,
        generatedAtEpochSeconds = 1_000,
        status = RouteStatus.OnRoute,
        flightStatus = FlightStatus.Flight,
        note = "",
        legIndex = 0,
        position = OwnPosition.Unknown,
        next = null,
        final = null,
        statusText = "",
        flightLevel = Measured.Absent,
        flightLevelImplausible = false,
    )

    private fun connected(frame: NavFrame? = this.frame) =
        SessionState(connection = ConnectionState.Connected, frame = frame)

    @Test
    fun `no frame means no data, whatever the connection says`() {
        assertEquals(Freshness.NoData, freshnessOf(connected(frame = null), null))
        assertEquals(Freshness.NoData, freshnessOf(connected(frame = null), 0))
    }

    @Test
    fun `age thresholds are three and ten seconds`() {
        assertEquals(Freshness.Live, freshnessOf(connected(), 0))
        assertEquals(Freshness.Live, freshnessOf(connected(), 2))
        assertEquals(Freshness.Stale, freshnessOf(connected(), 3))
        assertEquals(Freshness.Stale, freshnessOf(connected(), 9))
        assertEquals(Freshness.Old, freshnessOf(connected(), 10))
        assertEquals(Freshness.Old, freshnessOf(connected(), 600))
    }

    @Test
    fun `a dropped link reads as disconnected even if the last frame was recent`() {
        // The pilot must be able to tell "the link went away one second ago" from
        // "the link is fine". Both would otherwise render identically.
        val retrying = SessionState(
            connection = ConnectionState.Retrying(FailureReason.PeerClosed, 1),
            frame = frame,
        )
        assertEquals(Freshness.Disconnected, freshnessOf(retrying, 0))
    }

    @Test
    fun `age formatting is minutes and padded seconds`() {
        assertEquals("0:00", formatAge(0))
        assertEquals("0:07", formatAge(7))
        assertEquals("1:00", formatAge(60))
        assertEquals("12:03", formatAge(723))
        // A watch clock behind the phone clock would otherwise render a negative age.
        assertEquals("0:00", formatAge(-5))
    }
}

class BackoffTest {

    // Fixed seed so the jitter is reproducible; the bounds are what matter.
    private fun backoff() = Backoff(random = Random(1))

    @Test
    fun `delays grow exponentially and then stop at the cap`() {
        val b = backoff()
        val bounds = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)
        bounds.forEach { nominal ->
            val actual = b.nextMs()
            assertTrue(
                "expected within 20% of $nominal but was $actual",
                actual >= (nominal * 0.8).toLong() && actual <= (nominal * 1.2).toLong(),
            )
        }
    }

    @Test
    fun `reset returns to the base delay`() {
        val b = backoff()
        repeat(5) { b.nextMs() }
        assertEquals(5, b.attempt)
        b.reset()
        assertEquals(0, b.attempt)
        assertTrue(b.nextMs() <= 1_200)
    }
}
