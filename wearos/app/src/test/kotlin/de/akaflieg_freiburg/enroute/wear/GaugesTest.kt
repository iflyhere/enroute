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
import de.akaflieg_freiburg.enroute.wear.R
import de.akaflieg_freiburg.enroute.wear.ui.data.connectionMessage
import de.akaflieg_freiburg.enroute.wear.ui.instruments.Instrument
import de.akaflieg_freiburg.enroute.wear.ui.instruments.altitudeDigits
import de.akaflieg_freiburg.enroute.wear.ui.instruments.dialAngleDeg
import de.akaflieg_freiburg.enroute.wear.ui.instruments.isUsable
import de.akaflieg_freiburg.enroute.wear.ui.instruments.roundedVerticalSpeed
import de.akaflieg_freiburg.enroute.wear.ui.instruments.spanAngleDeg
import de.akaflieg_freiburg.enroute.wear.ui.instruments.varioAngleDeg
import de.akaflieg_freiburg.enroute.wear.ui.instruments.speedFullScale
import de.akaflieg_freiburg.enroute.wear.ui.instruments.speedTickStep
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
        // The bottom rung is well above a cruise, not just above a standstill: a glider
        // at sixty knots on a sixty-knot dial reads against the stop, where the needle
        // says nothing the number did not.
        assertEquals(120.0, speedFullScale(40.0, "kn"), 0.001)
        assertEquals(120.0, speedFullScale(80.0, "kn"), 0.001)
        assertEquals(200.0, speedFullScale(90.0, "kmh"), 0.001)
        // Past the top of the ladder the dial stays at the top rather than vanishing.
        assertEquals(300.0, speedFullScale(999.0, "kn"), 0.001)
    }

    @Test
    fun `a speed dial is marked in round numbers`() {
        // Instruments are marked in tens. A face reading 6, 12, 18 is a chart axis.
        assertEquals(20.0, speedTickStep(120.0), 0.001)
        assertEquals(20.0, speedTickStep(200.0), 0.001)
        assertEquals(50.0, speedTickStep(300.0), 0.001)
        assertEquals(50.0, speedTickStep(500.0), 0.001)
        // And never so many that the numbers crowd each other on a wrist.
        listOf(120.0, 200.0, 300.0, 500.0).forEach { scale ->
            assertTrue(scale / speedTickStep(scale) <= 10.0)
        }
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
        assertEquals(R.string.state_no_connection, whileFailed)
    }

    @Test
    fun `a refused code says so and keeps saying so`() {
        assertEquals(
            R.string.state_wrong_code,
            connectionMessage(ConnectionState.Rejected, null),
        )
        assertEquals(
            R.string.state_wrong_code,
            connectionMessage(ConnectionState.Connecting, FailureReason.Unauthorized),
        )
    }

    @Test
    fun `before anything has happened it says so plainly`() {
        assertEquals(R.string.state_not_connected, connectionMessage(ConnectionState.Idle, null))
        assertEquals(R.string.state_connecting, connectionMessage(ConnectionState.Connecting, null))
    }

    @Test
    fun `the vario reads zero at nine o'clock`() {
        // Measured against the instrument it is modelled on: zero is on the left, not at
        // the top. Angles here run clockwise from twelve, so nine o'clock is 270.
        assertEquals(270.0, varioAngleDeg(0.0, 5.0), 0.01)
    }

    @Test
    fun `climb goes clockwise over the top and sink under the bottom`() {
        // Half scale up is straight at twelve o'clock and half scale down at six --
        // which is exactly where the 10 sits on the instrument this is modelled on,
        // top and bottom, with 20 out at three o'clock where the two halves meet.
        assertEquals(360.0, varioAngleDeg(2.5, 5.0), 0.01)
        assertEquals(180.0, varioAngleDeg(-2.5, 5.0), 0.01)
    }

    @Test
    fun `both ends of the scale meet at three o'clock`() {
        // 450 and 90 are the same direction. Full climb and full sink arrive at the same
        // place from opposite ways round, exactly as on the real instrument.
        assertEquals(450.0, varioAngleDeg(5.0, 5.0), 0.01)
        assertEquals(90.0, varioAngleDeg(-5.0, 5.0), 0.01)
    }

    @Test
    fun `a vario needle never wraps past the pegs`() {
        // A needle that ran off the top and reappeared at the bottom would read a strong
        // climb as a strong sink, which is the one mistake this instrument must not make.
        assertEquals(varioAngleDeg(5.0, 5.0), varioAngleDeg(50.0, 5.0), 0.01)
        assertEquals(varioAngleDeg(-5.0, 5.0), varioAngleDeg(-50.0, 5.0), 0.01)
    }

    @Test
    fun `a vario with no scale does not divide by zero`() {
        assertEquals(270.0, varioAngleDeg(3.0, 0.0), 0.01)
    }

    @Test
    fun `the altimeter's two hands turn at ten to one`() {
        // The long hand once round the thousand, the short hand once round ten thousand.
        // At 1500 feet the long hand is at six o'clock and the short one is a sixth of
        // the way from one to two.
        assertEquals(180.0, dialAngleDeg(1500.0, 1_000.0), 0.01)
        assertEquals(54.0, dialAngleDeg(1500.0, 10_000.0), 0.01)
    }
}
