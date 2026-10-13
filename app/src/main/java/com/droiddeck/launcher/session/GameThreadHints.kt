package com.droiddeck.launcher.session

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.PowerManager
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.io.File
import kotlin.math.abs

/**
 * The game's busiest threads named to Android's performance hint service (ADPF) for a session.
 *
 * Qualcomm's WALT moves a thread to the prime cores only once it fills a mid core (the Odin 3's
 * sched_upmigrate is 95), and a game thread that waits on its workers or the GPU for part of each
 * frame never does: Factorio's update thread sat at ~68% on cores 0-5 with 6-7 idle, and ran 60%
 * faster pinned to 6-7. An app may not raise a thread's uclamp (EPERM without CAP_SYS_NICE), but a
 * hint session may name any thread of its UID - the guest's are - and one reported behind its
 * target is boosted by the power HAL onto the prime cores: on the Odin 3 a 70%-busy thread of a
 * child process went from cores 0-5 to 6-7 and ran 1.7x faster within a second.
 *
 * Once a second the busiest threads of this UID are read from /proc; one busy more than [ENTER] of
 * the time (averaged with the second before, so a loading spike does not count) joins the session,
 * and leaves it under [LEAVE] - well below, since it runs faster where it was moved. While the
 * session has threads it reports them behind target, which is what asks for the boost: the reports
 * carry no frame timing, the busy share is the signal (a capped game's frame interval looks the same
 * as a slow one's). Nothing is boosted while the device is critically hot or saves power.
 *
 * Nor while the game holds the panel's rate or a fraction of it ([holdsPace]): there is nothing to
 * gain, and it cost frame pacing - Serious Sam capped at 120 fps ran its boosted main thread on prime
 * cores the governor kept at 2.2 GHz, slower than a 3.5 GHz mid core, and 23-25% of its frames fell
 * under 90 fps against 2-7% unboosted. A game with no frames (Factorio's benchmark, a loading screen)
 * or one falling behind the panel is boosted; one holding pace keeps what it has for [PACE_LEAVE_S]
 * seconds and is then let go.
 */
object GameThreadHints {
    private const val TAG = "GameThreadHints"
    private const val TARGET_NS = 10_000_000L
    private const val REPORT_MS = 50L
    private const val SCAN_EVERY = 20 // reports, so once a second
    private const val ENTER = 0.6
    private const val LEAVE = 0.15 // a capped game's main thread idles to ~30% on a prime core; under 0.3 it hopped back and forth
    private const val MAX_THREADS = 2 // one per prime core; a third would be sped up in place instead
    private const val TOP_PROCESSES = 3
    private const val PACE_WINDOW_S = 3
    private const val PACE_LEAVE_S = 10
    private const val PACE_TOLERANCE = 0.08

    /** Busy, but not the game: the app itself and the Steam client, whose UI would otherwise hold the prime cores. */
    private val NOT_GAMES = setOf("steam", "steamwebhelper")

    private var worker: Thread? = null

    /** At a session's start. The line is for the log. */
    @Synchronized
    fun start(context: Context): String {
        if (!SessionPrefs.gameThreadHints(context)) return "game thread hints: off"
        // Measured on the Odin 3 (CQ8725S) only; another HAL may boost differently, or not at all.
        if (Build.VERSION.SDK_INT < 31 || Build.SOC_MODEL != "CQ8725S") return "game thread hints: Odin 3 only"
        val hm = context.getSystemService(PerformanceHintManager::class.java)
            ?: return "game thread hints: no PerformanceHintManager"
        val pm = context.getSystemService(PowerManager::class.java)
        worker?.interrupt()
        worker = Thread({ run(hm, pm) }, "game-thread-hints").apply { isDaemon = true; start() }
        return "game thread hints: watching"
    }

    /** At a session's end. */
    @Synchronized
    fun stop() {
        worker?.interrupt()
        worker = null
    }

    private fun run(hm: PerformanceHintManager, pm: PowerManager) {
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
                    // Not SEVERE: the Odin 3 reports it once its skin reaches 50 C, a couple of minutes into any game.
                    val spare = pm.isPowerSaveMode || pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL
                    val next = when {
                        spare || pacedFor >= PACE_LEAVE_S -> emptySet()
                        paced -> pick(boosted, busy.filterKeys { it in boosted })
                        else -> pick(boosted, busy)
                    }
                    if (next != boosted) {
                        session = update(hm, session, next)
                        // No session means a thread exited in between: tried again next second.
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

    /** A new session over [tids] in place of [session]; null for none. The set changes seldom, so no setThreads. */
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

    /**
     * Whether a game presenting [fps] frames a second keeps up with a [refreshHz] panel: within
     * [PACE_TOLERANCE] of the rate or of a half, a third or a quarter of it (a 60 fps limiter on 120 Hz).
     * A game with next to no frames (a headless benchmark, a loading screen) does not.
     */
    // ponytail: a CPU-bound game that happens to run at ~60 fps on a 120 Hz panel reads as limited; narrow the band if that shows up
    internal fun holdsPace(fps: Double, refreshHz: Double): Boolean =
        fps >= 10 && refreshHz > 0 && (1..4).any { k -> abs(fps - refreshHz / k) < PACE_TOLERANCE * refreshHz / k }

    /**
     * The threads to boost next: those already boosted that are still above [LEAVE], then new ones
     * above [ENTER], busiest first, at most [MAX_THREADS].
     */
    internal fun pick(boosted: Set<Int>, busy: Map<Int, Double>): Set<Int> =
        busy.entries
            .filter { (tid, share) -> share >= if (tid in boosted) LEAVE else ENTER }
            .sortedWith(compareByDescending<Map.Entry<Int, Double>> { it.key in boosted }.thenByDescending { it.value })
            .take(MAX_THREADS)
            .map { it.key }
            .toSet()

    private fun readName(path: String) = try { File(path).readText().trim() } catch (_: Exception) { "?" }

    /** Busy shares of this UID's threads since the last sample: /proc shows an app only its own processes. */
    private class Scanner {
        private val self = Process.myPid()
        private val hz = Os.sysconf(OsConstants._SC_CLK_TCK).toDouble()
        private var lastNs = 0L
        private var lastProcess = HashMap<Int, Long>()
        private var lastThread = HashMap<Int, Long>()
        private var lastBusy = HashMap<Int, Double>()

        /** Only the busiest few processes have their threads read, so a second costs a few dozen small reads. */
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

        /** utime + stime of a /proc stat line; fields counted after the command's closing parenthesis. */
        private fun cpuTicks(path: String): Long? = try {
            val line = File(path).readText()
            val fields = line.substring(line.lastIndexOf(')') + 2).split(' ')
            fields[11].toLong() + fields[12].toLong()
        } catch (_: Exception) {
            null
        }
    }
}
