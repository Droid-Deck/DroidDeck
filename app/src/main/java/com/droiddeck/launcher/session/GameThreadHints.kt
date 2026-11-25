package com.droiddeck.launcher.session

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.PowerManager
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.droiddeck.launcher.gpu.GpuInfo
import com.droiddeck.launcher.gpu.SocNames
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.io.File
import kotlin.math.abs

object GameThreadHints {
    private const val TAG = "GameThreadHints"
    private const val TARGET_NS = 10_000_000L
    private const val REPORT_MS = 50L
    private const val SCAN_EVERY = 20
    private const val ENTER = 0.6
    private const val LEAVE = 0.15
    private const val MAX_THREADS = 2
    private const val TOP_PROCESSES = 3
    private const val PACE_WINDOW_S = 3
    private const val PACE_LEAVE_S = 10
    private const val PACE_TOLERANCE = 0.08

    private val NOT_GAMES = setOf("steam", "steamwebhelper")
    private val POLICY_NAME = Regex("""policy\d+""")
    private val QTI_BIN_SUFFIX = Regex("""-(?:\d+-)?[A-Z]{2}$""")
    private val DIMENSITY_NAME = Regex("""^(?:MEDIATEK\s+)?DIMENSITY\s*[89]\d{3}\b.*""")
    private val SOCS = setOf(
        "CQ8725S",
        "SM7475", "SM7475P", "SM7675", "SM7675P",
        "SM8450", "SM8475", "SM8475P",
        "SM8550", "SM8550P", "QCS8550", "QCS8550P",
        "SM8635", "SM8635P", "SM8650", "SM8650P", "QCS8650", "SG8275", "SG8275P", "SG8350", "SG8350P",
        "SM8735", "SM8735P", "SM8750", "SM8750P",
        "SM8845", "SM8845P", "SM8850", "SM8850P",
        "SM8975", "SM8975P",
        "MT6895", "MT6895Z", "MT6895ZB", "MT6896", "MT6897", "MT6897Z", "MT6899",
        "MT6983", "MT6983W", "MT6985", "MT6985W", "MT6989", "MT6989W", "MT6991", "MT6991W", "MT6993",
        "S5E9925", "S5E9945", "S5E9955", "EXYNOS2200", "EXYNOS2400", "EXYNOS2400E", "EXYNOS2500",
        "GS101", "GS201", "GS301", "GS401", "ZUMA", "ZUMAPRO", "ZUMA_PRO",
        "TENSOR", "GOOGLETENSOR", "TENSORG1", "GOOGLETENSORG1",
        "TENSORG2", "GOOGLETENSORG2", "TENSORG3", "GOOGLETENSORG3", "TENSORG4", "GOOGLETENSORG4",
    )
    private val PLATFORMS = setOf(
        "taro", "cape", "kalama", "pineapple", "cliff", "sun", "canoe",
        "gs101", "gs201", "zuma", "zumapro", "zuma_pro",
        "s5e9925", "s5e9945", "s5e9955",
        "mt6895", "mt6896", "mt6897", "mt6899", "mt6983", "mt6985", "mt6989", "mt6991", "mt6993",
    )

    private var worker: Thread? = null

    @Synchronized
    fun start(context: Context): String {
        if (!SessionPrefs.gameThreadHints(context)) return "game thread hints: off"
        if (Build.VERSION.SDK_INT < 31) return "game thread hints: Android 12+ only"
        val soc = SocNames.model()
        val platform = GpuInfo.systemProperty("ro.board.platform")
        if (!supported(soc, platform, File("/sys/devices/system/cpu/cpufreq"))) {
            return "game thread hints: homogeneous or unknown CPU"
        }
        val hm = context.getSystemService(PerformanceHintManager::class.java)
            ?: return "game thread hints: no PerformanceHintManager"
        if (runCatching { hm.preferredUpdateRateNanos }.getOrDefault(-1L) <= 0L) {
            return "game thread hints: ADPF hint sessions not supported"
        }
        val pm = context.getSystemService(PowerManager::class.java)
        worker?.interrupt()
        worker = Thread({ run(hm, pm) }, "game-thread-hints").apply { isDaemon = true; start() }
        return "game thread hints: watching"
    }

    @Synchronized
    fun stop() {
        worker?.interrupt()
        worker = null
    }

