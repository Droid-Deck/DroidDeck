package com.droiddeck.launcher.session

import android.os.SystemClock
import android.util.Log
import com.droiddeck.launcher.core.SessionPart
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import java.io.RandomAccessFile

class GpuStatsComponent(val dir: File, private val root: File) : SessionPart() {
    @Volatile private var running = false
    private var thread: Thread? = null
    private val feeds = ArrayList<Feed>()
    private var layerLoad: LayerLoad? = null
    private var cycleClock: KgslClock.Sampler? = null
    @Volatile private var shown = false

    private class Feed(
        val file: File, val source: String, val read: () -> Long?, val format: String, val percent: Boolean = false,
    )

    fun binds(): List<String> {
        val list = mutableListOf(dir.path + ":" + KGSL)
        if (File(dir, "perf_now").exists()) list.add(File(dir, "perf_now").path + ":" + MSM_LOAD)
        if (File(dir, "cur_freq").exists()) list.add(File(dir, "cur_freq").path + ":" + MSM_CLOCK)
        return list
    }

    fun prepare(): Boolean {
        sources = ""
        if (!File(KGSL).exists()) return false
        val unreadable = MANGOHUD_FILES.any { File(KGSL, it).let { f -> f.exists() && !f.canRead() } }
        // Where SELinux hides the kgsl nodes completely (exists() is false too), the layer and cycle-counter feeds stand in.
        if (!unreadable && !File(GPUBUSY).canRead() && !loadBlocked()) return false
        return try {
            dir.mkdirs()
            dir.listFiles()?.forEach { java.nio.file.Files.deleteIfExists(it.toPath()) }
            feeds.clear()
            PASSTHROUGH_FILES.forEach { name ->
                runCatching { File(dir, name).writeText(File(KGSL, name).readText()) }
            }
            (loadFeed() ?: layerLoadFeed())?.let { load ->
                feeds.add(load)
                feeds.add(Feed(File(dir, "perf_now"), load.source, load.read, "%-6s\n", percent = true))
            }
            (pick("clock_mhz", clockSources(), "%5d\n") { mhz(it) } ?: cycleClockFeed())?.let { clock ->
                feeds.add(clock)
                feeds.add(Feed(File(dir, "cur_freq"), clock.source, { clock.read()?.times(1_000_000) }, "%-10d\n"))
            }
            pick("temp", tempSources(), "%6d\n") { milliCelsius(it) }?.let { feeds.add(it) }
            refresh()
            sources = MANGOHUD_FILES.joinToString { name ->
                "$name " + (feeds.firstOrNull { it.file.name == name }?.source ?: "none")
            }
            Log.i(TAG, "hud: kgsl stats from the session's own: $sources")
            true
        } catch (e: Exception) {
            Log.w(TAG, "hud: could not stand in for kgsl stats: $e")
            false
        }
    }

    override fun start() {
        if (feeds.isEmpty()) return
        running = true
        thread = Thread({
            while (running) {
                try { Thread.sleep(PERIOD_MS) } catch (e: InterruptedException) { break }
                if (!running) break
                refresh()
            }
        }, "gpu-stats").apply { isDaemon = true; start() }
    }

    // kgsl's gpubusy can read 0 0 straight after a read, so feeds that share a reader share one read.
    private fun refresh() {
        if (layerLoad != null || cycleClock != null) {
            shown = hudShown()
            layerLoad?.wanted(shown)
        }
        val values = HashMap<() -> Long?, Long?>()
        for (feed in feeds) {
            if (feed.read !in values) values[feed.read] = feed.read()
            values[feed.read]?.let { write(feed, it) }
        }
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        layerLoad?.clear()
        cycleClock?.close()
        cycleClock = null
        sources = ""
    }

    private fun hudShown(): Boolean = OverlayShown.check(root) || com.droiddeck.launcher.hud.DroidDeckHudHost.showing

