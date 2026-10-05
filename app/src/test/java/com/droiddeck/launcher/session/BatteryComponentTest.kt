package com.droiddeck.launcher.session

import android.content.Intent
import android.os.BatteryManager
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BatteryComponentTest {
    @get:Rule val tmp = TemporaryFolder()
    private var battery: BatteryComponent? = null

    @After fun stop() { battery?.stop() }

    @Test fun oneAggregateBatteryKeepsOverlayPowerAndLifetimeConsistentWithQam() {
        val supplies = tmp.newFolder("power_supply")
        val vpower = tmp.newFolder("vpower")
        start(supplies, vpower, -2_000_000L)
        assertTelemetry(supplies, vpower)
    }

    @Test fun upgradingRemovesTheDuplicateRatherThanLeavingItForOverlayDiscovery() {
        val supplies = tmp.newFolder("power_supply")
        val vpower = tmp.newFolder("vpower")
        val legacy = File(supplies, "BAT0").apply { mkdirs() }
        File(legacy, "power_now").writeText("8000000\n")
        File(legacy, "charge_now").writeText("4000000\n")
        start(supplies, vpower, 2_000_000L)
        assertFalse(legacy.exists())
        assertTelemetry(supplies, vpower)
    }

    private fun start(supplies: File, vpower: File, currentUa: Long) {
        val context = RuntimeEnvironment.getApplication()
        context.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).apply {
            putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING)
            putExtra(BatteryManager.EXTRA_LEVEL, 50)
            putExtra(BatteryManager.EXTRA_SCALE, 100)
            putExtra(BatteryManager.EXTRA_VOLTAGE, 4000)
        })
        shadowOf(context.getSystemService(BatteryManager::class.java)).apply {
            setLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER, 4_000_000L)
            setLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, currentUa)
            setLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE, currentUa)
        }
        battery = BatteryComponent(supplies, vpower).also { it.attach(context); it.start() }
    }

    private fun assertTelemetry(supplies: File, vpower: File) {
        val discovered = supplies.listFiles()!!.filter { it.name.contains("BAT") }
        assertEquals(listOf("BAT1"), discovered.map { it.name })
        fun read(name: String) = File(discovered.single(), name).readText().trim().toLong()
        // One 4 Ah battery at 4 V drawing 2 A: 8 W and two hours, regardless of vendor current sign.
        assertEquals(8_000_000L, read("power_now"))
        assertEquals(16_000_000L, read("energy_now"))
        assertEquals(7200L, read("time_to_empty_now"))
        assertEquals("7200", File(vpower, "secs_until_shutdown_request").readText().trim())
        assertEquals("50.00", File(vpower, "battery_percent").readText().trim())
    }
}
