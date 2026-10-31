package com.droiddeck.launcher.session

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.droiddeck.launcher.core.CpuCores
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the Performance boost did in a session, in boost.log in the session folder and in the app log:
 * whether each part (GameThreadHints, SamsungPerf) came on and can be seen working, a status line
 * every [STATUS_S] seconds of play, and a summary with a verdict when the session ends. Play is time
 * the game presents frames.
 *
 * With "Check the boost" on, the boost is paused for every other [WINDOW_S] seconds of play, so the
 * frame rate with and without it is compared over the same stretch of game. The first [WARMUP_S]
 * seconds of play (loading, shader compiles) and the first [SETTLE_S] seconds after each switch are
 * not counted.
 */
object BoostReport {
    private const val TAG = "BoostReport"
    private const val STATUS_S = 30
    internal const val WARMUP_S = 60
    internal const val WINDOW_S = 60
    internal const val SETTLE_S = 5
    internal const val CHECK_MIN_S = 120 // counted seconds on each side before there is a verdict
    private const val CONFIRM_S = 2 // held this long before the clocks are compared
    private const val RAISED = 1.1
    private val THERMAL = listOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown")
    private val GPU_MIN_FILES = listOf(
        "/sys/class/kgsl/kgsl-3d0/devfreq/min_freq",
        "/sys/class/kgsl/kgsl-3d0/min_clock_mhz",
        "/sys/kernel/gpu/gpu_min_clock",
    )

    /** True while Check the boost has the boost paused; GameThreadHints and SamsungPerf let go meanwhile. */
    @Volatile var paused = false
        private set

    /** Seconds of play on one side of a comparison. */
    internal class Side {
        val fps = ArrayList<Int>()
        val milliamps = ArrayList<Int>()
        var hottest = 0
        val seconds get() = fps.size
        val average get() = if (fps.isEmpty()) 0.0 else fps.average()
        /** The frame rate of the slowest 5 % of seconds. */
        val low get() = if (fps.isEmpty()) 0 else fps.sorted()[fps.size / 20]
        val draw get() = if (milliamps.isEmpty()) null else milliamps.average()

        fun add(f: Int, ma: Int?, thermal: Int) {
            fps.add(f)
            ma?.let { milliamps.add(it) }
            hottest = maxOf(hottest, thermal)
        }
    }

    /** The CPU a thread last ran on: field 39 of /proc/<tid>/stat. */
    internal fun processorOf(stat: String): Int? =
        stat.substringAfterLast(')').trim().split(' ').getOrNull(36)?.toIntOrNull()

    /** Each core's class by top speed: 2 the fastest, 0 the slowest, 1 between. */
    internal fun tiers(maxGhz: Map<Int, Double>): Map<Int, Int> {
        val top = maxGhz.values.maxOrNull() ?: return emptyMap()
        val bottom = maxGhz.values.minOrNull() ?: top
        return maxGhz.mapValues { (_, ghz) -> if (ghz >= top) 2 else if (ghz <= bottom) 0 else 1 }
    }

    internal fun minutesForVerdict(): Int {
        val windows = (CHECK_MIN_S + WINDOW_S - SETTLE_S - 1) / (WINDOW_S - SETTLE_S)
        return (WARMUP_S + 2 * windows * WINDOW_S + 59) / 60
    }

    /** Where second [played] of play falls in the check: null in the warm-up, else whether the boost is paused and the second's place in its window. */
    internal fun checkWindow(played: Int): Pair<Boolean, Int>? {
        if (played <= WARMUP_S) return null
        val n = played - WARMUP_S - 1
        return (n / WINDOW_S % 2 == 1) to n % WINDOW_S
    }

