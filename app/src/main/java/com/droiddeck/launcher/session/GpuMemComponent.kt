package com.droiddeck.launcher.session

import android.util.Log
import com.droiddeck.launcher.core.SessionPart
import java.io.File

class GpuMemComponent(private val file: File, private val root: File) : SessionPart() {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var source = ""

    fun prepare(): Boolean {
        val bytes = sample() ?: return false
        write(bytes)
        Log.i(TAG, "hud: gpu memory from $source (${bytes / (1024 * 1024)} MiB now)")
        return true
    }

    override fun start() {
        running = true
        thread = Thread({
            while (running) {
                try { Thread.sleep(PERIOD_MS) } catch (e: InterruptedException) { break }
                if (running && (OverlayShown.check(root) || com.droiddeck.launcher.hud.DroidDeckHudHost.showing ||
                        com.droiddeck.launcher.hud.BottomHudPresence.showing)) sample()?.let { write(it) }
            }
        }, "gpu-mem").apply { isDaemon = true; start() }
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        file.delete()
    }

    private fun sample(): Long? {
        readLong("$KGSL_ROOT/page_alloc")?.let { source = "$KGSL_ROOT/page_alloc"; return it }
        val pids = sessionPids()
        var perProcess = 0L
        var anyProcess = false
        for (pid in pids) {
            val mapped = readLong("$KGSL_ROOT/proc/$pid/gpumem_mapped") ?: continue
            perProcess += mapped + (readLong("$KGSL_ROOT/proc/$pid/gpumem_unmapped") ?: 0L)
            anyProcess = true
        }
        if (anyProcess) { source = "$KGSL_ROOT/proc/<pid>/gpumem_*"; return perProcess }
        var mappedKb = 0L
        var anyMap = false
        for (pid in pids) {
            val kb = kgslMappedKb(pid) ?: continue
            mappedKb += kb
            anyMap = true
        }
        if (anyMap) { source = "/proc/<pid>/maps (KGSL mappings)"; return mappedKb * 1024 }
        return null
    }

    private fun sessionPids(): List<String> =
        File("/proc").list()?.filter { it.isNotEmpty() && it.all(Char::isDigit) }.orEmpty()

    private fun kgslMappedKb(pid: String): Long? =
        runCatching { File("/proc/$pid/maps").useLines(MemoryReadout::gpuMappedKb) }.getOrNull()

    private fun write(bytes: Long) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText("$bytes\n")
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (e: Exception) {
        }
    }

    companion object {
        private const val TAG = "SessionService"
        private const val KGSL_ROOT = "/sys/class/kgsl/kgsl"
        private const val PERIOD_MS = 5_000L

        private fun readLong(path: String): Long? =
            runCatching { File(path).readText().trim().toLong() }.getOrNull()
    }
}
