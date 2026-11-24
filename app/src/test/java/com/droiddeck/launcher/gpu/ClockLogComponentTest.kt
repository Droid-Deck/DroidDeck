package com.droiddeck.launcher.gpu

import android.content.Intent
import android.os.BatteryManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ClockLogComponentTest {
    @get:Rule val tmp = TemporaryFolder()
    private var clocks: ClockLogComponent? = null

    @After fun stop() { clocks?.stop() }

    @Test fun everySampleCarriesTheBatteryTemperature() {
        val context = RuntimeEnvironment.getApplication()
        context.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_TEMPERATURE, 412))
        val log = tmp.newFile("clocks.log")
        clocks = ClockLogComponent(log).also { it.attach(context); it.start() }
        val deadline = System.currentTimeMillis() + 5_000
        while (!log.readText().contains("| battery") && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(log.readText(), log.readText().contains("| battery 41.2 C"))
        context.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_TEMPERATURE, 423))
        val updatedDeadline = System.currentTimeMillis() + 5_000
        while (!log.readText().contains("| battery 42.3 C") && System.currentTimeMillis() < updatedDeadline) Thread.sleep(20)
        assertTrue(log.readText(), log.readText().contains("| battery 42.3 C"))
    }

    @Test fun stoppedComponentCannotStartFromAnEarlierSnapshot() {
        val context = RuntimeEnvironment.getApplication()
        val log = tmp.newFile("stopped-clocks.log")
        clocks = ClockLogComponent(log).also { it.attach(context); it.stop(); it.start() }
        assertTrue(log.readText(), log.length() == 0L)
    }
    @Test fun gpuStepsAndCeilingAreParsedAndPrinted() {
        val table = tmp.newFile("gpu_available_frequencies").apply { writeText("443000000 350000000 285000000 160000000\n") }
        val ceiling = tmp.newFile("max_gpuclk").apply { writeText("285000000\n") }
        val floor = tmp.newFile("min_clock_mhz").apply { writeText("160\n") }
        val clock = tmp.newFile("gpu_clock").apply { writeText("230\n") }
        val steps = GpuLimits.first(listOf(tmp.root.path + "/missing", table.path))!!
        assertEquals(listOf(160L, 285L, 350L, 443L), steps.mhz)
        assertEquals(
            listOf(
                "gpu steps: 160 285 350 443 MHz from ${table.path}",
                "gpu ceiling from: ${ceiling.path}",
                "gpu floor: 160 MHz from ${floor.path}",
            ),
            ClockLogComponent.limitLines(steps, ceiling.path, GpuLimits.first(listOf(floor.path))),
        )
        assertEquals(" | gpu 230/285(of 443) MHz", ClockLogComponent.gpuField(clock.path, ceiling.path, steps.mhz.last()))
        ceiling.writeText("443000000\n")
        assertEquals(" | gpu 230/443 MHz", ClockLogComponent.gpuField(clock.path, ceiling.path, steps.mhz.last()))
    }

    @Test fun aZeroCeilingIsNotALimit() {
        val clock = tmp.newFile("gpu_clock").apply { writeText("0\n") }
        val zero = tmp.newFile("max_gpuclk").apply { writeText("0\n") }
        assertEquals(" | gpu 0 MHz", ClockLogComponent.gpuField(clock.path, zero.path, 443))
        assertEquals(listOf("gpu limit ${zero.path}: refused"), ClockLogComponent.probeLines("gpu limit", listOf(zero.path), ClockLogComponent::limit))
        assertEquals(listOf("gpu source ${clock.path}: readable"), ClockLogComponent.probeLines("gpu source", listOf(clock.path)))
        assertNull(ClockLogComponent.ceilingSource(listOf(zero.path)))
        zero.writeText("285000000\n")
        assertEquals(zero.path, ClockLogComponent.ceilingSource(listOf(zero.path)))
        assertEquals(" | gpu 0/285(of 443) MHz", ClockLogComponent.gpuField(clock.path, zero.path, 443))
    }

    @Test fun aStepTableWithALeadingZeroIsReadable() {
        val steps = tmp.newFile("freq_table_mhz").apply { writeText("0 160000000 285000000\n") }
        assertEquals(listOf(160L, 285L), GpuLimits.first(listOf(steps.path))?.mhz)
        assertEquals(listOf("gpu limit ${steps.path}: readable"), ClockLogComponent.probeLines("gpu limit", listOf(steps.path), ClockLogComponent::firstStep))
    }

    @Test fun unreadableOrMissingLimitsLeaveTheSampleUnchanged() {
        val clock = tmp.newFile("clock_mhz").apply { writeText("230\n") }
        val refused = tmp.newFolder("max_clock_mhz")
        val missing = tmp.root.path + "/gpu_max_clock"
        assertEquals(" | gpu 230 MHz", ClockLogComponent.gpuField(clock.path, null, null))
        assertEquals(" | gpu 230 MHz", ClockLogComponent.gpuField(clock.path, missing, 443))
        assertEquals(" | gpu 230 MHz", ClockLogComponent.gpuField(clock.path, refused.path, 443))
        assertEquals("", ClockLogComponent.gpuField(null, null, null))
        assertNull(GpuLimits.first(listOf(missing, refused.path)))
        assertEquals(listOf("gpu limit ${refused.path}: refused"), ClockLogComponent.probeLines("gpu limit", listOf(missing, refused.path)))
        assertEquals(
            listOf("gpu steps: nothing readable", "gpu ceiling from: nothing readable", "gpu floor: nothing readable"),
            ClockLogComponent.limitLines(null, null, null),
        )
    }

    @Test fun aCeilingChangeMidSessionShowsInTheNextSample() {
        val clock = tmp.newFile("cur_freq").apply { writeText("443000000\n") }
        val ceiling = tmp.newFile("max_freq").apply { writeText("443000000\n") }
        val first = ClockLogComponent.gpuField(clock.path, ceiling.path, 443)
        ceiling.writeText("285000000\n")
        clock.writeText("285000000\n")
        val second = ClockLogComponent.gpuField(clock.path, ceiling.path, 443)
        assertEquals(" | gpu 443/443 MHz", first)
        assertEquals(" | gpu 285/285(of 443) MHz", second)
    }
}