    private fun run(hm: PerformanceHintManager, pm: PowerManager?) {
        val scanner = Scanner()
        var session: PerformanceHintManager.Session? = null
        var boosted = emptySet<Int>()
        val frames = ArrayDeque<Long>()
        var pacedFor = 0
        var tick = 0
        try {
            while (!Thread.currentThread().isInterrupted) {
                if (tick++ % SCAN_EVERY == 0) {
                    val busy = scanner.sample()
                    frames.addLast(WaylandCompositor.gameFrameCount())
                    if (frames.size > PACE_WINDOW_S + 1) frames.removeFirst()
                    val fps = (frames.last() - frames.first()).toDouble() / (frames.size - 1).coerceAtLeast(1)
                    val paced = frames.size > PACE_WINDOW_S && holdsPace(fps, SessionState.refreshHz.toDouble())
                    pacedFor = if (paced) pacedFor + 1 else 0
                    val spare = pm?.isPowerSaveMode == true ||
                        (pm != null && pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL)
                    val next = when {
                        spare || pacedFor >= PACE_LEAVE_S -> emptySet()
                        paced -> pick(boosted, busy.filterKeys { it in boosted })
                        else -> pick(boosted, busy)
                    }
                    if (next != boosted) {
                        session = update(hm, session, next)
                        boosted = if (session == null) emptySet() else next
                        Log.i(TAG, (if (session == null && next.isNotEmpty()) "no session for " else "boosting ") +
                            next.joinToString { "${readName("/proc/$it/comm")}/$it ${((busy[it] ?: 0.0) * 100).toInt()}%" }.ifEmpty { "nothing" } +
                            " (game ${fps.toInt()} fps${if (paced) ", holding pace" else ""})")
                    }
                }
                session?.reportActualWorkDuration(TARGET_NS * 3 / 2)
                Thread.sleep(REPORT_MS)
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            Log.w(TAG, "stopped", t)
        } finally {
            session?.close()
        }
    }

    private fun update(hm: PerformanceHintManager, session: PerformanceHintManager.Session?, tids: Set<Int>): PerformanceHintManager.Session? {
        session?.close()
        if (tids.isEmpty()) return null
        return try {
            hm.createHintSession(tids.toIntArray(), TARGET_NS)
        } catch (t: RuntimeException) {
            Log.w(TAG, "createHintSession", t)
            null
        }
    }

    internal fun supported(soc: String): Boolean = supported(soc, "", null)

    internal fun supported(soc: String, platform: String, cpufreqDir: File?): Boolean =
        isKnownSoc(soc) || isKnownSoc(platform) || platform.trim().lowercase() in PLATFORMS ||
            (cpufreqDir != null && hasAsymmetricClusters(cpufreqDir))

    internal fun hasAsymmetricClusters(cpufreqDir: File = File("/sys/devices/system/cpu/cpufreq")): Boolean {
        val policies = cpufreqDir.listFiles { file -> file.isDirectory && POLICY_NAME.matches(file.name) } ?: return false
        val maxFreqs = policies.mapNotNull { policy ->
            runCatching { File(policy, "cpuinfo_max_freq").readText().trim().toLongOrNull() }.getOrNull()?.takeIf { it > 0 }
        }.toSet()
        return maxFreqs.size >= 2
    }

    private fun isKnownSoc(raw: String): Boolean {
        val clean = SocNames.normalizedModel(raw).removePrefix("MTK ").trim().substringBefore('/').trim()
        if (clean.isEmpty()) return false
        val collapsed = clean.replace(Regex("""[\s_-]+"""), "")
        val qtiBase = clean.replace(QTI_BIN_SUFFIX, "")
        return clean in SOCS || collapsed in SOCS || qtiBase in SOCS || DIMENSITY_NAME.matches(clean)
    }

    internal fun holdsPace(fps: Double, refreshHz: Double): Boolean =
        fps >= 10 && refreshHz > 0 && (1..4).any { k -> abs(fps - refreshHz / k) < PACE_TOLERANCE * refreshHz / k }

    internal fun pick(boosted: Set<Int>, busy: Map<Int, Double>): Set<Int> =
        busy.entries
            .filter { (tid, share) -> share >= if (tid in boosted) LEAVE else ENTER }
            .sortedWith(compareByDescending<Map.Entry<Int, Double>> { it.key in boosted }.thenByDescending { it.value })
            .take(MAX_THREADS)
            .map { it.key }
            .toSet()

    private fun readName(path: String) = try { File(path).readText().trim() } catch (_: Exception) { "?" }

    private class Scanner {
        private val self = Process.myPid()
        private val hz = Os.sysconf(OsConstants._SC_CLK_TCK).toDouble()
        private var lastNs = 0L
        private var lastProcess = HashMap<Int, Long>()
        private var lastThread = HashMap<Int, Long>()
        private var lastBusy = HashMap<Int, Double>()

        fun sample(): Map<Int, Double> {
            val now = System.nanoTime()
            val seconds = (now - lastNs) / 1e9
            lastNs = now
            val process = HashMap<Int, Long>()
            File("/proc").list()?.forEach { entry ->
                val pid = entry.toIntOrNull() ?: return@forEach
                cpuTicks("/proc/$pid/stat")?.let { process[pid] = it }
            }
            val top = process.entries
                .map { (pid, ticks) -> pid to ticks - (lastProcess[pid] ?: ticks) }
                .filter { (pid, delta) -> delta > 0 && pid != self }
                .sortedByDescending { it.second }
                .asSequence()
                .filter { (pid, _) -> readName("/proc/$pid/comm") !in NOT_GAMES }
                .take(TOP_PROCESSES)
                .toList()
            lastProcess = process
            val thread = HashMap<Int, Long>()
            val busy = HashMap<Int, Double>()
            for ((pid, _) in top) {
                File("/proc/$pid/task").list()?.forEach { entry ->
                    val tid = entry.toIntOrNull() ?: return@forEach
                    val ticks = cpuTicks("/proc/$pid/task/$tid/stat") ?: return@forEach
                    thread[tid] = ticks
                    lastThread[tid]?.let { before -> busy[tid] = ((ticks - before) / hz / seconds + (lastBusy[tid] ?: 0.0)) / 2 }
                }
            }
            lastThread = thread
            lastBusy = busy
            return busy
        }

        private fun cpuTicks(path: String): Long? = try {
            val line = File(path).readText()
            val fields = line.substring(line.lastIndexOf(')') + 2).split(' ')
            fields[11].toLong() + fields[12].toLong()
        } catch (_: Exception) {
            null
        }
    }
}
