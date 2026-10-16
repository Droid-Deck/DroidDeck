package com.droiddeck.launcher.session

import android.os.SystemClock
import android.system.Os
import android.util.Log
import com.droiddeck.launcher.core.SessionPart
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import java.io.RandomAccessFile

class HwmonComponent(val dir: File, private val root: File) : SessionPart() {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var fanSource: String? = null
    private val fanFile = File(dir, "hwmon1/fan1_input")
    private val valveFanFile = File(dir, "hwmon2/fan1_input")
    private var lastRpm = 0L
    private var lastRpmAt = 0L

    fun prepare(): Boolean = try {
        dir.mkdirs()
        clear(dir)
        val cpu = LinuxRuntime.cpuTempSource()
        if (cpu != null) sensor("hwmon0", "cpu0_thermal").also { link(cpu, File(it, "temp1_input")) }
        val gpu = LinuxRuntime.gpuTempSource()
        fanSource = FAN_SOURCES().firstOrNull { readNumber(it) != null }
        if (gpu != null || fanSource != null) {
            val sensor = sensor("hwmon1", "gpuss0_thermal")
            if (gpu != null) link(gpu, File(sensor, "temp1_input"))
            if (fanSource != null) {
                sensor("hwmon2", "slg4ax46073v")
                writeFan(sampleFan())
            }
        }
        Log.i(TAG, "hud: hwmon cpu temp ${cpu ?: "none"}, gpu temp ${gpu ?: "none"}, fan ${fanSource ?: "none"}")
        cpu != null || gpu != null || fanSource != null
    } catch (e: Exception) {
        Log.w(TAG, "hud: could not write the session's hwmon: $e")
        false
    }

    override fun start() {
        if (fanSource == null) return
        running = true
        thread = Thread({
            while (running) {
                try { Thread.sleep(FAN_PERIOD_MS) } catch (e: InterruptedException) { break }
                if (running && OverlayShown.check(root)) writeFan(sampleFan())
            }
        }, "hwmon-fan").apply { isDaemon = true; start() }
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun sampleFan(): Long {
        val now = SystemClock.elapsedRealtime()
        val rpm = fanSource?.let { readNumber(it) } ?: 0L
        if (rpm > 0) {
            lastRpm = rpm
            lastRpmAt = now
        } else if (now - lastRpmAt > FAN_HOLD_MS) {
            lastRpm = 0
        }
        return lastRpm
    }

    private fun writeFan(rpm: Long) {
        for ((file, value) in listOf(fanFile to rpm, valveFanFile to rpm * 2)) {
            try {
                RandomAccessFile(file, "rw").use { it.seek(0); it.write(String.format("%6d\n", value).toByteArray()) }
            } catch (e: Exception) {
            }
        }
    }

    private fun sensor(name: String, label: String): File =
        File(dir, name).apply { mkdirs(); File(this, "name").writeText("$label\n") }

    private fun link(target: String, link: File) {
        java.nio.file.Files.deleteIfExists(link.toPath())
        Os.symlink(target, link.path)
    }

    private fun clear(root: File) {
        root.listFiles()?.forEach { f ->
            if (!java.nio.file.Files.isSymbolicLink(f.toPath()) && f.isDirectory) clear(f)
            java.nio.file.Files.deleteIfExists(f.toPath())
        }
    }

    companion object {
        private const val TAG = "SessionService"
        private const val FAN_PERIOD_MS = 3_000L
        private const val FAN_HOLD_MS = 10_000L

        private val FAN_SOURCES = {
            val sources = mutableListOf("/sys/class/gpio5_pwm2/speed", "/sys/class/gpio5_pwm/speed")
            File("/sys/class/hwmon").listFiles()?.sortedBy { it.name }?.forEach { hwmon ->
                hwmon.listFiles { f -> f.name.matches(Regex("fan\\d+_input")) }?.sortedBy { it.name }
                    ?.forEach { sources.add(it.path) }
            }
            sources
        }

        private fun readNumber(path: String): Long? =
            runCatching { File(path).readText().trim().toLong() }.getOrNull()?.takeIf { it >= 0 }
    }
}
