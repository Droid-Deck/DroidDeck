package com.droiddeck.launcher.hud

import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File

/**
 * The readings Steam's performance overlay shows in a DroidDeck session (Valve's mangohud 20260902.1,
 * tools/mangoapp), taken from the files the session hands it and computed the same way, so the two
 * overlays agree. Sources and formulas follow that build: cpu.cpp calculateCPUData, deckard_temp.cpp,
 * msm.cpp getQcomGpuInfo, memory.cpp update_meminfo and battery.cpp getPercent / getPower.
 */
internal class MangoReadings(private val sessionDir: File, private val rootDir: File) {
    class Cpu(val percent: Int, val perCore: IntArray)
    class Memory(val usedBytes: Long, val totalBytes: Long, val swapUsedBytes: Long, val swapTotalBytes: Long)
    class Battery(val percent: Int?, val watts: Float, val charging: Boolean)

    private val cpuTimes = HashMap<String, LongArray>()
    private val cpuPercents = HashMap<String, Int>()

    /** Total and per-core load from the session's /proc/stat (DroidDeck's stand-in when the app can't read it). */
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
        // user nice system idle iowait irq softirq steal guest guest_nice; guest time is already in user.
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

    /** Average of the zones the session types cpuN-thermal, truncated. */
    fun cpuTempC(): Int? {
        var sum = 0f
        var count = 0
        for (zone in LinuxRuntime.cpuTempZones()) {
            val milli = readLong(File(zone, "temp")) ?: continue
            sum += milli / 1000f
            count++
        }
        return if (count == 0) null else (sum / count).toInt()
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

    /** MemTotal - MemAvailable and SwapTotal - SwapFree from /proc/meminfo. */
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
    fun vramBytes(): Long? = fresh(File(rootDir, VRAM_FILE), VRAM_FRESH_MS)?.let(::readLong)

    /** BAT* under the session's power_supply (BatteryComponent): no watts while charging, full or unknown. */
    fun battery(): Battery? {
        val dirs = File(sessionDir, "sys/power_supply").listFiles { f -> f.name.contains("BAT") }?.sortedBy { it.name }
        if (dirs.isNullOrEmpty()) return null
        var chargeNow = 0f
        var chargeFull = 0f
        var watts = 0f
        var charging = false
        for (dir in dirs) {
            val status = runCatching { File(dir, "status").readLines().firstOrNull() }.getOrNull()
            if (status == "Charging" || status == "Unknown" || status == "Full") charging = true
            val now = File(dir, "charge_now").takeIf { it.exists() } ?: File(dir, "energy_now")
            val full = File(dir, "charge_full").takeIf { it.exists() } ?: File(dir, "energy_full")
            if (now.exists()) {
                chargeNow += (readFloat(now) ?: 0f) / 1_000_000f
                chargeFull += (readFloat(full) ?: 0f) / 1_000_000f
            } else {
                chargeNow += (readFloat(File(dir, "capacity")) ?: 0f) / 100f
                chargeFull = dirs.size.toFloat()
            }
            if (!charging) {
                val power = File(dir, "power_now")
                watts += if (power.exists()) kotlin.math.abs(readFloat(power) ?: 0f) / 1_000_000f
                else kotlin.math.abs((readFloat(File(dir, "current_now")) ?: 0f) * (readFloat(File(dir, "voltage_now")) ?: 0f)) / 1e12f
            }
        }
        val percent = if (chargeFull > 0f) Math.round(chargeNow / chargeFull * 100f) else null
        return Battery(percent, if (charging) 0f else watts, charging)
    }

    private fun fresh(file: File, maxAgeMs: Long = FEED_FRESH_MS): File? =
        file.takeIf { it.isFile && System.currentTimeMillis() - it.lastModified() < maxAgeMs }


    companion object {
        private const val KGSL = "/sys/class/kgsl/kgsl-3d0"
        const val VRAM_FILE = "run/droiddeck-hud/gpu-mem"
        private const val FEED_FRESH_MS = 10_000L
        private const val VRAM_FRESH_MS = 15_000L
        private val WHITESPACE = Regex("\\s+")

        private fun readLong(file: File): Long? = runCatching { file.readText().trim().toLong() }.getOrNull()
        private fun readFloat(file: File): Float? = runCatching { file.readLines().first().trim().toFloat() }.getOrNull()

        /** std::stod on the first line: the leading number, "6 %" -> 6. */
        fun leadingNumber(file: File): Double? = runCatching {
            Regex("^\\s*-?\\d+(\\.\\d+)?").find(file.readLines().first())?.value?.trim()?.toDouble()
        }.getOrNull()
    }
}
