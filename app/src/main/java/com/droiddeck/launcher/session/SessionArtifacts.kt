package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.core.LogRedactor
import com.droiddeck.launcher.core.SessionLogCapture
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.io.File
import java.io.Writer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SessionArtifacts {
    private const val TAG = "SessionArtifacts"

    const val COMPLETE_MARKER = ".complete"

    private const val RECOVERY_RUNTIME_CAPTURED_MARKER = ".recovery-runtime-captured"
    private const val RECOVERY_RUNTIME_EXPIRED_MARKER = ".recovery-runtime-expired"

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
            val scrubbed = markScrubbed(scrubTree(record))
            SessionEvents.record("session.artifacts_collected", mapOf("reason" to reason), dir)
            if (scrubbed) File(dir, COMPLETE_MARKER).writeText("collected: $reason at ${now()}\n")
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
        var done = 0
        for (dir in dirs) {
            if (SessionState.running) break
            if (scrubAndMark(dir, stopWhenSessionStarts = true)) done++
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
        var deferred = false
    }

    private sealed class ScrubResult {
        class Done(val scrubbed: Scrubbed) : ScrubResult()
        object Failed : ScrubResult()
        object Deferred : ScrubResult()
    }

    private fun scrubFolder(
        dir: File,
        record: Record,
        shouldStop: () -> Boolean,
        beforeReplace: (File) -> Unit,
        listFiles: (File) -> Array<File>?,
    ): Boolean {
        val files = listFiles(dir)?.filter { f -> f.isFile && !f.name.startsWith(".") && f !in record.files }
        if (files == null) {
            record.failed = true
            return true
        }
        for (f in files) {
            if (shouldStop()) return false
            if (LogRedactor.isBinary(f)) continue
            when (val result = scrubFile(dir, f, shouldStop, beforeReplace)) {
                is ScrubResult.Done -> record.files[f] = result.scrubbed
                ScrubResult.Failed -> record.failed = true
                ScrubResult.Deferred -> {
                    record.failed = true
                    record.deferred = true
                    return false
                }
            }
        }
        return true
    }
    private fun scrubFile(
        dir: File,
        f: File,
        shouldStop: () -> Boolean,
        beforeReplace: (File) -> Unit,
    ): ScrubResult {
        val tmp = File(dir, ".${f.name}.scrub")
        return try {
            val before = Scrubbed(f.length(), f.lastModified())
            val scrubbed = tmp.bufferedWriter().use { w -> LogRedactor.scrubTo(f, w, shouldStop) }
            if (!scrubbed) {
                tmp.delete()
                return ScrubResult.Deferred
            }
            val scrubbedLength = tmp.length()
            when (sameContents(tmp, f, shouldStop)) {
                null -> {
                    tmp.delete()
                    ScrubResult.Deferred
                }
                true -> {
                    tmp.delete()
                    before.takeIf { f.length() == it.length && f.lastModified() == it.modified }
                        ?.let { ScrubResult.Done(it) } ?: ScrubResult.Failed
                }
                false -> {
                    if (shouldStop()) {
                        tmp.delete()
                        return ScrubResult.Deferred
                    }
                    beforeReplace(f)
                    if (f.length() != before.length || f.lastModified() != before.modified) {
                        tmp.delete()
                        return ScrubResult.Failed
                    }
                    if (!tmp.renameTo(f)) {
                        tmp.delete()
                        return ScrubResult.Failed
                    }
                    Scrubbed(scrubbedLength, f.lastModified()).takeIf { f.length() == it.length }
                        ?.let { ScrubResult.Done(it) } ?: ScrubResult.Failed
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not scrub ${f.name}", e)
            tmp.delete()
            ScrubResult.Failed
        }
    }

    internal fun sameContents(a: File, b: File, shouldStop: () -> Boolean = { false }): Boolean? {
        if (shouldStop()) return null
        if (a.length() != b.length()) return false
        fun readChunk(input: java.io.InputStream, buffer: ByteArray): Int? {
            var count = 0
            while (count < buffer.size) {
                if (shouldStop()) return null
                val read = input.read(buffer, count, buffer.size - count)
                if (shouldStop()) return null
                if (read < 0) break
                if (read == 0) continue
                count += read
            }
            return count
        }
        return a.inputStream().buffered().use { left ->
            b.inputStream().buffered().use { right ->
                val leftBuffer = ByteArray(64 * 1024)
                val rightBuffer = ByteArray(64 * 1024)
                while (true) {
                    val leftCount = readChunk(left, leftBuffer) ?: return null
                    val rightCount = readChunk(right, rightBuffer) ?: return null
                    if (leftCount != rightCount) return false
                    if (leftCount == 0) break
                    if (shouldStop()) return null
                    for (i in 0 until leftCount) if (leftBuffer[i] != rightBuffer[i]) return false
                }
                true
            }
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

    internal fun scrubAndMark(
        dir: File,
        stopWhenSessionStarts: Boolean = false,
        beforeReplace: (File) -> Unit = {},
        listFiles: (File) -> Array<File>? = { it.listFiles() },
        sessionStarted: () -> Boolean = { SessionState.running },
    ): Boolean {
        val shouldStop = { stopWhenSessionStarts && sessionStarted() }
        val record = scrubTree(
            Record(dir),
            listFiles = listFiles,
            beforeReplace = beforeReplace,
            shouldStop = shouldStop,
        )
        if (shouldStop()) {
            record.failed = true
            record.deferred = true
        }
        return markScrubbed(record)
    }

    private fun scrubTree(
        record: Record,
        listFiles: (File) -> Array<File>? = { it.listFiles() },
        beforeReplace: (File) -> Unit = {},
        shouldStop: () -> Boolean = { false },
    ): Record {
        for (dir in record.dir.walkTopDown().filter { it.isDirectory }) {
            if (shouldStop() || !scrubFolder(dir, record, shouldStop, beforeReplace, listFiles)) {
                record.failed = true
                record.deferred = true
                break
            }
        }
        if (shouldStop()) {
            record.failed = true
            record.deferred = true
        }
        return record
    }

    private const val CLIENT_FILE = "steam-client.txt"

    fun recordClient(dir: File, arch: String) {
        runCatching { File(dir, CLIENT_FILE).writeText("$arch\n") }
    }

    /** The Steam client a session ran, when it recorded one. */
    internal fun recordedClient(dir: File): String? =
        runCatching { File(dir, CLIENT_FILE).readText().trim() }.getOrNull()?.takeIf { it == "arm64" || it == "x86_64" }

    private const val LOG_MARKS = "steam-log-marks.txt"
    private const val MAX_STEAM_LOG = 8L * 1024 * 1024
    private const val HEAD = 4096L

    private class Mark(val size: Long, val head: String)
    private class CopyResult(val files: List<File>, val failed: Boolean, val deferred: Boolean)

    fun markSteamLogs(dir: File, logs: File) {
        val lines = logs.listFiles { f -> f.isFile }?.mapNotNull { f ->
            val size = f.length()
            head(f, size)?.let { "$size\t$it\t${f.name}" }
        } ?: return
        runCatching { File(dir, LOG_MARKS).writeText(lines.joinToString("") { "$it\n" }) }
    }

    private fun head(f: File, length: Long): String? = runCatching {
        val buf = ByteArray(minOf(length, HEAD).toInt())
        f.inputStream().use { input ->
            var off = 0
            while (off < buf.size) {
                val n = input.read(buf, off, buf.size - off)
                if (n < 0) return@runCatching null
                off += n
            }
        }
        java.security.MessageDigest.getInstance("SHA-1").digest(buf).joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun steamLogMarks(dir: File): List<Mark>? = runCatching {
        File(dir, LOG_MARKS).readLines().mapNotNull { line ->
            val parts = line.split('\t', limit = 3)
            parts.getOrNull(0)?.toLongOrNull()?.let { size -> parts.getOrNull(1)?.let { Mark(size, it) } }
        }
    }.getOrNull()

    private fun startOf(src: File, size: Long, marks: List<Mark>): Long {
        val heads = HashMap<Long, String?>()
        return marks.filter { it.size <= size }.sortedByDescending { it.size }
            .firstOrNull { m -> heads.getOrPut(minOf(m.size, HEAD)) { head(src, m.size) } == m.head }?.size ?: 0L
    }

    private fun copySteamLogs(
        context: Context,
        dir: File,
        record: Record,
        steamArch: String? = null,
        stopWhenSessionStarts: Boolean = false,
    ) {
        val logs = File(steamArch?.let { SessionPrefs.steamRoot(context, it) } ?: SessionPrefs.activeSteamRoot(context), "logs")
        if (!logs.isDirectory) return
        val out = File(dir, "steam").apply { mkdirs() }
        val result = copySessionLogsResult(logs, dir, out) { stopWhenSessionStarts && SessionState.running }
        result.files.forEach { dst -> record.files[dst] = Scrubbed(dst.length(), dst.lastModified()) }
        record.failed = record.failed || result.failed
        record.deferred = record.deferred || result.deferred
        Log.i(TAG, "collected ${out.listFiles()?.size ?: 0} Steam log(s), scrubbed, into $out")
    }

    internal fun copySessionLogs(
        logs: File,
        dir: File,
        out: File,
        openWriter: (File) -> Writer = { it.bufferedWriter() },
    ): List<File> = copySessionLogsResult(logs, dir, out, openWriter) { false }.files

    private fun copySessionLogsResult(
        logs: File,
        dir: File,
        out: File,
        openWriter: (File) -> Writer = { it.bufferedWriter() },
        shouldStop: () -> Boolean,
    ): CopyResult {
        val marks = steamLogMarks(dir)
        val copied = ArrayList<File>()
        var failed = false
        var deferred = false
        val sources = logs.listFiles { f -> f.isFile } ?: return CopyResult(copied, true, false)
        for (src in sources) {
            if (shouldStop()) {
                failed = true
                deferred = true
                break
            }
            val size = src.length()
            val from = marks?.let { startOf(src, size, it) } ?: 0L
            if (marks != null && from == size || size - from >= MAX_STEAM_LOG) continue
            val dst = File(out, src.name)
            val staging = File(out, ".${src.name}.copy")
            try {
                src.inputStream().buffered().use { input ->
                    var left = if (from > 0) from - 1 else 0L
                    while (left > 0) {
                        val skipped = input.skip(left)
                        if (skipped <= 0) break
                        left -= skipped
                    }
                    if (from > 0 && input.read() != '\n'.code) {
                        while (true) {
                            val c = input.read()
                            if (c < 0 || c == '\n'.code) break
                        }
                    }
                    val complete = openWriter(staging).use { w -> scrubSteamLog(input, w, shouldStop) }
                    if (!complete) {
                        staging.delete()
                        failed = true
                        deferred = true
                    }
                }
                if (deferred) break
                Files.move(
                    staging.toPath(),
                    dst.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                copied += dst
            } catch (e: Exception) {
                staging.delete()
                Log.w(TAG, "could not scrub ${src.name}", e)
                failed = true
            }
        }
        return CopyResult(copied, failed, deferred)
    }

    private fun scrubSteamLog(
        input: java.io.InputStream,
        out: java.io.Writer,
        shouldStop: () -> Boolean,
    ): Boolean {
        input.reader().use { reader ->
            val chunk = CharArray(8192)
            val line = StringBuilder(4096)
            var oversized = false
            var pendingContent = false
            var pendingCarriageReturn = false

            fun emit() {
                out.write(if (oversized) LogRedactor.OVERSIZED_LINE else LogRedactor.redact(line.toString()))
                out.write("\n")
                line.setLength(0)
                oversized = false
                pendingContent = false
            }

            while (true) {
                if (shouldStop()) return false
                val count = reader.read(chunk)
                if (count < 0) break
                if (shouldStop()) return false
                for (i in 0 until count) {
                    if (shouldStop()) return false
                    val char = chunk[i]
                    if (pendingCarriageReturn) {
                        emit()
                        pendingCarriageReturn = false
                        if (char == '\n') continue
                    }
                    when (char) {
                        '\r' -> pendingCarriageReturn = true
                        '\n' -> emit()
                        else -> {
                            pendingContent = true
                            if (!oversized) {
                                if (line.length < LogRedactor.MAX_LINE_LENGTH) line.append(char)
                                else {
                                    line.setLength(0)
                                    oversized = true
                                }
                            }
                        }
                    }
                }
            }
            if (pendingCarriageReturn || pendingContent) {
                if (shouldStop()) return false
                emit()
            }
        }
        return true
    }

    @Synchronized
    fun finishAbandoned(context: Context, beforeGuestStart: Boolean = false) {
        val stopWhenSessionStarts = !beforeGuestStart
        val parent = LinuxRuntime.logDir(context)
        val abandoned = parent.listFiles { f ->
            SessionPaths.isSessionFolder(f) && !File(f, COMPLETE_MARKER).exists() && !inFlight(f)
        }?.sortedWith(SessionPaths.chronological) ?: return
        if (abandoned.isEmpty()) return
        val current = SessionPaths.current()
        val latestPreviousSession = SessionPaths.sessionFolders(context).lastOrNull { it != current }
        LogRedactor.learnFromRuntime(LinuxRuntime.rootDir(context))
        for (dir in abandoned) {
            if (stopWhenSessionStarts && SessionState.running) break
            if (dir == current) continue
            if (beforeGuestStart && dir != latestPreviousSession) continue
            val newest = dir == latestPreviousSession
            try {
                val recoveryNote = File(dir, "ended-without-teardown.txt")
                val runtimeCaptured = File(dir, RECOVERY_RUNTIME_CAPTURED_MARKER)
                val runtimeExpired = File(dir, RECOVERY_RUNTIME_EXPIRED_MARKER)
                val recoveryPreviouslyAttempted = recoveryNote.exists()
                val recoveryAttempted = recoveryPreviouslyAttempted &&
                    (runtimeCaptured.exists() || runtimeExpired.exists() || !beforeGuestStart)
                if (!recoveryAttempted) {
                    recoveryNote.writeText(
                        "This session's process ended without running its own teardown - killed by\n" +
                            "Android, a native crash, or the device going down - so the files below were\n" +
                            "gathered when the app next started, at ${now()}.\n" +
                            "The logs written during the session (session.log, app.log, wayland.log,\n" +
                            "audio.log, device.txt) were on disk already and are as they were left.\n" +
                            (if (newest) "" else "Steam's logs are not included: a later session has overwritten them.\n") +
                            "crash.log holds Android's crash buffer as of the next app start - if this\n" +
                            "session died of a crash, the entry is in there unless the device rebooted.\n"
                    )
                }
                if (recoveryPreviouslyAttempted && !runtimeCaptured.exists() &&
                    (runtimeExpired.exists() || !beforeGuestStart) &&
                    !recoveryNote.readText().contains("runtime capture did not finish")) {
                    recoveryNote.appendText(
                        "Steam's logs and VAC evidence are not included: runtime capture did not finish " +
                            "before its evidence expired.\n"
                    )
                }
                if (recoveryPreviouslyAttempted && !runtimeCaptured.exists() && !beforeGuestStart) {
                    runtimeExpired.writeText("runtime evidence no longer belongs to this session, at ${now()}\n")
                }
                val record = Record(dir)
                if (newest && !recoveryAttempted) {
                    copySteamLogs(
                        context,
                        dir,
                        record,
                        steamArch = recordedClient(dir),
                        stopWhenSessionStarts = stopWhenSessionStarts,
                    )
                    if (!record.deferred && (!stopWhenSessionStarts || !SessionState.running)) {
                        if (!record.failed && VacDiagnostics.collect(context, dir)) {
                            try {
                                runtimeCaptured.writeText("collected at ${now()}\n")
                            } catch (e: Exception) {
                                record.failed = true
                                if (beforeGuestStart) {
                                    runtimeExpired.writeText("runtime capture marker failed, at ${now()}\n")
                                }
                                Log.w(TAG, "could not mark runtime capture for $dir", e)
                            }
                        } else {
                            record.failed = true
                            if (beforeGuestStart) {
                                runtimeExpired.writeText("runtime capture failed, at ${now()}\n")
                            }
                        }
                    }
                }
                if (record.deferred || stopWhenSessionStarts && SessionState.running) {
                    record.failed = true
                    record.deferred = true
                    markScrubbed(record)
                    break
                }
                val crashLog = File(dir, "crash.log")
                if (!crashLog.exists()) {
                    SessionLogCapture.dumpCrashBuffer(crashLog)
                }
                if (stopWhenSessionStarts && SessionState.running) {
                    record.failed = true
                    record.deferred = true
                    markScrubbed(record)
                    break
                }
                SessionEvents.record("session.artifacts_recovered", mapOf("newest" to newest), dir)
                if (beforeGuestStart) return
                val scrubbed = scrubTree(record) { stopWhenSessionStarts && SessionState.running }
                val marked = markScrubbed(scrubbed)
                if (scrubbed.deferred) break
                if (!marked) continue
                File(dir, COMPLETE_MARKER).writeText("collected: late, at next app start, ${now()}\n")
                Log.i(TAG, "finished the abandoned session folder $dir")
            } catch (e: Exception) {
                Log.w(TAG, "could not finish $dir", e)
            }
        }
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
