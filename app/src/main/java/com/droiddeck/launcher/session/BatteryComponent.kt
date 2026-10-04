package com.droiddeck.launcher.session

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.droiddeck.launcher.core.SessionPart
import java.io.File
import kotlin.math.abs

/**
 * The battery the Steam client can see.
 *
 * The client reads `/sys/class/power_supply/BAT<n>/...` - a laptop's or a Deck's naming and files
 * (`energy_now`, `power_now`, `time_to_empty_now`, a `uevent` of `POWER_SUPPLY_*` lines). Android
 * names its supply `battery` and lays it out differently, and on many phones an app may not read
 * it at all, so the client showed no battery. This writes a `BAT0` (and a `BAT1`, the Deck's name)
 * from Android's own battery API - the same on every device - into a directory the session binds
 * over `/sys/class/power_supply`, refreshed every few seconds while the session runs.
 *
 * Steam's Quick Access Menu takes its time estimates from neither: it reads the files in `/run/vpower` (as on
 * SteamOS), and with those absent showed "?h ?m" for projected battery life and for time to full.
 * The same estimates are written there too, into [vpowerDir] in the guest's own `/run`.
 *
 * Values: charge in µAh from the charge counter; energy in µWh from charge × voltage; power in µW
 * from |current| × voltage; time to empty from charge ÷ |average current| while discharging. Where
 * a device reports no current, the client still gets status and percentage.
 */
class BatteryComponent(val dir: File, private val vpowerDir: File) : SessionPart() {
    @Volatile private var running = false
    private var thread: Thread? = null

    override fun start() {
        running = true
        write()
        thread = Thread({
            while (running) {
                try { Thread.sleep(PERIOD_MS) } catch (e: InterruptedException) { break }
                if (running) write()
            }
        }, "battery-sysfs").apply { isDaemon = true; start() }
        Log.i(TAG, "battery: BAT0/BAT1 written to ${dir.path} every ${PERIOD_MS / 1000} s")
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        // Stale estimates would outlive the session in the rootfs; Steam falls back to "?h ?m".
        for (name in VPOWER_FILES) File(vpowerDir, name).delete()
    }

    private fun write() {
        val ctx = app() ?: return
        try {
            val bm = ctx.getSystemService(BatteryManager::class.java)
            val sticky: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN) ?: BatteryManager.BATTERY_STATUS_UNKNOWN
            val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.takeIf { it > 0 } ?: 100
            val pct = if (level >= 0) level * 100 / scale else (bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 50)
            val voltageUv = ((sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0).toLong() * 1000L).takeIf { it > 0 } ?: 3_800_000L
            val tempDeci = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            val tech = sticky?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.takeIf { it.isNotBlank() } ?: "Li-ion"
            val chargeNowUah = bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.takeIf { it > 0 && it != Long.MIN_VALUE } ?: 0L
            val currentNowUa = bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.takeIf { it != Long.MIN_VALUE } ?: 0L
            val currentAvgUa = bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)?.takeIf { it != Long.MIN_VALUE && it != 0L } ?: currentNowUa
            // Android's sign convention varies by vendor; the status says which way the current flows.
            val discharging = status == BatteryManager.BATTERY_STATUS_DISCHARGING || status == BatteryManager.BATTERY_STATUS_NOT_CHARGING
            val chargeFullUah = if (chargeNowUah > 0 && pct > 0) chargeNowUah * 100 / pct else 0L
            val energyNowUwh = chargeNowUah * voltageUv / 1_000_000L
            val energyFullUwh = chargeFullUah * voltageUv / 1_000_000L
            val powerNowUw = abs(currentNowUa) * voltageUv / 1_000_000L
            val timeToEmptyS = if (discharging && chargeNowUah > 0 && abs(currentAvgUa) > 0) chargeNowUah * 3600L / abs(currentAvgUa) else 0L
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING
            val timeToFullS = if (charging && chargeFullUah > chargeNowUah && abs(currentAvgUa) > 0) (chargeFullUah - chargeNowUah) * 3600L / abs(currentAvgUa) else 0L
            val statusText = when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
                BatteryManager.BATTERY_STATUS_FULL -> "Full"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
                else -> "Unknown"
            }
            val capacityLevel = when {
                status == BatteryManager.BATTERY_STATUS_FULL || pct >= 100 -> "Full"
                pct <= 5 -> "Critical"
                pct <= 15 -> "Low"
                else -> "Normal"
            }
            for (name in listOf("BAT0", "BAT1")) {
                val bat = File(dir, name).apply { mkdirs() }
                val attrs = linkedMapOf(
                    "type" to "Battery",
                    "present" to "1",
                    "status" to statusText,
                    "capacity" to pct.toString(),
                    "capacity_level" to capacityLevel,
                    "technology" to tech,
                    "voltage_now" to voltageUv.toString(),
                    "voltage_min_design" to voltageUv.toString(),
                    "current_now" to currentNowUa.toString(),
                    "current_avg" to currentAvgUa.toString(),
                    "charge_now" to chargeNowUah.toString(),
                    "charge_full" to chargeFullUah.toString(),
                    "charge_full_design" to chargeFullUah.toString(),
                    "energy_now" to energyNowUwh.toString(),
                    "energy_full" to energyFullUwh.toString(),
                    "energy_full_design" to energyFullUwh.toString(),
                    "power_now" to powerNowUw.toString(),
                    "time_to_empty_now" to timeToEmptyS.toString(),
                    "time_to_empty_avg" to timeToEmptyS.toString(),
                    "time_to_full_now" to timeToFullS.toString(),
                    "time_to_full_avg" to timeToFullS.toString(),
                    "temp" to tempDeci.toString(),
                    "model_name" to android.os.Build.MODEL.replace(' ', '_'),
                    "manufacturer" to android.os.Build.MANUFACTURER.replace(' ', '_'),
                    "scope" to "System",
                )
                for ((k, v) in attrs) put(File(bat, k), v + "\n")
                val uevent = StringBuilder("POWER_SUPPLY_NAME=$name\n")
                for ((k, v) in attrs) uevent.append("POWER_SUPPLY_").append(k.uppercase()).append('=').append(v).append('\n')
                put(File(bat, "uevent"), uevent.toString())
            }
            val plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            vpowerDir.mkdirs()
            val vpower = linkedMapOf(
                "ac_status" to if (plugged) "Connected" else "Disconnected",
                "battery_percent" to "%.2f".format(java.util.Locale.ROOT, pct.toDouble()),
                "battery_status" to statusText,
                // Steam reads a separate file for each direction; -1 = no estimate.
                "secs_until_battery_full" to (if (timeToFullS > 0) timeToFullS else -1L).toString(),
                "secs_until_shutdown_request" to (if (timeToEmptyS > 0) timeToEmptyS else -1L).toString(),
            )
            for ((k, v) in vpower) put(File(vpowerDir, k), v + "\n")
        } catch (t: Throwable) {
            Log.w(TAG, "battery: could not write", t)
        }
    }

    /** Atomic per file: a reader in the guest never sees a half-written value. */
    private fun put(target: File, text: String) {
        val tmp = File(target.parentFile, "." + target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) { target.writeText(text); tmp.delete() }
    }

    companion object {
        private const val TAG = "SessionService"
        private const val PERIOD_MS = 5_000L
        private val VPOWER_FILES = listOf(
            "ac_status", "battery_percent", "battery_status",
            "secs_until_battery_full", "secs_until_shutdown_request",
        )
    }
}