    /** From Check the boost: whether the boost made the game faster where it matters. */
    internal fun verdict(on: Side, off: Side): String {
        if (on.seconds < CHECK_MIN_S || off.seconds < CHECK_MIN_S) {
            return "too short to tell: play at least ${minutesForVerdict()} minutes with Check the boost on"
        }
        if (off.average < 1) return "unclear: the game showed no frames while the boost was paused"
        val gain = on.average / off.average - 1
        val lowGain = if (off.low > 0) on.low.toDouble() / off.low - 1 else 0.0
        val onDraw = on.draw
        val offDraw = off.draw
        val cost = if (onDraw != null && offDraw != null && offDraw > 0) ", for ${percent(onDraw / offDraw - 1)} battery draw" else ""
        val numbers = "${percent(gain)} fps on average, ${percent(lowGain)} in the slowest moments$cost"
        return when {
            gain >= 0.05 || lowGain >= 0.10 -> "worth keeping: $numbers"
            gain <= 0.02 && lowGain <= 0.05 -> "not worth keeping for this game: $numbers"
            else -> "unclear, a small gain: $numbers"
        }
    }

    private fun percent(x: Double) = (if (x >= 0) "+" else "") + (x * 100).roundToInt() + " %"

    private class Run(val file: File, val check: Boolean) {
        var started = false
        var played = 0
        var lastFrames = -1L
        val boosted = Side()
        val unboosted = Side()
        val checkOn = Side()
        val checkOff = Side()
        val recent = ArrayList<Int>()
        var coresBoosted = 0
        val onTier = IntArray(3)
        val recentTier = IntArray(3)
        var samsungHeld = 0
        var heldFor = 0
        var cpuBefore = emptyMap<String, Long>()
        var gpuBefore: Long? = null
        var cpuRaised: Map<String, Pair<Long, Long>>? = null
        var gpuRaised = false
        var clocks: String? = null
        var fallen = false
        var drops = 0
        var batteryStart: Int? = null
        var batteryEnd: Int? = null
        var firstFrameMs = 0L
        var lastFrameMs = 0L
        var charging = false
        var hottest = 0
    }

    private val letGoCounts = LinkedHashMap<String, Int>()
    private var run: Run? = null
    private var worker: Thread? = null

    /** SamsungPerf let go of its floors, and why. */
    fun letGo(why: String) {
        synchronized(letGoCounts) { letGoCounts[why] = (letGoCounts[why] ?: 0) + 1 }
    }

    /** At a session's start, after the boost's parts. The line is for the log. */
    @Synchronized
    fun start(context: Context, dir: File): String {
        stop()
        synchronized(letGoCounts) { letGoCounts.clear() }
        if (!SessionPrefs.performanceBoost(context)) return "boost report: off"
        val r = Run(File(dir, "boost.log"), SessionPrefs.boostCheck(context))
        run = r
        val soc = if (Build.VERSION.SDK_INT >= 31) ", ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else ""
        line(r, "Performance boost report: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}$soc; " +
            "Check the boost ${if (r.check) "on" else "off"}. Play is time the game shows frames.")
        val app = context.applicationContext
        worker = Thread({ watch(app, r) }, "boost-report").apply { isDaemon = true; start() }
        return "boost report: ${r.file}"
    }

    /** At a session's end: the summary. */
    @Synchronized
    fun stop() {
        val r = run ?: return
        run = null
        worker?.let { it.interrupt(); it.join(2000) }
        worker = null
        paused = false
        runCatching { summary(r) }.onFailure { Log.w(TAG, "summary", it) }
    }

    private fun line(r: Run, text: String) {
        Log.i(TAG, text)
        val clock = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        runCatching { r.file.appendText("$clock $text\n") }
    }

