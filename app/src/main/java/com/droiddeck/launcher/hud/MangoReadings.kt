package com.droiddeck.launcher.hud

import android.os.Environment
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.BatteryRuntimeEstimator
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

internal class MangoReadings(private val sessionDir: File, private val rootDir: File) {
    class Cpu(val percent: Int, val perCore: IntArray)
    class Memory(val usedBytes: Long, val totalBytes: Long, val swapUsedBytes: Long, val swapTotalBytes: Long)
    class Battery(val percent: Int?, val watts: Float, val charging: Boolean, val runtimeText: String?)

    private val batteryRuntimeEstimator = BatteryRuntimeEstimator()
    private var lastBatteryDischarging: Boolean? = null

    private val cpuTimes = HashMap<String, LongArray>()
    private val cpuPercents = HashMap<String, Int>()

    @JvmOverloads
    fun cpu(stat: File = statFile()): Cpu? {
        val lines = runCatching { stat.readLines() }.getOrNull() ?: return null
        var total: Int? = null
        val cores = ArrayList<Int>()
        for (line in lines) {
            if (!line.startsWith("cpu")) break
            val parts = line.split(WHITESPACE)
            if (parts.size < 11) continue
            val values = LongArray(10) { parts[it + 1].toLongOrNull() ?: 0L }
            val percent = cpuPercent(parts[0], values)
            if (parts[0] == "cpu") total = percent else cores.add(percent)
        }
        return total?.let { Cpu(it, cores.toIntArray()) }
    }

    private fun statFile(): File =
        File(sessionDir, "proc-stat").takeIf { !File("/proc/stat").canRead() && it.isFile } ?: File("/proc/stat")

    private fun cpuPercent(key: String, v: LongArray): Int {
        val user = v[0] - v[8]
        val nice = v[1] - v[9]
        val systemAll = v[2] + v[5] + v[6]
        val idleAll = v[3] + v[4]
        val virt = v[8] + v[9]
        val totalTime = user + nice + systemAll + idleAll + v[7] + virt
        val now = longArrayOf(user, nice, systemAll, v[7], virt, totalTime)
        val last = cpuTimes.put(key, now) ?: LongArray(6)
        fun period(i: Int) = if (now[i] > last[i]) now[i] - last[i] else 0L
        val totalPeriod = period(5).toFloat()
        if (totalPeriod == 0f) return cpuPercents[key] ?: 0
        val busy = period(1) * 100f / totalPeriod + period(0) * 100f / totalPeriod +
            period(2) * 100f / totalPeriod + (period(3) + period(4)) * 100f / totalPeriod
        return busy.coerceIn(0f, 100f).toInt().also { cpuPercents[key] = it }
    }

    fun cpuTempC(): Int? {
        externalCpuTemp()?.let { return it }
        var sum = 0f
        var count = 0
        for (zone in LinuxRuntime.cpuTempZones()) {
            val milli = readLong(File(zone, "temp")) ?: continue
            sum += milli / 1000f
            count++
        }
        return if (count == 0) null else (sum / count).toInt()
    }

    /** Optional ADB helper feed: /sdcard/DroidDeck/cpu-temp, written from the host while a session runs. */
    private fun externalCpuTemp(): Int? {
        val file = File(Environment.getExternalStorageDirectory(), "DroidDeck/cpu-temp")
        if (!file.isFile || System.currentTimeMillis() - file.lastModified() > CPU_TEMP_FRESH_MS) return null
        return readFloat(file)?.takeIf { it.isFinite() && it in -20f..150f }?.roundToInt()
    }

    fun gpuPercent(): Int? {
        val feed = fresh(File(sessionDir, "sys/kgsl-3d0/perf_now"))
        val source = feed ?: LinuxRuntime.gpuLoadSource()?.let(::File) ?: return null
        return leadingNumber(source)?.toInt()?.coerceIn(0, 100)
    }

    fun gpuClockMhz(): Int? {
        val feed = fresh(File(sessionDir, "sys/kgsl-3d0/cur_freq"))
        val source = feed ?: listOf("$KGSL/devfreq/cur_freq", "$KGSL/gpuclk").map(::File).firstOrNull { it.canRead() } ?: return null
        val hz = leadingNumber(source) ?: return null
        return (hz / 1_000_000.0).toInt().takeIf { it > 0 }
    }