    // Android hides the load (kgsl sysfs blocked): the in-game Vulkan layer times the GPU work instead.
    private fun layerLoadFeed(): Feed? {
        if (!loadBlocked()) return null
        val load = LayerLoad(guestLoadDir(root)).also { it.clear(); layerLoad = it }
        val read = { if (shown) load.percent() else null }
        return Feed(File(dir, "gpu_busy_percentage"), "in-game Vulkan timing ($GUEST_LOAD_DIR)", read, "%3d %%\n")
    }

    // The clock nodes are blocked too: read the GPU's cycle counter from a small GPU job of our own.
    private fun cycleClockFeed(): Feed? {
        val sampler = KgslClock.open() ?: return null
        cycleClock = sampler
        // clock_mhz and cur_freq both read this; one GPU job per refresh serves both.
        var at = 0L
        var last: Long? = null
        val read = {
            val now = SystemClock.elapsedRealtime()
            if (now - at > PERIOD_MS / 2) { at = now; last = if (shown) sampler.mhz()?.toLong() else null }
            last
        }
        return Feed(File(dir, "clock_mhz"), "GPU cycle counter (own GPU job)", read, "%5d\n")
    }

    /** Busy time the layer (tools/gpuload) writes per process, as a share of wall time between two reads. */
    internal class LayerLoad(private val dir: File, private val clock: () -> Long = System::nanoTime) {
        private val last = HashMap<String, Long>()
        private var lastAt = 0L

        fun wanted(on: Boolean) {
            val flag = File(dir, "on")
            if (on && !flag.exists()) runCatching { dir.mkdirs(); flag.createNewFile() }
            if (!on && flag.exists()) { flag.delete(); last.clear(); lastAt = 0L }
        }

        fun percent(): Long? {
            val now = clock()
            val files = dir.listFiles { f -> f.name.isNotEmpty() && f.name.all(Char::isDigit) } ?: return null
            var busy = 0L
            val seen = HashSet<String>()
            for (file in files) {
                val total = runCatching { file.readText().trim().substringBefore(' ').toLong() }.getOrNull() ?: continue
                seen += file.name
                last[file.name]?.let { if (total >= it) busy += total - it }
                last[file.name] = total
                if (System.currentTimeMillis() - file.lastModified() > STALE_MS) file.delete()
            }
            last.keys.retainAll(seen)
            val wall = now - lastAt
            val first = lastAt == 0L
            lastAt = now
            if (first || wall <= 0) return null
            return (busy * 100 / wall).coerceIn(0, 100)
        }

        fun clear() {
            dir.listFiles()?.forEach { it.delete() }
            last.clear()
            lastAt = 0L
        }
    }

    private fun loadFeed(): Feed? {
        latchedBusyFeed()?.let { return it }
        return pick("gpu_busy_percentage", LOAD_SOURCES, "%3d %%\n") { it.coerceIn(0, 100) }
    }

    // kgsl publishes busy/total once per ~1 s of GPU-on time and zeroes it on any read while the GPU is
    // power-collapsed, so direct pollers mostly see 0; latch each published window instead.
    private fun latchedBusyFeed(): Feed? {
        var load = 0L
        var windowAt = 0L
        val read = read@{
            val parts = runCatching {
                File(GPUBUSY).readText().trim().split(Regex("\\s+")).map { it.toLong() }
            }.getOrNull()
            if (parts == null || parts.size < 2) return@read null
            val now = SystemClock.elapsedRealtime()
            if (parts[1] > 0) {
                load = (parts[0] * 100 / parts[1]).coerceIn(0, 100)
                windowAt = now
            } else if (now - windowAt > LOAD_HOLD_MS) {
                load = 0
            }
            load
        }
        return if (read() != null) Feed(File(dir, "gpu_busy_percentage"), GPUBUSY, read, "%3d %%\n") else null
    }

    private fun pick(name: String, sources: List<String>, format: String, unit: (Long) -> Long?): Feed? {
        for (source in sources) {
            val read = { firstNumber(source)?.let(unit) }
            if (read() != null) return Feed(File(dir, name), source, read, format)
        }
        return null
    }

