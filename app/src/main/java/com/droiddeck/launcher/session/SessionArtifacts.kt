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

    fun collect(context: Context, dir: File, reason: String) {
        try {
            val wayland = File(dir, "wayland.log")
            if (!wayland.exists()) {
                WaylandCompositor.currentLogFile()?.takeIf { it.isFile }?.let { src ->
                    src.copyTo(wayland, overwrite = true)
                }
            }
            LogRedactor.learnFromRuntime(LinuxRuntime.rootDir(context))
            val record = Record(dir)
            copySteamLogs(context, dir, record)
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

    private fun scrubFolder(dir: File, record: Record, stopWhenSessionStarts: Boolean): Boolean {
        val files = dir.listFiles { f -> f.isFile && !f.name.startsWith(".") && f !in record.files } ?: return true
        for (f in files) {
            if (stopWhenSessionStarts && SessionState.running) return false
            if (!LogRedactor.isText(f)) continue
            val done = scrubFile(dir, f)
            if (done == null) record.failed = true else record.files[f] = done
        }
        return true
    }

    private fun scrubFile(dir: File, f: File): Scrubbed? {
        val tmp = File(dir, ".${f.name}.scrub")
        return try {
            val before = Scrubbed(f.length(), f.lastModified())
            tmp.bufferedWriter().use { w -> LogRedactor.scrubTo(f, w) }
            val scrubbedLength = tmp.length()
            if (sameContents(tmp, f)) {
                tmp.delete()
                before.takeIf { f.length() == it.length && f.lastModified() == it.modified }
            } else {
                if (!tmp.renameTo(f)) {
                    tmp.inputStream().use { input -> f.outputStream().use { output -> input.copyTo(output, 64 * 1024) } }
                    tmp.delete()
                }
                Scrubbed(scrubbedLength, f.lastModified()).takeIf { f.length() == it.length }
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not scrub ${f.name}", e)
            tmp.delete()
            null
        }
    }

    private fun sameContents(a: File, b: File): Boolean {
        if (a.length() != b.length()) return false
        fun readChunk(input: java.io.InputStream, buffer: ByteArray): Int {
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
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
                    val leftCount = readChunk(left, leftBuffer)
                    val rightCount = readChunk(right, rightBuffer)
                    if (leftCount != rightCount) return false
                    if (leftCount == 0) break
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

    internal fun scrubAndMark(dir: File, stopWhenSessionStarts: Boolean = false): Boolean {
        val record = scrubTree(Record(dir), stopWhenSessionStarts)
        if (stopWhenSessionStarts && SessionState.running) {
            record.failed = true
            record.deferred = true
        }
        return markScrubbed(record)
    }

    private fun scrubTree(record: Record, stopWhenSessionStarts: Boolean = false): Record {
        for (dir in record.dir.walkTopDown().filter { it.isDirectory }) {
            if (stopWhenSessionStarts && SessionState.running || !scrubFolder(dir, record, stopWhenSessionStarts)) {
                record.failed = true
                record.deferred = true
                break
            }
        }
        if (stopWhenSessionStarts && SessionState.running) {
            record.failed = true
            record.deferred = true
        }
        return record
    }

    private fun copySteamLogs(context: Context, dir: File, record: Record, stopWhenSessionStarts: Boolean = false) {
        val logs = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/logs")
        if (!logs.isDirectory) return
        val out = File(dir, "steam").apply { mkdirs() }
        val sources = logs.listFiles { f -> f.isFile && f.length() < 8L * 1024 * 1024 } ?: return
        for (src in sources) {
            if (stopWhenSessionStarts && SessionState.running) {
                record.failed = true
                record.deferred = true
                break
            }
            try {
                val dst = File(out, src.name)
                dst.bufferedWriter().use { w -> LogRedactor.scrubTo(src, w) }
                record.files[dst] = Scrubbed(dst.length(), dst.lastModified())
            } catch (e: Exception) {
                Log.w(TAG, "could not scrub ${src.name}", e)
            }
        }
        Log.i(TAG, "collected ${out.listFiles()?.size ?: 0} Steam log(s), scrubbed, into $out")
    }

    @Synchronized
    fun finishAbandoned(context: Context) {
        val parent = LinuxRuntime.logDir(context)
        val abandoned = parent.listFiles { f ->
            SessionPaths.isSessionFolder(f) && !File(f, COMPLETE_MARKER).exists()
        }?.sortedWith(SessionPaths.chronological) ?: return
        if (abandoned.isEmpty()) return
        val current = SessionPaths.current()
        val latestSession = SessionPaths.sessionFolders(context).lastOrNull()
        LogRedactor.learnFromRuntime(LinuxRuntime.rootDir(context))
        for (dir in abandoned) {
            if (SessionState.running) break
            if (dir == current) continue
            val newest = dir == latestSession
            try {
                val recoveryNote = File(dir, "ended-without-teardown.txt")
                val recoveryAttempted = recoveryNote.exists()
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
                val record = Record(dir)
                if (newest && !recoveryAttempted) copySteamLogs(context, dir, record, stopWhenSessionStarts = true)
                if (record.deferred || SessionState.running) {
                    record.failed = true
                    record.deferred = true
                    markScrubbed(record)
                    break
                }
                SessionLogCapture.dumpCrashBuffer(File(dir, "crash.log"))
                if (SessionState.running) {
                    record.failed = true
                    record.deferred = true
                    markScrubbed(record)
                    break
                }
                SessionEvents.record("session.artifacts_recovered", mapOf("newest" to newest), dir)
                val scrubbed = scrubTree(record, stopWhenSessionStarts = true)
                markScrubbed(scrubbed)
                if (scrubbed.deferred) break
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
