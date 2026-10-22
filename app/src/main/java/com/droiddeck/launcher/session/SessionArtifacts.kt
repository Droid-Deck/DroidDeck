package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.core.LogRedactor
import com.droiddeck.launcher.core.SessionLogCapture
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SessionArtifacts {
    private const val TAG = "SessionArtifacts"

    const val COMPLETE_MARKER = ".complete"

    private val SCRUBBED_MARKER = ".scrubbed-r${LogRedactor.RULES_VERSION}"
    private val OLD_SCRUBBED_MARKER = Regex("""\.scrubbed-(r?\d+)""")

    const val SCRUBBED_TREE_MARKER = ".scrubbed-tree"

    private val collectingLock = java.util.concurrent.locks.ReentrantLock()
    private val collectingDone = collectingLock.newCondition()
    private val collecting = HashSet<File>()

    /** Recovery leaves [dir] alone from the stop until [collect] has finished it. */
    fun collectionStarted(dir: File) {
        collectingLock.lock()
        try { collecting.add(dir) } finally { collectingLock.unlock() }
    }

    private fun collectionDone(dir: File) {
        collectingLock.lock()
        try { collecting.remove(dir); collectingDone.signalAll() } finally { collectingLock.unlock() }
    }

    private fun inFlight(dir: File): Boolean {
        collectingLock.lock()
        try { return dir in collecting } finally { collectingLock.unlock() }
    }

    fun awaitCollections(timeoutMs: Long = 30_000) {
        collectingLock.lock()
        try {
            var left = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (collecting.isNotEmpty() && left > 0) left = collectingDone.awaitNanos(left)
        } finally {
            collectingLock.unlock()
        }
    }

    fun collect(context: Context, dir: File, reason: String, steamArch: String? = null) {
        try {
            collectNow(context, dir, reason, steamArch)
        } finally {
            collectionDone(dir)
        }
    }

    private fun collectNow(context: Context, dir: File, reason: String, steamArch: String?) {
        try {
            val wayland = File(dir, "wayland.log")
            if (!wayland.exists()) {
                WaylandCompositor.currentLogFile()?.takeIf { it.isFile }?.let { src ->
                    src.copyTo(wayland, overwrite = true)
                }
            }
            LogRedactor.learnFromRuntime(LinuxRuntime.rootDir(context))
            val record = Record(dir)
            copySteamLogs(context, dir, record, steamArch)
            VacDiagnostics.collect(context, dir)
            SessionLogCapture.dumpCrashBuffer(File(dir, "crash.log"))
            markScrubbed(scrubTree(record))
            SessionEvents.record("session.artifacts_collected", mapOf("reason" to reason), dir)
            File(dir, COMPLETE_MARKER).writeText("collected: $reason at ${now()}\n")
        } catch (e: Exception) {
            Log.w(TAG, "collecting session artifacts", e)
        }
    }

    @Synchronized
    fun scrubOlder(context: Context) {
        val current = SessionPaths.current()
        val dirs = LinuxRuntime.logDir(context).listFiles { f ->
            SessionPaths.isSessionFolder(f) && f != current && !File(f, SCRUBBED_MARKER).exists()
        }?.toList() ?: return
        if (dirs.isEmpty()) return
        LogRedactor.learnFromRuntime(LinuxRuntime.rootDir(context))
        val workers = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(workers) { r ->
            Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "scrub-logs")
        }
        val done = try {
            pool.invokeAll(dirs.map { dir -> java.util.concurrent.Callable { scrubAndMark(dir) } })
                .count { f -> try { f.get() } catch (e: Exception) { false } }
        } finally {
            pool.shutdown()
        }
        val failed = dirs.size - done
        Log.i(TAG, "logs: scrubbed $done folders under r${LogRedactor.RULES_VERSION}" +
            if (failed > 0) ", $failed not fully (again at the next start)" else "")
    }

    fun unmarkMoved(dirs: List<File>) {
        dirs.filter { SessionPaths.isSessionFolder(it) }.forEach { dir ->
            dir.listFiles { f -> OLD_SCRUBBED_MARKER.matches(f.name) || f.name == SCRUBBED_TREE_MARKER }?.forEach { it.delete() }
        }
    }

    private class Scrubbed(val length: Long, val modified: Long)

    private class Record(val dir: File) {
        val files = HashMap<File, Scrubbed>()
        var failed = false
    }

    private fun scrubFolder(dir: File, record: Record) {
        dir.listFiles { f -> f.isFile && !f.name.startsWith(".") && f !in record.files }?.forEach { f ->
            if (LogRedactor.isBinary(f)) return@forEach
            // Too large to rewrite in memory: the folder must not be recorded as scrubbed.
            if (!LogRedactor.isText(f)) { record.failed = true; return@forEach }
            val done = scrubFile(dir, f)
            if (done == null) record.failed = true else record.files[f] = done
        }
    }

    private fun scrubFile(dir: File, f: File): Scrubbed? {
        val tmp = File(dir, ".${f.name}.scrub")
        return try {
            val before = Scrubbed(f.length(), f.lastModified())
            tmp.bufferedWriter().use { w -> LogRedactor.scrubTo(f, w) }
            val scrubbed = tmp.readBytes()
            if (scrubbed.contentEquals(f.readBytes())) {
                tmp.delete()
                before.takeIf { f.length() == it.length && f.lastModified() == it.modified }
            } else {
                if (!tmp.renameTo(f)) { f.writeBytes(scrubbed); tmp.delete() }
                Scrubbed(scrubbed.size.toLong(), f.lastModified()).takeIf { f.length() == it.length }
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not scrub ${f.name}", e)
            tmp.delete()
            null
        }
    }

    private fun markScrubbed(record: Record): Boolean {
        val dir = record.dir
        val tree = File(dir, SCRUBBED_TREE_MARKER)
        dir.listFiles { f -> OLD_SCRUBBED_MARKER.matches(f.name) }?.forEach { it.delete() }
        if (record.failed) {
            tree.delete()
            Log.w(TAG, "$dir is not fully scrubbed; it is scrubbed again when shared")
            return false
        }
        return try {
            File(dir, SCRUBBED_MARKER).writeText("scrubbed ${now()}\n")
            val lines = StringBuilder("rules ${LogRedactor.RULES_VERSION}\n")
            record.files.forEach { (f, s) ->
                lines.append(s.length).append('\t').append(s.modified).append('\t')
                    .append(f.relativeTo(dir).invariantSeparatorsPath).append('\n')
            }
            tree.writeText(lines.toString())
            true
        } catch (e: Exception) {
            tree.delete()
            File(dir, SCRUBBED_MARKER).delete()
            false
        }
    }

    fun scrubbedFiles(dir: File): Set<String> {
        val lines = try { File(dir, SCRUBBED_TREE_MARKER).takeIf { it.isFile }?.readLines() } catch (e: Exception) { null }
            ?: return emptySet()
        if (lines.firstOrNull() != "rules ${LogRedactor.RULES_VERSION}") return emptySet()
        return lines.drop(1).mapNotNull { line ->
            val parts = line.split('\t', limit = 3)
            if (parts.size != 3) return@mapNotNull null
            val f = File(dir, parts[2])
            parts[2].takeIf { f.isFile && f.length().toString() == parts[0] && f.lastModified().toString() == parts[1] }
        }.toSet()
    }

    internal fun scrubAndMark(dir: File): Boolean = markScrubbed(scrubTree(Record(dir)))

    private fun scrubTree(record: Record): Record {
        record.dir.walkTopDown().filter { it.isDirectory }.forEach { scrubFolder(it, record) }
        return record
    }

    private const val CLIENT_FILE = "steam-client.txt"

    fun recordClient(dir: File, arch: String) {
        runCatching { File(dir, CLIENT_FILE).writeText("$arch\n") }
    }

    /** The Steam client a session ran, when it recorded one. */
    internal fun recordedClient(dir: File): String? =
        runCatching { File(dir, CLIENT_FILE).readText().trim() }.getOrNull()?.takeIf { it == "arm64" || it == "x86_64" }

    private fun copySteamLogs(context: Context, dir: File, record: Record, steamArch: String? = null) {
        val logs = File(steamArch?.let { SessionPrefs.steamRoot(context, it) } ?: SessionPrefs.activeSteamRoot(context), "logs")
        if (!logs.isDirectory) return
        val out = File(dir, "steam").apply { mkdirs() }
        logs.listFiles { f -> f.isFile && f.length() < 8L * 1024 * 1024 }?.forEach { src ->
            try {
                val dst = File(out, src.name)
                dst.bufferedWriter().use { w ->
                    src.forEachLine { line -> w.write(LogRedactor.redact(line)); w.newLine() }
                }
                record.files[dst] = Scrubbed(dst.length(), dst.lastModified())
            } catch (e: Exception) {
                Log.w(TAG, "could not scrub ${src.name}", e)
            }
        }
        Log.i(TAG, "collected ${out.listFiles()?.size ?: 0} Steam log(s), scrubbed, into $out")
    }

    /**
     * Finishes the folders of sessions whose process died without its teardown. Returns true when
     * the newest of them ended because Android killed the app for memory, so the app can say so
     * (a game "crashing" on a phone with little free RAM is usually this, not a driver fault).
     */
    @Synchronized
    fun finishAbandoned(context: Context): Boolean {
        val parent = LinuxRuntime.logDir(context)
        val abandoned = parent.listFiles { f ->
            SessionPaths.isSessionFolder(f) && !File(f, COMPLETE_MARKER).exists() && !inFlight(f)
        }?.sortedWith(SessionPaths.chronological) ?: return false
        if (abandoned.isEmpty()) return false
        var killedForMemory = false
        val current = SessionPaths.current()
        LogRedactor.learnFromRuntime(LinuxRuntime.rootDir(context))
        abandoned.forEachIndexed { i, dir ->
            if (dir == current) return@forEachIndexed
            val newest = i == abandoned.lastIndex
            try {
                File(dir, "ended-without-teardown.txt").writeText(
                    "This session's process ended without running its own teardown - killed by\n" +
                        "Android, a native crash, or the device going down - so the files below were\n" +
                        "gathered when the app next started, at ${now()}.\n" +
                        "The logs written during the session (session.log, app.log, wayland.log,\n" +
                        "audio.log, device.txt) were on disk already and are as they were left.\n" +
                        (if (newest) "" else "Steam's logs are not included: a later session has overwritten them.\n") +
                        "crash.log holds Android's crash buffer as of the next app start - if this\n" +
                        "session died of a crash, the entry is in there unless the device rebooted.\n" +
                        (if (newest) exitReasons(context) else "")
                )
                if (newest && lastExitForMemory(context)) killedForMemory = true
                val record = Record(dir)
                if (newest) {
                    copySteamLogs(context, dir, record, recordedClient(dir))
                    VacDiagnostics.collect(context, dir)
                }
                SessionLogCapture.dumpCrashBuffer(File(dir, "crash.log"))
                SessionEvents.record("session.artifacts_recovered", mapOf("newest" to newest), dir)
                markScrubbed(scrubTree(record))
                File(dir, COMPLETE_MARKER).writeText("collected: late, at next app start, ${now()}\n")
                Log.i(TAG, "finished the abandoned session folder $dir")
            } catch (e: Exception) {
                Log.w(TAG, "could not finish $dir", e)
            }
        }
        // Whoever finishes the folder first (the agent provider starts before the activity) leaves
        // the notice for the launcher to show.
        if (killedForMemory) SessionPrefs.setMemoryKillNotice(context, true)
        return killedForMemory
    }

    /** Android's newest record for this app's main process says it was killed for memory. */
    private fun lastExitForMemory(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 30) return false
        return runCatching {
            val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return false
            am.getHistoricalProcessExitReasons(context.packageName, 0, 3)
                .firstOrNull { it.processName == context.packageName }
                ?.reason == android.app.ApplicationExitInfo.REASON_LOW_MEMORY
        }.getOrDefault(false)
    }

    private fun exitReasons(context: Context): String {
        if (android.os.Build.VERSION.SDK_INT < 30) return ""
        return runCatching {
            val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return ""
            val reasons = am.getHistoricalProcessExitReasons(context.packageName, 0, 3)
            if (reasons.isEmpty()) return ""
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            buildString {
                append("\nAndroid's record of how this app's recent processes ended (newest first):\n")
                reasons.forEach { r ->
                    append("  ${fmt.format(java.util.Date(r.timestamp))} ${r.processName} pid ${r.pid}: reason ${reasonName(r.reason)}, " +
                        "status ${r.status}, importance ${r.importance}, pss ${r.pss / 1024} MB, rss ${r.rss / 1024} MB" +
                        (r.description?.let { " - $it" } ?: "") + "\n")
                }
            }
        }.getOrDefault("")
    }

    private fun reasonName(reason: Int): String = when (reason) {
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY (killed for memory)"
        android.app.ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        android.app.ApplicationExitInfo.REASON_CRASH -> "CRASH"
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        android.app.ApplicationExitInfo.REASON_ANR -> "ANR"
        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        android.app.ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> reason.toString()
    }

    @Synchronized
    fun prune(context: Context) {
        val current = SessionPaths.current()
        val finished = SessionPaths.sessionFolders(context).filter { it != current && File(it, COMPLETE_MARKER).exists() }
        val old = finished.dropLast(SessionPaths.KEEP_SESSIONS)
        old.forEach { com.droiddeck.launcher.core.FileUtils.delete(it) }
        if (old.isNotEmpty()) Log.i(TAG, "deleted ${old.size} session folder(s) past the newest ${SessionPaths.KEEP_SESSIONS}")
    }

    @Synchronized
    fun clearAll(context: Context): Int {
        val current = SessionPaths.current()
        val gone = SessionPaths.sessionFolders(context).filter { it != current }
        gone.forEach { com.droiddeck.launcher.core.FileUtils.delete(it) }
        com.droiddeck.launcher.core.FileUtils.delete(File(LinuxRuntime.logDir(context), SessionPaths.TOOLS_DIR))
        Log.i(TAG, "cleared ${gone.size} session folder(s)")
        return gone.size
    }

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
