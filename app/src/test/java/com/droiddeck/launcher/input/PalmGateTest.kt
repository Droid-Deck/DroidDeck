package com.droiddeck.launcher.input

import com.droiddeck.launcher.input.PalmGate.Pen.CONTACT
import com.droiddeck.launcher.input.PalmGate.Pen.HOVER
import org.junit.Assert.*
import org.junit.Test

class PalmGateTest {
    private val logs = mutableListOf<String>()
    private val cancels = mutableListOf<Long>()
    private var fingers = 0
    private val gate = PalmGate { logs += it }.apply { register { t -> cancels += t; fingers } }

    @Test fun aHoverArmsTheGateUntilTheGraceAfterItLeaves() {
        assertFalse(gate.armed(1_000))
        gate.onPen(1_000, HOVER, PEN)
        assertTrue(gate.armed(1_100))
        gate.leave(1_100)
        assertTrue(gate.armed(1_100 + PalmGate.GRACE_MS - 1))
        assertFalse(gate.armed(1_100 + PalmGate.GRACE_MS))
    }

    @Test fun aPenThatOnlyTouchesStaysArmedBetweenStrokes() {
        gate.onPen(1_000, CONTACT, PEN)
        gate.leave(1_100)
        gate.onPen(1_400, CONTACT, PEN)
        gate.leave(1_500)
        assertTrue(gate.armed(1_899))
        assertFalse(gate.armed(1_900))
        assertEquals(listOf(1_000L), cancels)
    }

    @Test fun fingersAreCancelledOnlyWhenThePenArmsTheGate() {
        fingers = 2
        gate.onPen(1_000, HOVER, PEN)
        gate.onPen(1_010, HOVER, PEN)
        gate.leave(1_050)
        gate.onPen(1_060, CONTACT, PEN)
        assertEquals(listOf(1_000L), cancels)
        assertEquals(listOf("palm: pen near, cancelled 2 finger(s)"), logs)
        fingers = 0
        gate.leave(1_100)
        gate.onPen(2_000, HOVER, PEN)
        assertEquals(listOf(1_000L, 2_000L), cancels)
        assertEquals(1, logs.size)
    }

    @Test fun aSilentHoverTimesOutAndItsNextSampleCancelsAgain() {
        gate.onPen(1_000, HOVER, PEN)
        gate.expire(1_000 + PenStream.HOVER_TIMEOUT_MS - 1)
        assertTrue(gate.armed(1_000 + PenStream.HOVER_TIMEOUT_MS - 1))
        gate.expire(1_000 + PenStream.HOVER_TIMEOUT_MS)
        assertFalse(gate.armed(1_000 + PenStream.HOVER_TIMEOUT_MS))
        assertEquals(listOf("palm: pen hover timed out"), logs)
        gate.onPen(3_500, HOVER, PEN)
        assertEquals(listOf(1_000L, 3_500L), cancels)
    }

    @Test fun aContactNeverTimesOut() {
        gate.onPen(1_000, CONTACT, PEN)
        gate.expire(60_000)
        assertTrue(gate.armed(60_000))
    }

    @Test fun forcingAPenOutKeepsTheGraceButNotForAStaleHover() {
        gate.onPen(1_000, HOVER, PEN)
        gate.forceOut(1_100)
        assertTrue(gate.armed(1_499))
        assertFalse(gate.armed(1_500))
        gate.onPen(2_000, HOVER, PEN)
        gate.forceOut(4_500)
        assertFalse(gate.armed(4_500))
    }

    @Test fun removingThePenDeviceForcesItOut() {
        gate.onPen(1_000, HOVER, PEN)
        gate.deviceRemoved(PEN + 1, 1_100)
        assertTrue(gate.armed(2_000))
        gate.deviceRemoved(PEN, 2_100)
        assertTrue(gate.armed(2_499))
        assertFalse(gate.armed(2_500))
    }

    @Test fun aDisabledGateFollowsThePenWithoutArming() {
        gate.enabled = false
        gate.onPen(1_000, HOVER, PEN)
        assertFalse(gate.armed(1_100))
        assertTrue(cancels.isEmpty())
        gate.enabled = true
        assertTrue(gate.armed(1_200))
    }

    private companion object {
        const val PEN = 5
    }
}