    private fun watch(context: Context, r: Run) {
        val pm = context.getSystemService(PowerManager::class.java)
        val bm = context.getSystemService(BatteryManager::class.java)
        val tiers = tiers(CpuCores.all.associateWith { CpuCores.maxGhz(it) ?: 0.0 })
        val gpuFile = GPU_MIN_FILES.map(::File).firstOrNull { readLong(it) != null }
        try {
            while (!Thread.currentThread().isInterrupted) {
                Thread.sleep(1000)
                second(r, pm, bm, tiers, gpuFile)
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            Log.w(TAG, "stopped", t)
        } finally {
            paused = false
        }
    }

    private fun second(r: Run, pm: PowerManager, bm: BatteryManager?, tiers: Map<Int, Int>, gpuFile: File?) {
        val frames = WaylandCompositor.gameFrameCount()
        val fps = if (r.lastFrames < 0) 0 else (frames - r.lastFrames).toInt()
        r.lastFrames = frames
        watchClocks(r, gpuFile)
        if (fps < 1) return
        if (!r.started) {
            r.started = true
            r.batteryStart = battery(bm)
            r.firstFrameMs = System.currentTimeMillis()
            line(r, "the game shows frames; fastest cores: ${GameThreadHints.startLine.removePrefix("game thread hints: ")}; " +
                "Samsung clocks: ${SamsungPerf.state}")
            if (r.check) line(r, "check: starts after the first $WARMUP_S s of play, so loading is not counted")
        }
        r.played++
        val thermal = if (Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else 0
        r.hottest = maxOf(r.hottest, thermal)
        val ma = milliamps(bm)
        if (bm?.isCharging == true) r.charging = true
        r.batteryEnd = battery(bm)
        r.lastFrameMs = System.currentTimeMillis()

        val window = if (r.check) checkWindow(r.played) else null
        if (window != null) {
            val (pause, at) = window
            if (at == 0) {
                paused = pause
                line(r, if (pause) "check: boost paused for $WINDOW_S s" else "check: boost on for $WINDOW_S s")
            }
            if (at >= SETTLE_S) (if (pause) r.checkOff else r.checkOn).add(fps, ma, thermal)
        }

        val threads = GameThreadHints.boostedThreads
        val samsung = SamsungPerf.holding
        (if (samsung || threads.isNotEmpty()) r.boosted else r.unboosted).add(fps, ma, thermal)
        if (samsung) r.samsungHeld++
        if (threads.isNotEmpty()) {
            r.coresBoosted++
            for (tid in threads) {
                val tier = readText(File("/proc/$tid/stat"))?.let(::processorOf)?.let { tiers[it] } ?: continue
                r.onTier[tier]++
                r.recentTier[tier]++
            }
        }
        r.recent.add(fps)
        if (r.played % STATUS_S == 0) {
            status(r, threads.size, samsung, thermal, bm, ma)
            r.recent.clear()
            r.recentTier.fill(0)
        }
    }

    /** Whether Samsung's floors show in the kernel's minimum speeds, and whether they stay there. */
    private fun watchClocks(r: Run, gpuFile: File?) {
        if (!SamsungPerf.holding) {
            r.heldFor = 0
            r.fallen = false
            r.cpuBefore = cpuMins()
            r.gpuBefore = gpuFile?.let(::readLong)
            return
        }
        r.heldFor++
        if (r.heldFor == CONFIRM_S && r.clocks == null) {
            val now = cpuMins()
            val raised = now.filter { (policy, khz) -> r.cpuBefore[policy]?.let { khz > it * RAISED } == true }
            r.cpuRaised = raised.mapValues { (policy, khz) -> r.cpuBefore.getValue(policy) to khz }
            val cpu = when {
                now.isEmpty() -> "the CPU's minimum speed can't be read on this phone"
                raised.isEmpty() -> "the CPU's minimum speed did not change (${mhz(now)})"
                else -> "CPU minimum speed raised: " + r.cpuRaised!!.entries.joinToString { (policy, ba) ->
                    "$policy ${ba.first / 1000} -> ${ba.second / 1000} MHz"
                }
            }
            val gpuNow = gpuFile?.let(::readLong)
            val gpuThen = r.gpuBefore
            r.gpuRaised = gpuNow != null && gpuThen != null && gpuNow > gpuThen * RAISED
            val gpu = when {
                gpuNow == null || gpuThen == null -> "the GPU's minimum speed can't be read on this phone"
                r.gpuRaised -> "GPU minimum raised $gpuThen -> $gpuNow (${gpuFile?.path})"
                else -> "the GPU's minimum speed did not change ($gpuNow, ${gpuFile?.path})"
            }
            r.clocks = "$cpu; $gpu; the memory bus can't be seen from an app"
            line(r, "Samsung clocks held: library answered ${SamsungPerf.lastResults}; ${r.clocks}")
        }
        val raised = r.cpuRaised
        if (r.heldFor > CONFIRM_S && !raised.isNullOrEmpty()) {
            val now = cpuMins()
            val fell = raised.any { (policy, ba) -> now[policy]?.let { it < (ba.first + ba.second) / 2 } == true }
            if (fell && !r.fallen) {
                r.drops++
                line(r, "Samsung clocks let go early: the CPU minimum speed fell back (${mhz(now)}) while DroidDeck still asks for it")
            } else if (!fell && r.fallen) {
                line(r, "Samsung clocks: the CPU minimum speed is back up (${mhz(now)})")
            }
            r.fallen = fell
        }
    }

    private fun status(r: Run, threads: Int, samsung: Boolean, thermal: Int, bm: BatteryManager?, ma: Int?) {
        val state = when {
            paused -> "paused for the check"
            samsung || threads > 0 -> "on"
            else -> "not needed right now"
        }
        val checks = r.recentTier.sum()
        val cores = if (threads == 0) "no threads" else "$threads threads" +
            if (checks > 0) ", on the fastest cores ${r.recentTier[2] * 100 / checks} % of checks" else ""
        val battery = battery(bm)?.let { "$it %" + (ma?.let { m -> ", $m mA" } ?: "") + if (bm?.isCharging == true) ", charging" else "" } ?: "unknown"
        line(r, "status: boost $state; fastest cores: $cores; Samsung clocks: ${if (samsung) "held" else "not held"}; " +
            "CPU minimum ${mhz(cpuMins())}; game ${r.recent.average().roundToInt()} fps, slowest second ${r.recent.minOrNull() ?: 0}; " +
            "heat ${THERMAL.getOrElse(thermal) { "$thermal" }}; battery $battery")
    }

    private fun summary(r: Run) {
        if (!r.started) {
            line(r, "summary: the game never showed frames, nothing to report")
            return
        }
        val all = Side().apply { (r.boosted.fps + r.unboosted.fps).forEach { add(it, null, 0) } }
        line(r, "summary: ${clock(r.played)} of play")
        val checks = r.onTier.sum()
        line(r, "fastest cores: ${GameThreadHints.startLine.removePrefix("game thread hints: ")}; boosted ${share(r.coresBoosted, r.played)} of play" +
            (if (checks > 0) "; boosted threads were on the fastest cores in ${share(r.onTier[2], checks)} of checks, " +
                "middle ${share(r.onTier[1], checks)}, slowest ${share(r.onTier[0], checks)}" else "") +
            (if (GameThreadHints.refused > 0) "; Android refused a hint session ${GameThreadHints.refused} times" else ""))
        val counts = synchronized(letGoCounts) { letGoCounts.entries.joinToString { "${it.value}x ${it.key}" } }
        line(r, "Samsung clocks: ${SamsungPerf.state}; held ${share(r.samsungHeld, r.played)} of play; " +
            "${r.clocks ?: "never held long enough to look at the clocks"}; let go early ${r.drops} times" +
            if (counts.isNotEmpty()) "; DroidDeck let go: $counts" else "")
        line(r, "game: ${all.average.roundToInt()} fps average, slowest 5 % of seconds ${all.low}; " +
            "boosted ${r.boosted.average.roundToInt()} fps for ${clock(r.boosted.seconds)}, not boosted ${r.unboosted.average.roundToInt()} fps for ${clock(r.unboosted.seconds)} " +
            "(not a fair comparison: the boost runs when the game falls behind)")
        val start = r.batteryStart
        val end = r.batteryEnd
        val spanS = ((r.lastFrameMs - r.firstFrameMs) / 1000).toInt()
        val battery = when {
            r.charging -> "charging, not measured"
            start == null || end == null -> "unknown"
            else -> "$start % -> $end % over ${clock(spanS)}" + if (spanS >= 300) ", ${(start - end) * 3600 / spanS} % an hour" else ""
        }
        line(r, "heat: hottest ${THERMAL.getOrElse(r.hottest) { "${r.hottest}" }}; battery: $battery")
        if (r.check) {
            line(r, "check: counted ${clock(r.checkOn.seconds)} with the boost on and ${clock(r.checkOff.seconds)} paused (first $WARMUP_S s of play and first $SETTLE_S s after each switch skipped)")
            line(r, "check, boost on: ${side(r.checkOn)}")
            line(r, "check, paused:   ${side(r.checkOff)}")
            line(r, "verdict: ${verdict(r.checkOn, r.checkOff)}")
        } else {
            line(r, "verdict: ${working(r)}. Turn on Check the boost for one game to see whether it is worth keeping.")
        }
    }

    private fun working(r: Run): String {
        val start = GameThreadHints.startLine
        val cores = when {
            start != "game thread hints: watching" -> "fastest cores not on (${start.removePrefix("game thread hints: ")})"
            r.coresBoosted == 0 && GameThreadHints.refused > 0 -> "fastest cores refused by Android"
            r.coresBoosted == 0 -> "fastest cores never needed"
            else -> "fastest cores working"
        }
        val samsung = when {
            SamsungPerf.state.startsWith("not available") -> "Samsung clocks not available on this phone"
            r.samsungHeld == 0 -> "Samsung clocks never needed"
            r.drops > 0 -> "Samsung clocks working, but Samsung let go early ${r.drops} times"
            r.cpuRaised.isNullOrEmpty() && !r.gpuRaised -> "Samsung clocks asked for, but the phone shows no raised speed"
            else -> "Samsung clocks working and stayed on"
        }
        return "$cores; $samsung"
    }

    private fun side(s: Side) = "${"%.1f".format(Locale.US, s.average)} fps, slowest 5 % ${s.low}" +
        (s.draw?.let { ", battery ${it.roundToInt()} mA" } ?: "") + ", hottest ${THERMAL.getOrElse(s.hottest) { "${s.hottest}" }}"

    private fun share(part: Int, whole: Int) = if (whole == 0) "0 %" else "${part * 100 / whole} %"

    private fun clock(seconds: Int) = "%d:%02d".format(seconds / 60, seconds % 60)

    private fun mhz(mins: Map<String, Long>) = if (mins.isEmpty()) "unknown" else mins.values.joinToString("/") { "${it / 1000}" } + " MHz"

    /** Each CPU policy's minimum speed in kHz, as the kernel holds it now. */
    private fun cpuMins(): Map<String, Long> =
        File("/sys/devices/system/cpu/cpufreq").listFiles { f -> f.name.startsWith("policy") }.orEmpty()
            .sortedBy { it.name.removePrefix("policy").toIntOrNull() ?: 0 }
            .mapNotNull { p -> readLong(File(p, "scaling_min_freq"))?.let { p.name to it } }
            .toMap()

    private fun battery(bm: BatteryManager?): Int? =
        bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }

    private fun milliamps(bm: BatteryManager?): Int? {
        val raw = bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: return null
        if (raw == 0L || raw == Long.MIN_VALUE) return null
        return (abs(BatteryComponent.toMicroamps(raw)) / 1000).toInt()
    }

    private fun readText(f: File): String? = try { f.readText() } catch (_: Exception) { null }

    private fun readLong(f: File): Long? = readText(f)?.trim()?.toLongOrNull()
}
