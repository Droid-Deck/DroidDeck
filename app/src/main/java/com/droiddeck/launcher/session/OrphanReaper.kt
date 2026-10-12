package com.droiddeck.launcher.session

import android.util.Log
import java.io.File

/**
 * Kills every process of this app's uid other than the app itself.
 *
 * A session is a tree of processes under proot, and proot dies with the app only when the app's
 * own teardown runs. When the app is killed or crashes instead, the tree stays: proot holding the
 * rootfs, gamescope holding the GPU, Steam holding its lock file - and the next session never
 * comes up, or comes up beside a client that is already running. A user on a Thor Pro had to
 * force-stop the app before every launch. Everything of ours that should be alive between
 * sessions is inside the app process, so at session start anything else under our uid is a
 * leftover, and this sweeps it. Each pid is checked by uid at kill time, never by name.
 *
 * The one exception is work the app started outside any session and is still waiting on - a
 * Flatpak install from the store - registered with [keep]: that process and everything under it
 * are left to finish. Session-owned Flatpak sandboxes are tracked separately and must be
 * included in teardown verification.
 */
object OrphanReaper {
    private const val TAG = "OrphanReaper"
    private val kept = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private val sessionKept = java.util.concurrent.ConcurrentHashMap.newKeySet<ProcessCleanup.Identity>()

    fun keep(pid: Int) { if (pid > 0) kept.add(pid) }

    /** Tracks a session-owned sandbox so shutdown must verify its exit instead of sparing it. */
    internal fun keepSession(identity: ProcessCleanup.Identity) { sessionKept.add(identity) }

    fun release(pid: Int) {
        kept.remove(pid)
        sessionKept.removeIf { it.pid == pid }
    }

    internal fun release(identity: ProcessCleanup.Identity) { sessionKept.remove(identity) }

    @JvmStatic
    fun hasSessionProcesses(): Boolean = sessionKept.isNotEmpty()

    internal fun identity(pid: Int): ProcessCleanup.Identity? =
        readProcess(pid)?.takeIf { it.alive }?.identity

    internal fun isAlive(identity: ProcessCleanup.Identity): Boolean =
        readProcess(identity.pid)?.let { it.identity == identity && it.alive } == true

    internal fun reap(reason: String): ProcessCleanup.Result = cleanup(reason) { processes ->
        processes
    }

    /**
     * proot's --kill-on-exit takes its tracees down, but a session that died from the inside
     * (the client asserting, Xwayland going) leaves gamescopereaper and the session script
     * behind, still holding the Wayland socket and the audio server the next session needs.
     *
     * The tree sweep only reaches what proot still traces; a tracee it lost - an Xwayland that
     * aborted from one thread and then looped on a syscall proot's own seccomp filter, with no
     * tracer left, answers ENOSYS - survives, reparented to init, and wrote 9 GB of one line into
     * the session log before anything killed it. Also match programs running out of the runtime,
     * restricted to our uid and excluding independently protected work.
     * The caller must exclude new sessions until this shared-runtime sweep finishes.
     */
    internal fun reapSession(root: File, roots: Set<ProcessCleanup.Identity>): ProcessCleanup.Result {
        val path = root.canonicalPath + "/"
        return cleanup("session stopped") { processes ->
            sessionTargets(processes, roots, sessionKept, path)
        }
    }

    internal fun reapTree(identity: ProcessCleanup.Identity): ProcessCleanup.Result {
        val tracked = hashSetOf(identity)
        return cleanup("auxiliary session stopped") { processes ->
            tracked.addAll(ProcessCleanup.descendants(processes, tracked))
            processes.filter { it.identity in tracked }
        }
    }

    private fun cleanup(
        reason: String,
        select: (List<ProcessCleanup.Entry>) -> Collection<ProcessCleanup.Entry>,
    ): ProcessCleanup.Result {
        val cleanup = ProcessCleanup(
            snapshot = ::processes,
            current = ::readProcess,
            signal = { pid, signal -> android.os.Process.sendSignal(pid, signal) },
            now = android.os.SystemClock::elapsedRealtime,
            sleep = Thread::sleep,
        )
        val result = cleanup.terminate { processes ->
            val protected = processes.filter { it.identity.pid in kept }.mapTo(HashSet()) { it.identity }
            unprotectedTargets(processes, select(processes), protected)
        }
        if (result.complete) {
            Log.i(TAG, "confirmed ${result.stopped} process(es) exited - $reason")
        } else {
            Log.e(TAG, "process cleanup incomplete - $reason: ${result.remaining}")
        }
        return result
    }

    internal fun sessionTargets(
        processes: List<ProcessCleanup.Entry>,
        roots: Set<ProcessCleanup.Identity>,
        sessionProcesses: Set<ProcessCleanup.Identity>,
        runtimePath: String,
    ): List<ProcessCleanup.Entry> {
        val tracked = HashSet(roots)
        tracked.addAll(sessionProcesses)
        tracked.addAll(ProcessCleanup.descendants(processes, tracked))
        return processes.filter { it.identity in tracked || it.executable.startsWith(runtimePath) }
    }

    internal fun unprotectedTargets(
        processes: List<ProcessCleanup.Entry>,
        targets: Collection<ProcessCleanup.Entry>,
        protectedRoots: Set<ProcessCleanup.Identity>,
    ): List<ProcessCleanup.Entry> {
        val spared = ProcessCleanup.descendants(processes, protectedRoots)
        return targets.filter { it.identity !in spared }
    }

    private fun processes(): List<ProcessCleanup.Entry> {
        val entries = checkNotNull(File("/proc").listFiles()) { "Could not enumerate session processes" }
        return entries.mapNotNull { it.name.toIntOrNull()?.let(::readProcess) }
    }

    private fun readProcess(pid: Int): ProcessCleanup.Entry? {
        if (pid <= 1 || pid == android.os.Process.myPid() || uidOf(pid) != android.os.Process.myUid()) return null
        val directory = File("/proc/$pid")
        val stat = try {
            File(directory, "stat").readText()
        } catch (e: java.io.IOException) {
            if (!directory.exists()) return null
            throw java.io.IOException("Could not inspect session process $pid", e)
        }
        // "pid (comm) state ppid ...": comm may hold spaces and parentheses; the last ')' ends it.
        val fields = stat.substringAfterLast(") ").trim().split(Regex("\\s+"))
        check(fields.size >= 20) { "Invalid process stat for $pid" }
        val executable = try {
            File(directory, "exe").canonicalPath
        } catch (e: java.io.IOException) {
            Log.w(TAG, "could not resolve executable for process $pid", e)
            ""
        }
        return ProcessCleanup.Entry(
            ProcessCleanup.Identity(pid, fields[19].toLong()),
            fields[1].toInt(), fields[0].first(), executable,
        )
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
