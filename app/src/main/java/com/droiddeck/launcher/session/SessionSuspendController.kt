package com.droiddeck.launcher.session

import android.os.Process
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal class SessionSuspendController(
    private val sessionRoot: () -> Int,
    private val helperRoots: () -> List<Int>,
    private val suspendAudio: () -> Unit,
    private val resumeAudio: () -> Unit,
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
    )

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var closing = false
    private var frozen = emptyList<ProcessRecord>()

    fun freeze(completion: (Boolean) -> Unit) {
        submit {
            if (closing) {
                completion(false)
                return@submit
            }
            if (frozen.isNotEmpty()) {
                val fullyFrozen = frozen.any { it.role == Role.SESSION_ROOT } && frozen.all(::isStoppedOrGone)
                if (!fullyFrozen && restore(frozen)) frozen = emptyList()
                completion(fullyFrozen)
                return@submit
            }
            val records = LinkedHashMap<Pair<Int, Long>, ProcessRecord>()
            val root = sessionRoot()
            if (root <= 1) {
                completion(false)
                return@submit
            }
            runAudio(suspendAudio)
            val helpers = helperRoots().filter { it > 1 && it != root }.distinct()
            var stablePasses = 0
            var complete = false
            repeat(10) { pass ->
                if (closing) {
                    restore(records.values.toList())
                    runAudio(resumeAudio)
                    completion(false)
                    return@submit
                }
                val includeRoots = pass >= 2
                addSnapshot(records, root, helpers, includeRoots)
                stop(records.values.toList(), includeRoots)
                Thread.sleep(if (pass == 0) 70L else 35L)
                val rootCaptured = records.values.any { it.role == Role.SESSION_ROOT }
                if (rootCaptured && records.isNotEmpty() && records.values.all(::isStoppedOrGone)) {
                    stablePasses++
                    if (stablePasses >= 2) complete = true
                } else {
                    stablePasses = 0
                }
            }
            if (complete) {
                frozen = records.values.toList()
                completion(true)
            } else {
                val restored = restore(records.values.toList())
                runAudio(resumeAudio)
                if (!restored) frozen = records.values.toList()
                completion(false)
            }
        }
    }

    fun resume(completion: (Boolean) -> Unit) {
        submit {
            val records = frozen
            runAudio(resumeAudio)
            val resumed = restore(records)
            if (resumed) frozen = emptyList()
            completion(resumed)
        }
    }

    fun closeAndResume(completion: () -> Unit) {
        closing = true
        submit {
            restore(frozen)
            frozen = emptyList()
            completion()
            executor.shutdown()
        }
    }

    private fun submit(task: () -> Unit) {
        try {
            executor.execute {
                try {
                    task()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        } catch (_: RuntimeException) {
            task()
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
        includeRoots: Boolean,
    ) {
        val processes = readProcesses()
        val byPid = processes.associateBy { it.pid }
        for (process in descendants(root, processes, byPid)) {
            records[process.pid to process.started] = ProcessRecord(process.pid, process.started, Role.SESSION_CHILD)
        }
        for (helper in helpers) {
            for (process in descendants(helper, processes, byPid)) {
                records[process.pid to process.started] = ProcessRecord(process.pid, process.started, Role.HELPER_CHILD)
            }
        }
        if (includeRoots) {
            byPid[root]?.let { records[it.pid to it.started] = ProcessRecord(it.pid, it.started, Role.SESSION_ROOT) }
            for (helper in helpers) {
                byPid[helper]?.let { records[it.pid to it.started] = ProcessRecord(it.pid, it.started, Role.HELPER_ROOT) }
            }
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
        File("/proc").listFiles().orEmpty().mapNotNull { entry ->
            val pid = entry.name.toIntOrNull() ?: return@mapNotNull null
            readProcess(pid)
        }

    private fun readProcess(pid: Int): ProcessInfo? {
        val stat = try {
            File("/proc/$pid/stat").readText()
        } catch (_: Exception) {
            return null
        }
        val close = stat.lastIndexOf(')')
        if (close < 0 || close + 2 >= stat.length) return null
        val fields = stat.substring(close + 2).trim().split(Regex("\\s+"))
        if (fields.size < 20) return null
        return try {
            ProcessInfo(pid, fields[1].toInt(), fields[19].toLong(), fields[0].first())
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun stop(records: List<ProcessRecord>, includeRoots: Boolean) {
        val descendants = records.filter { it.role == Role.SESSION_CHILD || it.role == Role.HELPER_CHILD }
        val session = records.filter { it.role == Role.SESSION_ROOT }
        val helpers = records.filter { it.role == Role.HELPER_ROOT }
        for (record in descendants) send(record, SIGSTOP)
        if (includeRoots) {
            for (record in session) send(record, SIGSTOP)
            for (record in helpers) send(record, SIGSTOP)
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
        repeat(8) {
            Thread.sleep(25L)
            if (ordered.all(::isRunningOrGone)) return true
        }
        return ordered.all(::isRunningOrGone)
    }

    private fun send(record: ProcessRecord, signal: Int) {
        val current = readProcess(record.pid) ?: return
        if (current.started != record.started) return
        try {
            Process.sendSignal(record.pid, signal)
        } catch (_: Throwable) {
        }
    }

    private fun isStoppedOrGone(record: ProcessRecord): Boolean {
        val current = readProcess(record.pid) ?: return true
        if (current.started != record.started) return true
        return current.state == 'T' || current.state == 't' || current.state == 'Z' || current.state == 'X'
    }

    private fun isRunningOrGone(record: ProcessRecord): Boolean {
        val current = readProcess(record.pid) ?: return true
        if (current.started != record.started) return true
        return current.state != 'T' && current.state != 't'
    }

    private companion object {
        const val SIGSTOP = 19
        const val SIGCONT = 18
    }
}
