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
    private var memstore: KgslBusy.Reader? = null
    private var memstoreLoad: MemstoreLoad? = null
    private var memstoreState: String? = null
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
        KgslBusy.status = "not tried"
        KgslClock.status = "not tried"
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
            (loadFeed() ?: blockedLoadFeed())?.let { load ->
                feeds.add(load)
                feeds.add(Feed(File(dir, "perf_now"), load.source, load.read, "%-6s\n", percent = true))
            }
            (pick("clock_mhz", clockSources(), "%5d\n") { mhz(it) } ?: cycleClockFeed())?.let { clock ->
                feeds.add(clock)
                feeds.add(Feed(File(dir, "cur_freq"), clock.source, { clock.read()?.times(1_000_000) }, "%-10d\n"))
            }
            pick("temp", tempSources(), "%6d\n") { milliCelsius(it) }?.let { feeds.add(it) }
            refresh()
            describeSources()
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

    private fun refresh() {
        if (layerLoad != null || cycleClock != null || memstore != null) {
            shown = hudShown()
            memstore?.run(shown)
            if (!shown) memstoreLoad?.pause()
            layerLoad?.wanted(shown && memstoreLoad?.working != true)
        }
        val values = HashMap<() -> Long?, Long?>()
        for (feed in feeds) {
            if (feed.read !in values) values[feed.read] = feed.read()
            values[feed.read]?.let { write(feed, it) }
        }
        memstoreLoad?.let { load ->
            val state = when {
                load.working -> "working"
                load.failed -> "failed"
                else -> null
            }
            if (state != memstoreState) {
                memstoreState = state
                if (load.failed) {
                    memstore?.close()
                    memstore = null
                    KgslBusy.status += ", but the stamps never moved"
                    Log.i(TAG, "hud: kgsl memstore timestamps never moved; GPU load from Vulkan timing")
                } else if (load.working) {
                    Log.i(TAG, "hud: GPU load from the kgsl memstore (all GPU work)")
                }
                describeSources()
            }
        }
    }

    private fun describeSources() {
        sources = MANGOHUD_FILES.joinToString { name ->
            val source = if (name == "gpu_busy_percentage" && memstoreLoad != null) memstoreSource()
                else feeds.firstOrNull { it.file.name == name }?.source
            "$name " + (source ?: "none")
        }
    }

    private fun memstoreSource(): String = when {
        memstoreLoad?.working == true -> "kgsl memstore ringbuffer timestamps (all GPU work)"
        memstoreLoad?.failed == true -> LAYER_SOURCE
        else -> "kgsl memstore once its timestamps move; until then $LAYER_SOURCE"
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        memstore?.close()
        memstore = null
        memstoreLoad = null
        memstoreState = null
        layerLoad?.clear()
        cycleClock?.close()
        cycleClock = null
        sources = ""
    }

    private fun hudShown(): Boolean = OverlayShown.check(root) || com.droiddeck.launcher.hud.DroidDeckHudHost.showing

    // Android hides the load (kgsl sysfs blocked): the kgsl memstore gives the whole GPU's busy share. The in-game
    // Vulkan layer and the compositor time their own GPU work until the memstore timestamps are seen moving, and
    // for the whole session where they never move.
    private fun blockedLoadFeed(): Feed? {
        if (!loadBlocked()) return null
        val load = LayerLoad(guestLoadDir(root), extra = ::compositorBusyNs, onWanted = ::compositorTiming)
            .also { it.clear(); layerLoad = it }
        val sampled = KgslBusy.open()?.let { reader ->
            memstore = reader
            MemstoreLoad(reader::counts).also { memstoreLoad = it }
        }
        val read = read@{
            if (!shown) return@read null
            val whole = sampled?.percent()
            if (sampled?.working == true) whole else load.percent()
        }
        return Feed(File(dir, "gpu_busy_percentage"), LAYER_SOURCE, read, "%3d %%\n")
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

    private fun compositorBusyNs(): Long? =
        runCatching { com.droiddeck.launcher.wayland.WaylandCompositor.nativeGpuBusyNs() }.getOrNull()?.takeIf { it >= 0 }

    private fun compositorTiming(on: Boolean) {
        runCatching { com.droiddeck.launcher.wayland.WaylandCompositor.nativeSetGpuTiming(on) }
    }

    /**
     * Busy time the layer (tools/gpuload) writes per process, plus [extra] (the compositor's own GPU time), as a share
     * of wall time between two reads.
     */
    internal class LayerLoad(
        private val dir: File,
        private val clock: () -> Long = System::nanoTime,
        private val extra: () -> Long? = { null },
        private val onWanted: (Boolean) -> Unit = {},
    ) {
        private val last = HashMap<String, Long>()
        private var lastAt = 0L
        private var on = false

        fun wanted(on: Boolean) {
            val flag = File(dir, "on")
            if (on && !flag.exists()) runCatching { dir.mkdirs(); flag.createNewFile() }
            if (!on && flag.exists()) { flag.delete(); last.clear(); lastAt = 0L }
            if (on != this.on) onWanted(on)
            this.on = on
        }

        fun percent(): Long? {
            val now = clock()
            val files = dir.listFiles { f -> f.name.isNotEmpty() && f.name.all(Char::isDigit) }.orEmpty()
            var busy = 0L
            val seen = HashSet<String>()
            for (file in files) {
                val total = runCatching { file.readText().trim().substringBefore(' ').toLong() }.getOrNull() ?: continue
                seen += file.name
                last[file.name]?.let { if (total >= it) busy += total - it }
                last[file.name] = total
                if (System.currentTimeMillis() - file.lastModified() > STALE_MS) file.delete()
            }
            extra()?.let { total ->
                seen += EXTRA
                last[EXTRA]?.let { if (total >= it) busy += total - it }
                last[EXTRA] = total
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
            if (on) onWanted(false)
            on = false
        }

        private companion object {
            const val EXTRA = "compositor"
        }
    }

    /**
     * Busy share over the last [WINDOW] reads from [KgslBusy] running totals (busy samples, samples, samples with
     * new ringbuffer work). Not [working] until new work has been seen; [failed] once [GIVE_UP_SAMPLES] samples
     * passed without any.
     */
    internal class MemstoreLoad(private val counts: () -> LongArray?) {
        var working = false
            private set
        var failed = false
            private set
        private val window = ArrayDeque<LongArray>()
        private var waited = 0L

        fun percent(): Long? {
            if (failed) return null
            val now = counts() ?: return null
            val previous = window.lastOrNull()
            window.addLast(now)
            while (window.size > WINDOW + 1) window.removeFirst()
            if (previous == null) return null
            val fresh = now[1] - previous[1]
            if (fresh <= 0) return null
            if (!working) {
                if (now[2] > previous[2]) {
                    working = true
                } else {
                    waited += fresh
                    if (waited >= GIVE_UP_SAMPLES) failed = true
                    return null
                }
            }
            val base = window.first()
            return ((now[0] - base[0]) * 100 / (now[1] - base[1])).coerceIn(0, 100)
        }

        /** The totals stop while sampling is off; the next reading starts a new window. */
        fun pause() {
            window.clear()
        }

        companion object {
            const val GIVE_UP_SAMPLES = 5_000L
            const val WINDOW = 2
        }
    }

    private fun loadFeed(): Feed? {
        latchedBusyFeed()?.let { return it }
        return pick("gpu_busy_percentage", LOAD_SOURCES, "%3d %%\n") { it.coerceIn(0, 100) }
    }

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
        private const val LAYER_SOURCE = "Vulkan timing: games ($GUEST_LOAD_DIR) + DroidDeck compositor"

        /** Where the running session's GPU feeds come from, for the HUD diagnostics export. */
        @Volatile var sources = ""

        /** Why the no-root GPU readers work or not, for the HUD diagnostics export; empty where they were not needed. */
        fun readerStatus(): String =
            if (KgslBusy.status == "not tried" && KgslClock.status == "not tried") ""
            else "kgsl memstore ${KgslBusy.status}; GPU cycle counter ${KgslClock.status}"

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
