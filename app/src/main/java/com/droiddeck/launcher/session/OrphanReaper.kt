package com.droiddeck.launcher.session

import android.util.Log
import java.io.File

object OrphanReaper {
    private const val TAG = "OrphanReaper"
    private val kept = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    fun keep(pid: Int) { if (pid > 0) kept.add(pid) }

    fun release(pid: Int) { kept.remove(pid) }

    /** Signal 0 checks ownership/liveness without sending a signal, even when /proc is unreadable. */
    fun hasOtherProcesses(): Boolean = hasOtherProcesses(
        File("/proc").list()?.mapNotNull { it.toIntOrNull() }, android.os.Process.myPid(),
    ) { pid ->
        try {
            android.system.Os.kill(pid, 0)
            true
        } catch (error: android.system.ErrnoException) {
            error.errno != android.system.OsConstants.ESRCH && error.errno != android.system.OsConstants.EPERM
        } catch (error: SecurityException) {
            true
        }
    }

    internal fun hasOtherProcesses(pids: List<Int>?, ownPid: Int, signalable: (Int) -> Boolean): Boolean =
        pids == null || pids.any { it > 1 && it != ownPid && signalable(it) }

    fun reap(reason: String): Int {
        val me = android.os.Process.myPid()
        val uid = android.os.Process.myUid()
        val spared = descendants(kept)
        var killed = 0
        File("/proc").listFiles()?.forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            if (pid == me || pid <= 1 || pid in spared) return@forEach
            if (uidOf(pid) != uid) return@forEach
            val name = try { File(entry, "cmdline").readText().substringBefore('\u0000') } catch (e: Exception) { "?" }
            try {
                android.os.Process.killProcess(pid)
                killed++
                Log.i(TAG, "killed leftover $pid ($name) - $reason")
            } catch (e: Exception) {
                Log.w(TAG, "could not kill $pid ($name)", e)
            }
        }
        if (killed > 0) Log.i(TAG, "$killed leftover process(es) from an earlier session - $reason")
        return killed
    }

    private fun descendants(roots: Set<Int>): Set<Int> {
        if (roots.isEmpty()) return emptySet()
        val parent = HashMap<Int, Int>()
        File("/proc").listFiles()?.forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            val stat = try { File(entry, "stat").readText() } catch (e: Exception) { return@forEach }
            stat.substringAfterLast(") ").split(' ').getOrNull(1)?.toIntOrNull()?.let { parent[pid] = it }
        }
        val out = HashSet(roots)
        var grew = true
        while (grew) {
            grew = false
            for ((pid, ppid) in parent) if (ppid in out && out.add(pid)) grew = true
        }
        return out
    }

    private fun uidOf(pid: Int): Int {
        return try {
            File("/proc/$pid/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("Uid:") }
                    ?.substringAfter("Uid:")?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull() ?: -1
            }
        } catch (e: Exception) {
            -1
        }
    }
}
