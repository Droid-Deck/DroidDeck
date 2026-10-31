package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoostReportTest {
    private fun side(fps: Int, seconds: Int = 200, milliamps: Int? = null, slow: Int = fps) =
        BoostReport.Side().apply { repeat(seconds) { add(if (it < seconds / 10) slow else fps, milliamps, 0) } }

    @Test fun `the CPU a thread ran on is field 39 of its stat, even with spaces in its name`() {
        val fields = (3..52).map { if (it == 39) "7" else "0" }
        assertEquals(7, BoostReport.processorOf("1234 (Main Thread) " + fields.joinToString(" ")))
        assertNull(BoostReport.processorOf("1234 (short) S 1"))
    }

    @Test fun `cores are sorted into fastest, middle and slowest by top speed`() {
        val tiers = BoostReport.tiers(mapOf(0 to 2.0, 1 to 2.0, 2 to 3.2, 3 to 3.2, 4 to 4.3))
        assertEquals(mapOf(0 to 0, 1 to 0, 2 to 1, 3 to 1, 4 to 2), tiers)
        assertEquals(mapOf(0 to 2, 1 to 2), BoostReport.tiers(mapOf(0 to 2.0, 1 to 2.0)))
    }

    @Test fun `the slowest 5 percent is the frame rate of the slowest seconds`() {
        val s = BoostReport.Side().apply { (1..100).forEach { add(it, null, 0) } }
        assertEquals(6, s.low)
        assertEquals(50.5, s.average, 1e-9)
    }

    @Test fun `a clear gain is worth keeping`() {
        val v = BoostReport.verdict(side(60, milliamps = 1200), side(50, milliamps = 1000))
        assertTrue(v, v.startsWith("worth keeping: +20 % fps on average"))
        assertTrue(v, v.endsWith("for +20 % battery draw"))
    }

    @Test fun `better slowest moments alone are worth keeping`() {
        val v = BoostReport.verdict(side(50, slow = 40), side(50, slow = 30))
        assertTrue(v, v.startsWith("worth keeping: +2 % fps on average, +33 % in the slowest moments"))
    }

    @Test fun `no gain is not worth keeping`() {
        assertTrue(BoostReport.verdict(side(50), side(50)).startsWith("not worth keeping"))
        assertTrue(BoostReport.verdict(side(45), side(50)).startsWith("not worth keeping"))
    }

    @Test fun `a small gain is unclear`() {
        assertTrue(BoostReport.verdict(side(52), side(50)).startsWith("unclear, a small gain"))
    }

    @Test fun `a short check gives no verdict`() {
        val v = BoostReport.verdict(side(60, seconds = 100), side(50))
        assertEquals("too short to tell: play at least ${BoostReport.minutesForVerdict()} minutes with Check the boost on", v)
    }

    @Test fun `the switch's hint asks for as many minutes as the verdict needs`() {
        assertEquals(7, BoostReport.minutesForVerdict())
    }

    @Test fun `the check starts after the first minute of play, boost on first`() {
        (1..60).forEach { assertNull(BoostReport.checkWindow(it)) }
        assertEquals(false to 0, BoostReport.checkWindow(61))
        assertEquals(false to 59, BoostReport.checkWindow(120))
        assertEquals(true to 0, BoostReport.checkWindow(121))
        assertEquals(true to 59, BoostReport.checkWindow(180))
        assertEquals(false to 0, BoostReport.checkWindow(181))
    }

    @Test fun `the minutes asked for give both sides enough counted seconds`() {
        val seconds = IntArray(2)
        for (s in 1..BoostReport.minutesForVerdict() * 60) {
            val (pause, at) = BoostReport.checkWindow(s) ?: continue
            if (at >= BoostReport.SETTLE_S) seconds[if (pause) 1 else 0]++
        }
        assertTrue(seconds.joinToString(), seconds.all { it >= BoostReport.CHECK_MIN_S })
    }
}
