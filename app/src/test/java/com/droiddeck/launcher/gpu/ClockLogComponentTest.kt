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
        val sample = log.readLines().last()
        assertTrue(sample, sample.endsWith("| battery 41.2 C"))
    }
}