    private fun write(feed: Feed, value: Long) {
        try {
            val text = if (feed.percent) String.format(feed.format, "$value %") else String.format(feed.format, value)
            RandomAccessFile(feed.file, "rw").use { it.seek(0); it.write(text.toByteArray()) }
        } catch (e: Exception) {
        }
    }

    companion object {
        private const val TAG = "SessionService"
        const val KGSL = "/sys/class/kgsl/kgsl-3d0"
        const val MSM_LOAD = "/sys/kernel/debug/dri/0/perf_now"
        const val MSM_CLOCK = "/sys/devices/platform/soc@0/3d00000.gpu/devfreq/3d00000.gpu/cur_freq"
        private const val GPUBUSY = "$KGSL/gpubusy"
        private const val PERIOD_MS = 500L
        private const val LOAD_HOLD_MS = 5_000L
        private val MANGOHUD_FILES = listOf("gpu_busy_percentage", "clock_mhz", "temp")
        private val PASSTHROUGH_FILES = listOf("gpu_model", "gpu_chipid")
        const val GUEST_LOAD_DIR = "/run/droiddeck-hud/gpu-load"

        /** Where the running session's GPU feeds come from, for the HUD diagnostics export. */
        @Volatile var sources = ""
        private const val STALE_MS = 30_000L

        fun guestLoadDir(root: File) = File(root, GUEST_LOAD_DIR.removePrefix("/"))

        /** True when this app can see kgsl but none of its GPU load nodes (SELinux-blocked, e.g. the Retroid Pocket Duo). */
        fun loadBlocked(): Boolean = File(KGSL).exists() && !File(GPUBUSY).canRead() && LOAD_SOURCES.none { File(it).canRead() }

        /** Turns on the in-game layer (tools/gpuload) where the load is blocked; empty elsewhere. */
        fun guestEnv(root: File): List<String> {
            if (!loadBlocked()) return emptyList()
            guestLoadDir(root).mkdirs()
            return listOf("DROIDDECK_GPU_LOAD=1", "DROIDDECK_GPU_LOAD_DIR=$GUEST_LOAD_DIR")
        }
        private const val QCOM_GPU = "/sys/kernel/gpu"
        private val LOAD_SOURCES = listOf(
            "$KGSL/gpu_busy_percentage", "$KGSL/devfreq/gpu_load", "$KGSL/gpuload", "$QCOM_GPU/gpu_busy",
        )
        private val DEVFREQ_NODES = listOf(
            "kgsl-3d0", "3d00000.qcom,kgsl-3d0", "2c00000.qcom,kgsl-3d0", "5000000.qcom,kgsl-3d0", "5900000.qcom,kgsl-3d0",
        )

        private fun clockSources(): List<String> {
            val sources = mutableListOf(
                "$KGSL/clock_mhz", "$KGSL/gpu_clock", "$KGSL/gpuclk", "$KGSL/devfreq/cur_freq", "$QCOM_GPU/gpu_clock",
            )
            DEVFREQ_NODES.forEach { sources.add("/sys/class/devfreq/$it/cur_freq") }
            File("/sys/class/devfreq").list()?.sorted()?.forEach { node ->
                if (node.contains("kgsl") || node.contains("gpu")) sources.add("/sys/class/devfreq/$node/cur_freq")
            }
            return sources.distinct()
        }

        private fun tempSources(): List<String> =
            listOfNotNull("$KGSL/temp", "$KGSL/devfreq/temp", "$QCOM_GPU/temp", LinuxRuntime.gpuTempSource()).distinct()

        private fun firstNumber(path: String): Long? =
            runCatching { Regex("-?\\d+").find(File(path).readText())?.value?.toLong() }.getOrNull()

        private fun mhz(raw: Long): Long? = when {
            raw < 0 -> null
            raw > 10_000_000 -> raw / 1_000_000
            raw > 10_000 -> raw / 1_000
            else -> raw
        }

        private fun milliCelsius(raw: Long): Long? = when {
            raw <= 0 -> null
            raw < 1000 -> raw * 1000
            else -> raw
        }
    }
}
