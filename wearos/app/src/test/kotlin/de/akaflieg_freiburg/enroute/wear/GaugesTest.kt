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
import de.akaflieg_freiburg.enroute.wear.transport.FailureReason
import de.akaflieg_freiburg.enroute.wear.ui.data.connectionMessage
import de.akaflieg_freiburg.enroute.wear.ui.instruments.Instrument
import de.akaflieg_freiburg.enroute.wear.ui.instruments.altitudeDigits
import de.akaflieg_freiburg.enroute.wear.ui.instruments.dialAngleDeg
import de.akaflieg_freiburg.enroute.wear.ui.instruments.isUsable
import de.akaflieg_freiburg.enroute.wear.ui.instruments.roundedVerticalSpeed
import de.akaflieg_freiburg.enroute.wear.ui.instruments.spanAngleDeg
import de.akaflieg_freiburg.enroute.wear.ui.instruments.speedFullScale
import de.akaflieg_freiburg.enroute.wear.ui.instruments.speedUnitFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dials, and the one connection message that caused a visible defect.
 *
 * A needle in the wrong place does not look wrong, which is the whole reason this
 * arithmetic sits in free functions.
 */
class GaugesTest {

    @Test
    fun `a dial reads zero at the top and wraps once per revolution`() {
        assertEquals(0.0, dialAngleDeg(0.0, 1_000.0), 0.001)
        assertEquals(90.0, dialAngleDeg(250.0, 1_000.0), 0.001)
        assertEquals(180.0, dialAngleDeg(500.0, 1_000.0), 0.001)
        // 3500 ft reads the same as 500 ft on the hundreds hand, which is what an
        // altimeter does and why it has a second hand.
        assertEquals(dialAngleDeg(500.0, 1_000.0), dialAngleDeg(3_500.0, 1_000.0), 0.001)
    }

    @Test
    fun `a span dial clamps instead of wrapping`() {
        // A needle that ran off the end and reappeared at the other side would read
        // as its own opposite.
        assertEquals(210.0, spanAngleDeg(-50.0, 0.0, 100.0, 210.0, 300.0), 0.001)
        assertEquals(510.0, spanAngleDeg(150.0, 0.0, 100.0, 210.0, 300.0), 0.001)
        assertEquals(360.0, spanAngleDeg(50.0, 0.0, 100.0, 210.0, 300.0), 0.001)
    }

    @Test
    fun `a degenerate span does not divide by zero`() {
        assertEquals(210.0, spanAngleDeg(5.0, 10.0, 10.0, 210.0, 300.0), 0.001)
        assertEquals(0.0, dialAngleDeg(5.0, 0.0), 0.001)
    }

    @Test
    fun `the speed scale steps and follows the unit`() {
        assertEquals(60.0, speedFullScale(40.0, "kn"), 0.001)
        assertEquals(120.0, speedFullScale(80.0, "kn"), 0.001)
        assertEquals(120.0, speedFullScale(90.0, "kmh"), 0.001)
        // Past the top of the ladder the dial stays at the top rather than vanishing.
        assertEquals(300.0, speedFullScale(999.0, "kn"), 0.001)
    }

    @Test
    fun `the speed unit follows the distance preference, as the app does`() {
        assertEquals("kn", speedUnitFor("nm"))
        assertEquals("kmh", speedUnitFor("km"))
        assertEquals("mph", speedUnitFor("mil"))
        assertEquals("kn", speedUnitFor("something-new"))
    }

    @Test
    fun `readings are rounded to what the source supports`() {
        assertEquals(0.7, roundedVerticalSpeed(0.6789), 0.0001)
        assertEquals(1230, altitudeDigits(1234.0, "ft"))
        assertEquals(1234, altitudeDigits(1234.0, "m"))
    }

    @Test
    fun `an unusable value is not drawn`() {
        assertFalse(isUsable(null))
        assertFalse(isUsable(Double.NaN))
        assertFalse(isUsable(Double.POSITIVE_INFINITY))
        assertTrue(isUsable(0.0))
        assertTrue(isUsable(-3.5))
    }

    @Test
    fun `the instruments cycle and come back round`() {
        assertEquals(Instrument.Speed, Instrument.Altimeter.next())
        assertEquals(Instrument.Variometer, Instrument.Speed.next())
        assertEquals(Instrument.Altimeter, Instrument.Variometer.next())
    }

    @Test
    fun `the connection message does not change while a retry is in flight`() {
        // The defect this exists to stop: the retry loop passes through Connecting on
        // every attempt, so a message driven by the connection state alone alternated
        // once per backoff period -- a flicker every ten seconds on the watch.
        val whileFailed = connectionMessage(
            ConnectionState.Connecting,
            FailureReason.Unreachable,
        )
        val whileRetrying = connectionMessage(
            ConnectionState.Retrying(FailureReason.Unreachable, 3),
            FailureReason.Unreachable,
        )
        assertEquals(whileFailed, whileRetrying)
        assertEquals("No connection", whileFailed)
    }

    @Test
    fun `a refused code says so and keeps saying so`() {
        assertEquals(
            "Wrong pairing code",
            connectionMessage(ConnectionState.Rejected, null),
        )
        assertEquals(
            "Wrong pairing code",
            connectionMessage(ConnectionState.Connecting, FailureReason.Unauthorized),
        )
    }

    @Test
    fun `before anything has happened it says so plainly`() {
        assertEquals("Not connected", connectionMessage(ConnectionState.Idle, null))
        assertEquals("Connecting", connectionMessage(ConnectionState.Connecting, null))
    }
}
