package com.droiddeck.launcher.gpu

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.droiddeck.launcher.core.SessionPart
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ClockLogComponent(private val logFile: File) : SessionPart() {
    @Volatile private var running = false
    private var stopped = false
    private var thread: Thread? = null

    @Synchronized override fun start() {
        if (stopped || running) return
        running = true
        thread = Thread({ run() }, "clock-log").apply { isDaemon = true; start() }
    }

    @Synchronized override fun stop() {
        stopped = true
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun run() {
        val policies = File("/sys/devices/system/cpu/cpufreq").listFiles { f -> f.name.startsWith("policy") }
            ?.sortedBy { it.name.removePrefix("policy").toIntOrNull() ?: 0 } ?: emptyList()
        val gpuCandidates = gpuSources()
        val gpu = gpuCandidates.firstOrNull { !it.startsWith("/proc/") && firstNumber(it) != null }
        val power = app().getSystemService(Context.POWER_SERVICE) as? PowerManager
        val time = SimpleDateFormat("HH:mm:ss", Locale.US)
        try {
            FileWriter(logFile, true).use { w ->
                w.write("== clocks: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.HARDWARE}), every ${PERIOD_MS / 1000}s\n")
                w.write("cpu policies: ${policies.joinToString { p -> p.name + " " + (File(p, "related_cpus").readTextOrNull()?.trim() ?: "?") }}\n")
                for (source in gpuCandidates) {
                    val f = File(source)
                    val state = when {
                        !f.exists() -> continue
                        firstNumber(source) != null -> "readable"
                        else -> "refused"
                    }
                    w.write("gpu source $source: $state\n")
                }
                w.write("gpu clock from: ${gpu ?: "nothing readable"}\n")
                w.flush()
                while (running && logFile.length() < MAX_BYTES) {
                    val line = StringBuilder(time.format(Date()))
                    for (p in policies) {
                        val cur = firstNumber(File(p, "scaling_cur_freq").path)
                        val max = firstNumber(File(p, "scaling_max_freq").path)
                        val hw = firstNumber(File(p, "cpuinfo_max_freq").path)
                        line.append(" ${p.name.removePrefix("policy").let { "p$it" }} ${mhz(cur)}/${mhz(max)}")
                        if (hw != null && max != null && max < hw) line.append("(of ${mhz(hw)})")
                    }
                    gpu?.let { line.append(" | gpu ${mhz(firstNumber(it))} MHz") }
                    if (power != null && Build.VERSION.SDK_INT >= 29) {
                        line.append(" | thermal ${power.currentThermalStatus}")
                        if (Build.VERSION.SDK_INT >= 30) {
                            val h = power.getThermalHeadroom(10)
                            if (!h.isNaN()) line.append(String.format(Locale.US, " headroom %.2f", h))
                        }
                    }
                    batteryTenths()?.let { line.append(String.format(Locale.US, " | battery %.1f C", it / 10.0)) }
                    w.write(line.append('\n').toString())
                    w.flush()
                    try { Thread.sleep(PERIOD_MS) } catch (e: InterruptedException) { break }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "clock log stopped: $e")
        }
    }

    private fun batteryTenths(): Int? =
        app().registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }

    companion object {
        private const val TAG = "SessionService"
        private const val PERIOD_MS = 2000L
        private const val MAX_BYTES = 4L shl 20

        private fun gpuSources(): List<String> {
            val list = mutableListOf(
                "/sys/kernel/ged/hal/current_freqency", "/sys/kernel/ged/hal/current_frequency",
                "/proc/gpufreqv2/gpufreq_status", "/proc/gpufreq/gpufreq_var_dump",
                "/sys/kernel/gpu/gpu_clock", "/sys/class/misc/mali0/device/cur_freq",
                "/sys/class/misc/mali0/device/clock", "/sys/class/kgsl/kgsl-3d0/clock_mhz",
                "/sys/class/kgsl/kgsl-3d0/gpu_clock", "/sys/class/kgsl/kgsl-3d0/gpuclk",
                "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq", "/sys/kernel/gpu/gpu_clock",
                "/sys/devices/platform/soc@0/3d00000.gpu/devfreq/3d00000.gpu/cur_freq",
            )
            listOf("kgsl-3d0", "3d00000.qcom,kgsl-3d0", "2c00000.qcom,kgsl-3d0", "5000000.qcom,kgsl-3d0", "5900000.qcom,kgsl-3d0")
                .forEach { list.add("/sys/class/devfreq/$it/cur_freq") }
            File("/sys/class/devfreq").list()?.sorted()?.forEach { node ->
                if (node.contains("mali", true) || node.contains("gpu", true)) list.add("/sys/class/devfreq/$node/cur_freq")
            }
            return list.distinct()
        }

        private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

        private fun firstNumber(path: String): Long? =
            runCatching { Regex("\\d+").find(File(path).readText())?.value?.toLong() }.getOrNull()

        private fun mhz(raw: Long?): String = when {
            raw == null -> "?"
            raw > 10_000_000 -> (raw / 1_000_000).toString()
            raw > 10_000 -> (raw / 1_000).toString()
            else -> raw.toString()
        }
    }
}
