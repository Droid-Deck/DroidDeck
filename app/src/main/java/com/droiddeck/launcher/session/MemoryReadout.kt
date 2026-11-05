package com.droiddeck.launcher.session

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.R
import java.io.File
import java.util.Locale

internal object MemoryReadout {
    data class Device(val totalKb: Long, val availableKb: Long, val swapTotalKb: Long, val swapFreeKb: Long) {
        val usedKb get() = (totalKb - availableKb).coerceAtLeast(0)
        val swapUsedKb get() = (swapTotalKb - swapFreeKb).coerceAtLeast(0)
    }

    data class Split(val gameKb: Long, val droiddeckKb: Long, val gpuKb: Long?)

    fun parseMeminfo(text: String): Device? {
        fun field(name: String) = Regex("""(?m)^$name:\s+(\d+) kB""").find(text)?.groupValues?.get(1)?.toLongOrNull()
        val total = field("MemTotal") ?: return null
        val available = field("MemAvailable") ?: return null
        return Device(total, available, field("SwapTotal") ?: 0, field("SwapFree") ?: 0)
    }

    fun residentKb(status: String): Long {
        fun field(name: String) = Regex("""(?m)^$name:\s+(\d+) kB""").find(status)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        return field("RssAnon") + field("RssShmem")
    }

    fun inWinePrefix(environ: ByteArray): Boolean {
        var start = 0
        val key = "WINEPREFIX=".toByteArray()
        for (i in 0..environ.size) {
            if (i == environ.size || environ[i] == 0.toByte()) {
                if (i - start >= key.size && (key.indices).all { environ[start + it] == key[it] }) return true
                start = i + 1
            }
        }
        return false
    }

    fun androidKb(device: Device, split: Split): Long =
        (device.usedKb - split.gameKb - split.droiddeckKb - (split.gpuKb ?: 0)).coerceAtLeast(0)

    fun gb(kb: Long): String = String.format(Locale.US, "%.1f", kb / (1024.0 * 1024.0))

    fun availableMb(): Long? = runCatching { parseMeminfo(File("/proc/meminfo").readText())?.availableKb?.div(1024) }.getOrNull()

    fun device(): Device? = runCatching { parseMeminfo(File("/proc/meminfo").readText()) }.getOrNull()

    fun split(root: Int = android.os.Process.myPid()): Split {
        val parents = HashMap<Int, Int>()
        File("/proc").listFiles()?.forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            parentOf(pid)?.let { parents[pid] = it }
        }
        val children = parents.entries.groupBy({ it.value }, { it.key })
        var game = 0L
        var droiddeck = 0L
        val queue = ArrayDeque<Int>().apply { add(root) }
        val seen = HashSet<Int>()
        while (queue.isNotEmpty()) {
            val pid = queue.removeFirst()
            if (!seen.add(pid)) continue
            val status = runCatching { File("/proc/$pid/status").readText() }.getOrNull()
            if (status != null) {
                val kb = residentKb(status)
                val windows = pid != root && runCatching { inWinePrefix(File("/proc/$pid/environ").readBytes()) }.getOrDefault(false)
                if (windows) game += kb else droiddeck += kb
            }
            children[pid]?.let(queue::addAll)
        }
        return Split(game, droiddeck, gpuKb())
    }

    private fun parentOf(pid: Int): Int? {
        val stat = runCatching { File("/proc/$pid/stat").readText() }.getOrNull() ?: return null
        val close = stat.lastIndexOf(')')
        if (close < 0 || close + 2 >= stat.length) return null
        return stat.substring(close + 2).split(' ').getOrNull(1)?.toIntOrNull()
    }

    private fun gpuKb(): Long? = runCatching {
        File("/sys/class/kgsl/kgsl/page_alloc").readText().trim().split(Regex("\\s+"))[0].toLong() / 1024
    }.getOrNull()
}

object MemoryLog {
    private const val TAG = "Memory"
    private const val PERIOD_MS = 10_000L
    @Volatile private var thread: Thread? = null

    internal fun line(device: MemoryReadout.Device, split: MemoryReadout.Split): String =
        "used ${device.usedKb / 1024}/${device.totalKb / 1024} MB, available ${device.availableKb / 1024} MB, " +
            "swap ${device.swapUsedKb / 1024}/${device.swapTotalKb / 1024} MB | game ${split.gameKb / 1024}, " +
            "droiddeck ${split.droiddeckKb / 1024}, android ${MemoryReadout.androidKb(device, split) / 1024}, " +
            "gpu ${split.gpuKb?.div(1024) ?: "?"} MB"

    @Synchronized
    fun start() {
        if (thread != null) return
        thread = Thread({
            while (!Thread.currentThread().isInterrupted) {
                runCatching { MemoryReadout.device()?.let { android.util.Log.i(TAG, line(it, MemoryReadout.split())) } }
                try { Thread.sleep(PERIOD_MS) } catch (e: InterruptedException) { break }
            }
        }, "memory-log").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        thread?.interrupt()
        thread = null
    }
}

class MemoryHud(context: Context) {
    var text by mutableStateOf("")
        private set

    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var generation = 0
    private var thread: Thread? = null

    fun refresh() {
        if (SessionPrefs.memoryHud(context) == SessionPrefs.MEMORY_HUD_OFF) stop() else start()
    }

    fun start() {
        if (running || SessionPrefs.memoryHud(context) == SessionPrefs.MEMORY_HUD_OFF) return
        running = true
        val token = ++generation
        thread = Thread({
            while (running && generation == token) {
                val line = runCatching { sample() }.getOrDefault("")
                handler.post { if (running && generation == token) text = line }
                try { Thread.sleep(PERIOD_MS) } catch (e: InterruptedException) { break }
            }
        }, "memory-hud").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        generation++
        thread?.interrupt()
        thread = null
        text = ""
    }

    private fun sample(): String {
        val device = MemoryReadout.device() ?: return ""
        val memory = context.getString(
            R.string.memory_hud_simple,
            MemoryReadout.gb(device.usedKb), MemoryReadout.gb(device.totalKb),
            MemoryReadout.gb(device.swapUsedKb), MemoryReadout.gb(device.swapTotalKb),
        )
        if (SessionPrefs.memoryHud(context) != SessionPrefs.MEMORY_HUD_ADVANCED) return memory
        val split = MemoryReadout.split()
        val parts = context.getString(
            R.string.memory_hud_split,
            MemoryReadout.gb(split.gameKb), MemoryReadout.gb(split.droiddeckKb), MemoryReadout.gb(MemoryReadout.androidKb(device, split)),
        )
        val gpu = split.gpuKb?.let { context.getString(R.string.memory_hud_gpu, MemoryReadout.gb(it)) }
        return listOfNotNull(memory, parts, gpu).joinToString("\n")
    }

    private companion object {
        const val PERIOD_MS = 2000L
    }
}
