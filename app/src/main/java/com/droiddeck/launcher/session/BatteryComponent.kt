package com.droiddeck.launcher.session

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.droiddeck.launcher.core.SessionPart
import java.io.File
import kotlin.math.abs

class BatteryComponent(val dir: File, private val vpowerDir: File) : SessionPart() {
    @Volatile private var running = false
    private var thread: Thread? = null

    override fun start() {
        File(dir, "BAT0").deleteRecursively()
        running = true
        write()
        thread = Thread({
            while (running) {
                try { Thread.sleep(PERIOD_MS) } catch (e: InterruptedException) { break }
                if (running) write()
            }
        }, "battery-sysfs").apply { isDaemon = true; start() }
        Log.i(TAG, "battery: BAT1 written to ${dir.path} every ${PERIOD_MS / 1000} s")
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread = null
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
            val currentNowUa = toMicroamps(bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.takeIf { it != Long.MIN_VALUE } ?: 0L)
            val currentAvgUa = toMicroamps(bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)?.takeIf { it != Long.MIN_VALUE && it != 0L } ?: 0L)
                .takeIf { it != 0L } ?: currentNowUa
            val discharging = status == BatteryManager.BATTERY_STATUS_DISCHARGING || status == BatteryManager.BATTERY_STATUS_NOT_CHARGING
            val chargeFullUah = if (chargeNowUah > 0 && pct > 0) chargeNowUah * 100 / pct else 0L
            val energyNowUwh = chargeNowUah * voltageUv / 1_000_000L
            val energyFullUwh = chargeFullUah * voltageUv / 1_000_000L
            val powerNowUw = abs(currentNowUa) * voltageUv / 1_000_000L
            val timeToEmptyS = if (discharging && chargeNowUah > 0 && abs(currentAvgUa) > 0) chargeNowUah * 3600L / abs(currentAvgUa) else 0L
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING
            val timeToFullS = if (charging && chargeFullUah > chargeNowUah && abs(currentAvgUa) > 0) (chargeFullUah - chargeNowUah) * 3600L / abs(currentAvgUa) else 0L
            // Android can keep reporting charging at 100%; Linux reports Full there, and Steam shows 99% until it does.
            val statusText = when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> if (pct >= 100) "Full" else "Charging"
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
            for (name in listOf("BAT1")) {
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
                "secs_until_battery_full" to (if (timeToFullS > 0) timeToFullS else -1L).toString(),
                "secs_until_shutdown_request" to (if (timeToEmptyS > 0) timeToEmptyS else -1L).toString(),
            )
            for ((k, v) in vpower) put(File(vpowerDir, k), v + "\n")
        } catch (t: Throwable) {
            Log.w(TAG, "battery: could not write", t)
        }
    }

    private fun put(target: File, text: String) {
        val tmp = File(target.parentFile, "." + target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) { target.writeText(text); tmp.delete() }
    }

    companion object {
        private const val TAG = "SessionService"
        // CURRENT_NOW/CURRENT_AVERAGE are documented as µA, but Samsung and other vendors report mA,
        // which made every lifetime estimate 1000x too long. No real µA reading under load is below 10 mA,
        // and no mA reading is above 10 A, so anything smaller than 10_000 is taken as mA.
        private const val MILLIAMP_CEILING = 10_000L

        internal fun toMicroamps(raw: Long): Long = if (abs(raw) < MILLIAMP_CEILING) raw * 1000L else raw
        private const val PERIOD_MS = 5_000L
        private val VPOWER_FILES = listOf(
            "ac_status", "battery_percent", "battery_status",
            "secs_until_battery_full", "secs_until_shutdown_request",
        )
    }
}
