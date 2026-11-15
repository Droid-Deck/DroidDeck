package com.droiddeck.launcher.hud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MangoReadingsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun readings() = MangoReadings(tmp.root, tmp.root)

    @Test fun cpuLoadFollowsMangoHudsFormulaAndTruncates() {
        val stat = File(tmp.root, "stat")
        val m = readings()
        // user nice system idle iowait irq softirq steal guest guest_nice
        stat.writeText("cpu  1000 100 500 8000 400 50 50 0 200 0\ncpu0 500 50 250 4000 200 25 25 0 100 0\ncpu1 500 50 250 4000 200 25 25 0 100 0\nintr 0\n")
        m.cpu(stat)
        stat.writeText("cpu  1500 100 700 8600 500 60 70 10 300 0\ncpu0 900 50 400 4100 250 30 35 10 200 0\ncpu1 600 50 300 4500 250 30 35 0 100 0\nintr 0\n")
        val cpu = m.cpu(stat)!!
        // busy = user-guest 400 + nice 0 + system+irq+softirq 230 + steal 10 + guest 100 = 740 of 1440 (idle+iowait 700)
        assertEquals((740 * 100f / 1440).toInt(), cpu.percent)
        assertEquals(51, cpu.percent)
        assertArrayEquals(intArrayOf(((300 + 165 + 10 + 100) * 100f / (300 + 165 + 10 + 100 + 150)).toInt(), ((100 + 65) * 100f / (100 + 65 + 550)).toInt()), cpu.perCore)
    }

    @Test fun anUnchangedCounterKeepsTheLastLoad() {
        val stat = File(tmp.root, "stat")
        val m = readings()
        stat.writeText("cpu  100 0 0 100 0 0 0 0 0 0\n")
        m.cpu(stat)
        stat.writeText("cpu  200 0 0 100 0 0 0 0 0 0\n")
        assertEquals(100, m.cpu(stat)!!.percent)
        assertEquals(100, m.cpu(stat)!!.percent)
    }

    private fun battery(status: String, chargeNow: Long, chargeFull: Long, powerUw: Long) {
        val dir = File(tmp.root, "sys/power_supply/BAT1").apply { mkdirs() }
        File(dir, "status").writeText("$status\n")
        File(dir, "charge_now").writeText("$chargeNow\n")
        File(dir, "charge_full").writeText("$chargeFull\n")
        File(dir, "power_now").writeText("$powerUw\n")
    }

    @Test fun batteryHidesWattsWhileChargingAsMangoHudDoes() {
        battery("Discharging", 3_622_942, 3_854_193, 8_760_000)
        val discharging = readings().battery()!!
        assertEquals(94, discharging.percent)
        assertEquals(8.76f, discharging.watts, 0.001f)
        assertFalse(discharging.charging)
        battery("Charging", 3_622_942, 3_854_193, 8_760_000)
        val charging = readings().battery()!!
        assertEquals(0f, charging.watts, 0f)
        assertTrue(charging.charging)
    }

    @Test fun noBatteryFolderMeansNoReading() {
        assertNull(readings().battery())
    }

    @Test fun gpuLoadIsTheLeadingNumber() {
        val f = File(tmp.root, "perf_now").apply { writeText("  6 %\n") }
        assertEquals(6.0, MangoReadings.leadingNumber(f)!!, 0.0)
    }
}
