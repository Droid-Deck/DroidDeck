package com.droiddeck.launcher.session

import android.os.Process
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

internal class SessionSuspendController(
    private val sessionRoot: () -> Int,
    private val helperRoots: () -> List<Int>,
    private val suspendAudio: () -> Unit,
    private val resumeAudio: () -> Unit,
    private val procRoot: File = File("/proc"),
    private val signal: (Int, Int) -> Unit = Process::sendSignal,
    private val threadSignal: (Int, Int, Int) -> Unit = { pid, tid, signal -> ThreadSignals.tgkill(pid, tid, signal) },
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val zramControl: File? = null,
    private val pagedOut: (processes: Int, unacknowledged: Int, residentKb: Long, swapKb: Long, swapBeforeKb: Long, millis: Long) -> Unit =
        { _, _, _, _, _, _ -> },
) {
    private enum class Role { SESSION_ROOT, SESSION_CHILD, HELPER_ROOT, HELPER_CHILD }

    private data class ProcessRecord(
        val pid: Int,
        val started: Long,
        val role: Role,
    )

    private data class ProcessInfo(
        val pid: Int,
        val parent: Int,
        val started: Long,
        val state: Char,
        val tracer: Int,
    )

    private data class TaskIdentity(val process: ProcessRecord, val tid: Int, val started: Long)

    @Volatile private var closing = false
    @Volatile private var cancelled = false
    private var frozen = emptyList<ProcessRecord>()

    fun freeze(pageOut: Boolean = false, completion: (Boolean) -> Unit) {
        cancelled = false
        submit({
            val records = frozen.associateByTo(LinkedHashMap()) { it.pid to it.started }
            var complete = false
            var interrupted = false
            try {
                val root = records.values.firstOrNull { it.role == Role.SESSION_ROOT }
                    ?: readProcess(sessionRoot())?.takeIf { it.pid > 1 }?.let {
                        ProcessRecord(it.pid, it.started, Role.SESSION_ROOT)
                    } ?: throw IOException("Session root is unavailable")
                records[root.pid to root.started] = root
                runAudio(suspendAudio)
                val helpers = helperRoots().filter { it > 1 && it != root.pid }.distinct()
                for (helper in helpers) {
                    if (records.values.any { it.pid == helper && it.role == Role.HELPER_ROOT }) continue
                    readProcess(helper)?.let {
                        records[it.pid to it.started] = ProcessRecord(it.pid, it.started, Role.HELPER_ROOT)
                    }
                }
                if (pageOut) pageOut(records, root.pid, helpers)
                val deadline = nowMillis() + FREEZE_TIMEOUT_MS
                var previous: Map<TaskIdentity, Long>? = null
                for (pass in 0 until FREEZE_MAX_PASSES) {
                    if (closing || cancelled || nowMillis() >= deadline) break
                    try {
                        if (readProcess(root.pid)?.started != root.started) break
                        addSnapshot(records, root.pid, helpers)
                        if (readProcess(root.pid)?.started != root.started) break
                        stop(records.values.toList())
                        sleep(if (pass == 0) 70L else 35L)
                        if (closing || cancelled || nowMillis() >= deadline) break
                        val current = stoppedSnapshot(records.values.toList(), root)
                        if (current != null && current == previous) {
                            complete = true
                            break
                        }
                        previous = current
                    } catch (_: IOException) {
                        previous = null
                        sleep(35L)
                    }
                }
            } catch (_: InterruptedException) {
                interrupted = true
                Thread.interrupted()
            } catch (_: Exception) {
            } finally {
                frozen = records.values.toList()
                if (!complete) {
                    if (restoreSafely(frozen)) frozen = emptyList()
                    runAudio(resumeAudio)
                }
                if (interrupted) Thread.currentThread().interrupt()
            }
            completion(complete)
        }, { completion(false) })
    }

    // A freeze still queued or paging out stops before its next SIGSTOP pass and rolls back.
    fun cancelFreeze() {
        cancelled = true
    }

    fun resume(completion: (Boolean) -> Unit) {
        submit({
            val records = frozen
            runAudio(resumeAudio)
            val resumed = restoreSafely(records)
            if (resumed) frozen = emptyList()
            completion(resumed)
        }, { completion(false) })
    }

    fun closeAndResume(completion: () -> Unit) {
        closing = true
        submit({
            if (restoreSafely(frozen)) frozen = emptyList()
            completion()
            executor.shutdown()
        }, completion)
    }

    private fun submit(task: () -> Unit, rejected: () -> Unit) {
        try {
            executor.execute(task)
        } catch (_: RejectedExecutionException) {
            rejected()
        }
    }

    private fun runAudio(action: () -> Unit) {
        try {
            action()
        } catch (_: Exception) {
        }
    }

    private fun addSnapshot(
        records: MutableMap<Pair<Int, Long>, ProcessRecord>,
        root: Int,
        helpers: List<Int>,
    ) {
        val processes = readProcesses()
        val byPid = processes.associateBy { it.pid }
        val rootRecord = records.values.first { it.role == Role.SESSION_ROOT }
        if (byPid[root]?.started != rootRecord.started) throw IOException("Session root exited or changed")
        val liveHelpers = helpers.filter { pid ->
            records.values.any { it.pid == pid && it.role == Role.HELPER_ROOT && it.started == byPid[pid]?.started }
        }
        for (process in descendants(root, processes, byPid) + processes.filter { it.tracer == root }) {
            records.putIfAbsent(process.pid to process.started, ProcessRecord(process.pid, process.started, Role.SESSION_CHILD))
        }
        for (helper in liveHelpers) {
            for (process in descendants(helper, processes, byPid)) {
                records.putIfAbsent(process.pid to process.started, ProcessRecord(process.pid, process.started, Role.HELPER_CHILD))
            }
        }
        byPid[root]?.let { records[it.pid to it.started] = ProcessRecord(it.pid, it.started, Role.SESSION_ROOT) }
        for (helper in liveHelpers) {
            byPid[helper]?.let { records[it.pid to it.started] = ProcessRecord(it.pid, it.started, Role.HELPER_ROOT) }
        }
    }

    private fun descendants(root: Int, processes: List<ProcessInfo>, byPid: Map<Int, ProcessInfo>): List<ProcessInfo> {
        if (root !in byPid) return emptyList()
        val children = HashMap<Int, MutableList<ProcessInfo>>()
        for (process in processes) children.getOrPut(process.parent) { ArrayList() }.add(process)
        val excluded = Process.myPid()
        val found = ArrayList<ProcessInfo>()
        val seen = HashSet<Int>()
        val queue = ArrayDeque<Int>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val parent = queue.removeFirst()
            for (child in children[parent].orEmpty()) {
                if (child.pid <= 1 || child.pid == excluded || !seen.add(child.pid)) continue
                found.add(child)
                queue.add(child.pid)
            }
        }
        return found
    }

    private fun readProcesses(): List<ProcessInfo> =
        (procRoot.listFiles() ?: throw IOException("Cannot enumerate processes")).mapNotNull { entry ->
            val pid = entry.name.toIntOrNull() ?: return@mapNotNull null
            try { readProcess(pid) } catch (_: IOException) { null }
        }

    private fun readProcess(pid: Int): ProcessInfo? {
        val base = File(procRoot, pid.toString())
        try {
            val fields = readStat(File(base, "stat"))
            val tracer = File(base, "status").useLines { lines ->
                lines.firstOrNull { it.startsWith("TracerPid:") }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            }
            return ProcessInfo(pid, fields[1].toInt(), fields[19].toLong(), fields[0].first(), tracer)
        } catch (error: IOException) {
            if (procRoot.list()?.contains(pid.toString()) == false) return null
            throw error
        }
    }

    private fun readStat(file: File): List<String> {
        val stat = file.readText()
        val close = stat.lastIndexOf(')')
        if (close < 0 || close + 2 >= stat.length) throw IOException("Invalid process stat: $file")
        val fields = stat.substring(close + 2).trim().split(Regex("\\s+"))
        if (fields.size < 20) throw IOException("Incomplete process stat: $file")
        return fields
    }

    private fun stoppedSnapshot(records: List<ProcessRecord>, root: ProcessRecord): Map<TaskIdentity, Long>? {
        // PRoot can leave the leader stopped while native worker threads keep running.
        val tasks = LinkedHashMap<TaskIdentity, Long>()
        var stopped = true
        for (record in records) {
            val current = readProcess(record.pid)
            if (current == null || current.started != record.started) {
                if (record == root) throw IOException("Session root exited or changed")
                continue
            }
            val entries = File(procRoot, "${record.pid}/task").listFiles()
                ?: throw IOException("Cannot enumerate threads: ${record.pid}")
            if (entries.none { it.name.toIntOrNull() != null }) throw IOException("Empty thread inventory: ${record.pid}")
            var liveRoot = false
            for (entry in entries) {
                val tid = entry.name.toIntOrNull() ?: continue
                val fields = readStat(File(entry, "stat"))
                val state = fields[0].first()
                if (state != 'Z' && state != 'X') liveRoot = true
                if (state != 'T' && state != 't' && state != 'Z' && state != 'X') stopped = false
                tasks[TaskIdentity(record, tid, fields[19].toLong())] = fields[11].toLong() + fields[12].toLong()
            }
            if (record == root && !liveRoot) throw IOException("Session root has no live threads")
            if (readProcess(record.pid)?.started != record.started) throw IOException("Process changed during inspection")
        }
        return tasks.takeIf { stopped && it.isNotEmpty() }
    }

    // A stopped process cannot run libblsession's SIGURG handler, so the page-out has to finish
    // before the stop. Android's low-memory killer otherwise finds the frozen game resident in RAM
    // with most of zram still free.
    private fun pageOut(records: MutableMap<Pair<Int, Long>, ProcessRecord>, root: Int, helpers: List<Int>) {
        val control = zramControl?.takeIf { it.isFile } ?: return
        try {
            addSnapshot(records, root, helpers)
            control.writeText("1")
        } catch (_: IOException) {
            return
        }
        val targets = records.values.filter { record ->
            try {
                File(procRoot, "${record.pid}/maps").useLines { lines -> lines.any { it.endsWith("/libblsession.so") } }
            } catch (_: IOException) {
                false
            }
        }
        if (targets.isEmpty()) return
        val acks = targets.associateWith { File(control.parentFile, "${control.name}.ack.${it.pid}") }
        acks.values.forEach { it.delete() }
        val start = nowMillis()
        val before = memoryKb(targets)
        for (record in targets) send(record, SIGURG)
        // A program can replace libblsession's SIGURG handler (PyInstaller's bootloader, the Go runtime);
        // such a process never creates its ack and is not waited for once the start window has passed.
        val waiting = targets.toMutableList()
        while (!closing && !cancelled && nowMillis() - start < PAGE_OUT_TIMEOUT_MS) {
            val started = nowMillis() - start >= PAGE_OUT_START_MS
            waiting.removeAll { val ack = acks.getValue(it); ack.length() > 0 || (started && !ack.exists()) || isGone(it) }
            if (waiting.isEmpty()) break
            sleep(PAGE_OUT_POLL_MS)
        }
        val unacknowledged = targets.count { acks.getValue(it).length() == 0L && !isGone(it) }
        acks.values.forEach { it.delete() }
        val after = memoryKb(targets)
        pagedOut(targets.size, unacknowledged, after.first, after.second, before.second, nowMillis() - start)
    }

    private fun isGone(record: ProcessRecord): Boolean =
        try { readProcess(record.pid)?.started != record.started } catch (_: Exception) { false }

    private fun memoryKb(records: List<ProcessRecord>): Pair<Long, Long> {
        var resident = 0L
        var swap = 0L
        for (record in records) {
            try {
                File(procRoot, "${record.pid}/status").useLines { lines ->
                    for (line in lines) {
                        val kb = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: continue
                        if (line.startsWith("VmRSS:")) resident += kb else if (line.startsWith("VmSwap:")) swap += kb
                    }
                }
            } catch (_: IOException) {
            }
        }
        return resident to swap
    }

    // PRoot traces every guest. A stop sent while it runs becomes a group stop that it restarts
    // thread by thread; once it is stopped, each thread that takes a SIGSTOP stays parked in its
    // signal-delivery stop. So the roots stop first and every guest thread gets its own SIGSTOP.
    private fun stop(records: List<ProcessRecord>) {
        for (record in records.filter { it.role == Role.SESSION_ROOT }) send(record, SIGSTOP)
        for (record in records.filter { it.role == Role.HELPER_ROOT }) send(record, SIGSTOP)
        for (record in records.filter { it.role == Role.SESSION_CHILD || it.role == Role.HELPER_CHILD }) {
            stopThreads(record)
        }
    }

    private fun stopThreads(record: ProcessRecord) {
        val current = try { readProcess(record.pid) } catch (_: Exception) { return }
        if (current == null || current.started != record.started) return
        for (entry in File(procRoot, "${record.pid}/task").listFiles().orEmpty()) {
            val tid = entry.name.toIntOrNull() ?: continue
            val state = try { readStat(File(entry, "stat"))[0].first() } catch (_: IOException) { continue }
            if (state == 'T' || state == 't' || state == 'Z' || state == 'X') continue
            try {
                threadSignal(record.pid, tid, SIGSTOP)
            } catch (_: Throwable) {
            }
        }
    }

    private fun restore(records: List<ProcessRecord>): Boolean {
        val ordered = records.sortedBy {
            when (it.role) {
                Role.SESSION_ROOT -> 0
                Role.HELPER_ROOT -> 1
                Role.SESSION_CHILD -> 2
                Role.HELPER_CHILD -> 3
            }
        }
        for (record in ordered) send(record, SIGCONT)
        val waiting = ordered.toMutableList()
        repeat(RESTORE_CHECKS) { check ->
            sleep(25L)
            waiting.removeAll { isRunningOrGone(it) }
            if (waiting.isEmpty()) return true
            if (check % 8 == 7) for (record in waiting) send(record, SIGCONT)
        }
        return false
    }

    private fun restoreSafely(records: List<ProcessRecord>): Boolean = try {
        restore(records)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    } catch (_: Exception) {
        false
    }

    private fun send(record: ProcessRecord, signal: Int) {
        val current = try { readProcess(record.pid) } catch (_: Exception) { return }
        if (current == null || current.started != record.started) return
        try {
            this.signal(record.pid, signal)
        } catch (_: Throwable) {
        }
    }

    private fun isRunningOrGone(record: ProcessRecord): Boolean {
        val current = try { readProcess(record.pid) } catch (_: Exception) { return false }
        if (current == null || current.started != record.started) return true
        if (current.state == 'T' || current.state == 't') return false
        // A traced worker in 't' is usually PRoot handling a system call; 'T' is a job-control stop.
        return File(procRoot, "${record.pid}/task").listFiles().orEmpty().none { entry ->
            entry.name.toIntOrNull() != null &&
                try { readStat(File(entry, "stat"))[0].first() == 'T' } catch (_: IOException) { false }
        }
    }

    private companion object {
        const val SIGSTOP = 19
        const val SIGCONT = 18
        const val SIGURG = 23
        const val PAGE_OUT_POLL_MS = 100L
        const val PAGE_OUT_START_MS = 2_000L
        const val PAGE_OUT_TIMEOUT_MS = 12_000L
        const val RESTORE_CHECKS = 80
        const val FREEZE_TIMEOUT_MS = 8_000L
        const val FREEZE_MAX_PASSES = 256
    }
}
