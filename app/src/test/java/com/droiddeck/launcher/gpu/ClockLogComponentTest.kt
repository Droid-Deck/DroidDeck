package com.droiddeck.launcher.gpu

import android.content.Intent
import android.os.BatteryManager
import org.junit.After
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
}
