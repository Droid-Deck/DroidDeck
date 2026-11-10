package com.droiddeck.launcher.agent

import android.os.Process
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import java.io.File

/**
 * Linux userland processes, read from /proc: proot runs them as real processes under the app's
 * own uid, so the app can list and end them without a shell.
 */
object AgentProcesses {
    data class Proc(
        val pid: Int, val ppid: Int, val name: String, val args: String,
        val seconds: Long, val rssKb: Long, val cpuPercent: Int, val agent: Boolean,
    )

    private const val HZ = 100L
    private var lastTicks = emptyMap<Int, Long>()
    private var lastAt = 0L

    @VisibleForTesting
    internal class Stat(val pid: Int, val ppid: Int, val ticks: Long, val startTicks: Long, val rssPages: Long)

    /** Guest processes, those under [turnPid] (the agent's current turn) first. */
    @Synchronized
    fun snapshot(turnPid: Int): List<Proc> {
        val me = Process.myPid()
        val uid = Process.myUid()
        val stats = HashMap<Int, Stat>()
        val args = HashMap<Int, String>()
        for (dir in File("/proc").listFiles().orEmpty()) {
            val pid = dir.name.toIntOrNull() ?: continue
            if (pid == me) continue
            val stat = runCatching { parseStat(File(dir, "stat").readText()) }.getOrNull() ?: continue
            if (!owned(dir, uid)) continue
            stats[pid] = stat
            args[pid] = runCatching { File(dir, "cmdline").readBytes().toString(Charsets.UTF_8).trimEnd('\u0000').replace('\u0000', ' ') }.getOrDefault("")
        }
        val now = SystemClock.elapsedRealtime()
        val uptimeTicks = now * HZ / 1000
        val elapsed = (now - lastAt).coerceAtLeast(1)
        fun isProot(pid: Int) = args[pid].orEmpty().substringBefore(' ').endsWith("proot.so") || args[pid].orEmpty().substringBefore(' ').endsWith("/proot")
        fun ancestors(pid: Int) = generateSequence(stats[pid]?.ppid) { stats[it]?.ppid }.takeWhile { it > 1 }.take(64)
        val out = stats.values.filter { s ->
            !isProot(s.pid) && args[s.pid].orEmpty().isNotEmpty() && ancestors(s.pid).any { isProot(it) }
        }.map { s ->
            val full = args[s.pid].orEmpty()
            val cpu = lastTicks[s.pid]?.let { ((s.ticks - it) * 1000 * 100 / HZ / elapsed).toInt().coerceIn(0, 800) } ?: 0
            Proc(
                s.pid, s.ppid, full.substringBefore(' ').substringAfterLast('/'), full,
                ((uptimeTicks - s.startTicks) / HZ).coerceAtLeast(0), s.rssPages * 4, cpu,
                turnPid > 0 && ancestors(s.pid).any { it == turnPid },
            )
        }
        lastTicks = stats.mapValues { it.value.ticks }
        lastAt = now
        return out.sortedWith(compareByDescending<Proc> { it.agent }.thenBy { it.seconds })
    }

    /** Ends [pid] and everything it started, children first so none is left to be re-parented. */
    fun killTree(pid: Int) {
        if (pid <= 0) return
        val children = HashMap<Int, MutableList<Int>>()
        for (dir in File("/proc").listFiles().orEmpty()) {
            val child = dir.name.toIntOrNull() ?: continue
            val stat = runCatching { parseStat(File(dir, "stat").readText()) }.getOrNull() ?: continue
            children.getOrPut(stat.ppid) { mutableListOf() } += child
        }
        val order = mutableListOf<Int>()
        fun walk(p: Int) {
            children[p].orEmpty().forEach(::walk)
            order += p
        }
        walk(pid)
        order.forEach { runCatching { Process.sendSignal(it, Process.SIGNAL_KILL) } }
    }

    fun alive(pid: Int) = pid > 0 && File("/proc/$pid").exists()

    /** Processes whose command line contains [text], for ending a build the app did not start. */
    fun find(text: String): List<Int> = File("/proc").listFiles().orEmpty().mapNotNull { dir ->
        val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
        if (pid == Process.myPid()) return@mapNotNull null
        val args = runCatching { File(dir, "cmdline").readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ') }.getOrDefault("")
        pid.takeIf { args.contains(text) && owned(dir, Process.myUid()) }
    }

    private fun owned(dir: File, uid: Int) = runCatching {
        File(dir, "status").useLines { lines -> lines.first { it.startsWith("Uid:") }.split(Regex("\\s+"))[1].toInt() == uid }
    }.getOrDefault(false)

    /** `/proc/<pid>/stat`: the name in parentheses may hold spaces, so fields count from its end. */
    @VisibleForTesting
    internal fun parseStat(text: String): Stat {
        val pid = text.substringBefore(' ').toInt()
        val f = text.substring(text.lastIndexOf(')') + 2).split(' ')
        return Stat(pid, f[1].toInt(), f[11].toLong() + f[12].toLong(), f[19].toLong(), f[21].toLong())
    }

    /** The pid of a [java.lang.Process] the app started. */
    fun pidOf(process: java.lang.Process): Int =
        runCatching { process.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(process) }.getOrNull()
            ?: Regex("pid=(\\d+)").find(process.toString())?.groupValues?.get(1)?.toInt() ?: 0
}
