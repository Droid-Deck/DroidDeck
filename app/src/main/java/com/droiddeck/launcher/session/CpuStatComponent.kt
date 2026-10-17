package com.droiddeck.launcher.session

import android.os.SystemClock
import android.util.Log
import com.droiddeck.launcher.core.SessionPart
import java.io.File

class CpuStatComponent(val file: File) : SessionPart() {
    @Volatile private var running = false
    private var thread: Thread? = null
    private val cores = (0 until coreCount()).toList()
    private val busy = LongArray(cores.size)
    private val idle = LongArray(cores.size)
    private val lastIdleUs = LongArray(cores.size)
    private var lastMs = 0L
    private val bootSeconds = (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 1000

    fun prepare(): Boolean {
        if (File("/proc/stat").canRead() || cores.isEmpty() || idleUs(0) == null) return false
        lastMs = SystemClock.elapsedRealtime()
        for (i in cores) lastIdleUs[i] = idleUs(i) ?: 0L
        write()
        Log.i(TAG, "hud: /proc/stat from the cores' idle times (${cores.size} cores)")
        return true
    }

    override fun start() {
        running = true
        thread = Thread({
            while (running) {
                try { Thread.sleep(PERIOD_MS) } catch (e: InterruptedException) { break }
                if (!running) break
                sample()
                write()
            }
        }, "cpu-stat").apply { isDaemon = true; start() }
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun sample() {
        val now = SystemClock.elapsedRealtime()
        val elapsed = (now - lastMs) / MS_PER_TICK
        if (elapsed <= 0) return
        lastMs += elapsed * MS_PER_TICK
        for (i in cores) {
            val us = idleUs(i)
            val idleTicks = if (us == null) elapsed else ((us - lastIdleUs[i]) / US_PER_TICK).coerceIn(0, elapsed)
            if (us != null) lastIdleUs[i] = us
            idle[i] += idleTicks
            busy[i] += elapsed - idleTicks
        }
    }

    private fun write() {
        val text = StringBuilder()
        text.append("cpu  ${busy.sum()} 0 0 ${idle.sum()} 0 0 0 0 0 0\n")
        for (i in cores) text.append("cpu$i ${busy[i]} 0 0 ${idle[i]} 0 0 0 0 0 0\n")
        text.append("intr 0\nctxt 0\nbtime $bootSeconds\nprocesses 1\nprocs_running 1\nprocs_blocked 0\n")
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text.toString())
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (e: Exception) {
        }
    }

    companion object {
        private const val TAG = "SessionService"
        private const val PERIOD_MS = 500L
        private const val MS_PER_TICK = 10L
        private const val US_PER_TICK = 10_000L

        private fun coreCount(): Int =
            File("/sys/devices/system/cpu").list()?.count { it.matches(Regex("cpu\\d+")) } ?: 0

        private fun idleUs(core: Int): Long? {
            val states = File("/sys/devices/system/cpu/cpu$core/cpuidle").listFiles { f -> f.name.startsWith("state") }
                ?: return null
            if (states.isEmpty()) return null
            var total = 0L
            for (state in states) {
                total += runCatching { File(state, "time").readText().trim().toLong() }.getOrNull() ?: return null
            }
            return total
        }
    }
}