    fun gpuTempC(): Int? {
        val milli = LinuxRuntime.gpuTempSource()?.let { readLong(File(it)) } ?: return null
        return (milli / 1000.0).toInt()
    }

    fun memory(): Memory? {
        val kb = HashMap<String, Long>()
        runCatching {
            File("/proc/meminfo").forEachLine { line ->
                val colon = line.indexOf(':')
                if (colon > 0) line.substring(colon + 1).trim().split(WHITESPACE).firstOrNull()?.toLongOrNull()
                    ?.let { kb[line.substring(0, colon)] = it }
            }
        }
        val total = kb["MemTotal"] ?: return null
        val available = kb["MemAvailable"] ?: return null
        val swapTotal = kb["SwapTotal"] ?: 0L
        val swapFree = kb["SwapFree"] ?: 0L
        return Memory((total - available) * 1024, total * 1024, (swapTotal - swapFree) * 1024, swapTotal * 1024)
    }

    /** The KGSL total DroidDeck shares with the session (GpuMemComponent). */
    fun vramBytes(): Long? = fresh(File(rootDir, VRAM_FILE), VRAM_FRESH_MS)?.let(::readLong)?.takeIf { it > 0 }

    fun battery(): Battery? {
        val dirs = File(sessionDir, "sys/power_supply").listFiles { f -> f.name.contains("BAT") }?.sortedBy { it.name }?.take(2)
        if (dirs.isNullOrEmpty()) return null

        var weightedChargeNow = 0.0
        var weightedChargeFull = 0.0
        var allHaveWeightedCharge = true
        val perBatteryPercents = ArrayList<Int>()
        var runtimeChargeUah = 0.0
        var runtimeChargeComplete = true
        var aggregateCurrentUa = 0.0
        var currentComplete = true
        var watts = 0.0
        var anyDischarging = false
        var anyChargingStatus = false
        var allFull = true
        var suppliedRuntimeSeconds: Long? = null

        for (dir in dirs) {
            val status = runCatching { File(dir, "status").readLines().firstOrNull() }.getOrNull()
            val isDischarging = status == "Discharging" || status == "Not charging"
            if (isDischarging) anyDischarging = true
            if (status == "Charging" || status == "Unknown") anyChargingStatus = true
            if (status != "Full") allFull = false

            val chargeNowFile = File(dir, "charge_now")
            val chargeFullFile = File(dir, "charge_full")
            val energyNowFile = File(dir, "energy_now")
            val energyFullFile = File(dir, "energy_full")
            val voltageFile = File(dir, "voltage_now")
            val chargeNow = if (chargeNowFile.isFile) readFloat(chargeNowFile)?.toDouble() else null
            val chargeFull = if (chargeFullFile.isFile) readFloat(chargeFullFile)?.toDouble() else null
            val energyNow = if (energyNowFile.isFile) readFloat(energyNowFile)?.toDouble() else null
            val energyFull = if (energyFullFile.isFile) readFloat(energyFullFile)?.toDouble() else null
            val voltageUv = if (voltageFile.isFile) readFloat(voltageFile)?.toDouble() else null

            val nowForPercent: Double? = when {
                chargeNow != null && chargeNow >= 0.0 -> chargeNow
                energyNow != null && voltageUv != null -> BatteryRuntimeEstimator.chargeMicroampHoursFromEnergy(energyNow, voltageUv)
                else -> null
            }
            val fullForPercent: Double? = when {
                chargeFull != null && chargeFull > 0.0 -> chargeFull
                energyFull != null && voltageUv != null -> BatteryRuntimeEstimator.chargeMicroampHoursFromEnergy(energyFull, voltageUv)
                else -> null
            }
            if (nowForPercent != null && fullForPercent != null && fullForPercent > 0.0) {
                weightedChargeNow += nowForPercent
                weightedChargeFull += fullForPercent
                perBatteryPercents += (nowForPercent * 100.0 / fullForPercent).roundToInt().coerceIn(0, 100)
            } else {
                allHaveWeightedCharge = false
                readFloat(File(dir, "capacity"))?.roundToInt()?.coerceIn(0, 100)?.let { perBatteryPercents += it }
            }

            when {
                chargeNow != null && chargeNow >= 0.0 -> runtimeChargeUah += chargeNow
                energyNow != null && voltageUv != null ->
                    BatteryRuntimeEstimator.chargeMicroampHoursFromEnergy(energyNow, voltageUv)?.let { runtimeChargeUah += it }
                        ?: run { runtimeChargeComplete = false }
                else -> runtimeChargeComplete = false
            }

            if (dirs.size == 1) {
                suppliedRuntimeSeconds = readLong(File(dir, "time_to_empty_avg"))?.takeIf { it > 0L }
                    ?: readLong(File(dir, "time_to_empty_now"))?.takeIf { it > 0L }
                    ?: suppliedRuntimeSeconds
            }

            val currentFile = File(dir, "current_now")
            val powerFile = File(dir, "power_now")
            val currentNow = if (currentFile.isFile) readFloat(currentFile)?.toDouble() else null
            val currentForEta = when {
                currentNow != null -> abs(currentNow)
                powerFile.isFile && voltageUv != null -> readFloat(powerFile)?.toDouble()?.let {
                    BatteryRuntimeEstimator.currentMicroampsFromPower(it, voltageUv)
                }
                else -> null
            }
            if (currentForEta == null) currentComplete = false else aggregateCurrentUa += currentForEta

            if (isDischarging) {
                val power = if (powerFile.isFile) readFloat(powerFile)?.toDouble() else null
                if (power != null) watts += abs(power) / 1_000_000.0
                else if (currentNow != null && voltageUv != null) watts += abs(currentNow * voltageUv) / 1_000_000_000_000.0
            }
        }

        val percent = when {
            allHaveWeightedCharge && weightedChargeFull > 0.0 -> (weightedChargeNow * 100.0 / weightedChargeFull).roundToInt().coerceIn(0, 100)
            perBatteryPercents.isNotEmpty() -> perBatteryPercents.average().roundToInt().coerceIn(0, 100)
            else -> null
        }
        if (lastBatteryDischarging != null && lastBatteryDischarging != anyDischarging) {
            batteryRuntimeEstimator.reset()
        }
        lastBatteryDischarging = anyDischarging
        val estimatedHours = if (anyDischarging && runtimeChargeComplete && currentComplete)
            batteryRuntimeEstimator.estimateHours(runtimeChargeUah, aggregateCurrentUa) else null
        val runtimeHours = suppliedRuntimeSeconds?.let { it / 3600.0 } ?: estimatedHours
        val charging = !anyDischarging && anyChargingStatus
        val runtimeText = when {
            anyDischarging -> BatteryRuntimeEstimator.formatDuration(runtimeHours)
            charging -> "Charging"
            allFull -> "Full"
            else -> null
        }
        return Battery(percent, if (anyDischarging) watts.toFloat() else 0f, charging, runtimeText)
    }

    private fun fresh(file: File, maxAgeMs: Long = FEED_FRESH_MS): File? =
        file.takeIf { it.isFile && System.currentTimeMillis() - it.lastModified() < maxAgeMs }


    companion object {
        private const val KGSL = "/sys/class/kgsl/kgsl-3d0"
        const val VRAM_FILE = "run/droiddeck-hud/gpu-mem"
        private const val FEED_FRESH_MS = 10_000L
        private const val VRAM_FRESH_MS = 15_000L
        private const val CPU_TEMP_FRESH_MS = 10_000L
        private val WHITESPACE = Regex("\\s+")

        private fun readLong(file: File): Long? = runCatching { file.readText().trim().toLong() }.getOrNull()
        private fun readFloat(file: File): Float? = runCatching { file.readLines().first().trim().toFloat() }.getOrNull()

        fun leadingNumber(file: File): Double? = runCatching {
            Regex("^\\s*-?\\d+(\\.\\d+)?").find(file.readLines().first())?.value?.trim()?.toDouble()
        }.getOrNull()
    }
}
