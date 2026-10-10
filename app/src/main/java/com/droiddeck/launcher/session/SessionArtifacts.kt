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

/**
 * Finishes a session's folder: the compositor's log, the Steam client's logs scrubbed line by
 * line, Android's crash buffer, and a marker that says the folder is complete.
 *
 * Three callers, because a session ends three ways. [collect] runs at an ordinary stop. The
 * app's uncaught-exception handler ([CrashHandler]) runs it as the process dies, so a crash in
 * our own code leaves a full folder and not a half one. And [finishAbandoned] runs at the next
 * app start for a folder that has no marker: the process was killed outright (Android's phantom
 * process killer, a native crash in the compositor, the battery) and nothing of ours got to run.
 * Everything written *during* the session - session.log, app.log, wayland.log, audio.log, the
 * device report - is already on disk at that point; only the pieces gathered at the end were
 * missing, and the crash buffer keeps its entries after the process is gone, which is the whole
 * reason it is worth coming back for.
 */
object SessionArtifacts {
    private const val TAG = "SessionArtifacts"

    /** Written last; a folder without it did not get its ending. */
    const val COMPLETE_MARKER = ".complete"

    /**
     * Every file in the folder has been through the redactor under its current rules. Named after
     * [LogRedactor.RULES_VERSION], so a folder scrubbed under older rules is scrubbed again.
     */
    private val SCRUBBED_MARKER = ".scrubbed-r${LogRedactor.RULES_VERSION}"
    private val OLD_SCRUBBED_MARKER = Regex("""\.scrubbed-(r?\d+)""")

    /**
     * Written by a session's own ending, and only when every file in the folder, subfolders
     * included (whatever the session script copied into steam/ and droiddeck-esync/ as well as what
     * the app wrote), went through the redactor without a failure. It lists each of those files
     * with its size and modification time, under the redactor's rules version ([scrubbedFiles]).
     * A share no longer relies on it: every text file goes through the share pass on the way into
     * the zip regardless.
     */
    const val SCRUBBED_TREE_MARKER = ".scrubbed-tree"

    /** Everything the end of a session gathers, into [dir]. Safe to call for a dead session. */
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
            // A session the system killed leaves its trace here and nowhere else.
            SessionLogCapture.dumpCrashBuffer(File(dir, "crash.log"))
            markScrubbed(scrubTree(record))
            SessionEvents.record("session.artifacts_collected", mapOf("reason" to reason), dir)
            File(dir, COMPLETE_MARKER).writeText("collected: $reason at ${now()}\n")
        } catch (e: Exception) {
            Log.w(TAG, "collecting session artifacts", e)
        }
    }

    /**
     * Every session folder not yet scrubbed under the redactor's current rules - written before
     * every file was scrubbed, before the device's own addresses or the Steam account were, or
     * moved in from Download/ ([LogMigration] strips their markers first, [unmarkMoved]): the
     * whole folder, steam/ and every other subfolder included, through the redactor, and the
     * current marker written in place of the old ones. Runs at app start with [finishAbandoned].
     *
     * A folder holds some 30 MB of Steam logs, about half a minute's work, so folders go through
     * a few at a time; one the process does not get to keeps its old marker and is taken up at
     * the next start.
     */
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

    /**
     * Folders [LogMigration] just moved in lose every scrub marker an earlier build left, so
     * [scrubOlder], which runs right after it, puts them through the current rules whatever those
     * markers claimed.
     */
    fun unmarkMoved(dirs: List<File>) {
        dirs.filter { SessionPaths.isSessionFolder(it) }.forEach { dir ->
            dir.listFiles { f -> OLD_SCRUBBED_MARKER.matches(f.name) || f.name == SCRUBBED_TREE_MARKER }?.forEach { it.delete() }
        }
    }

    /**
     * The session's own files (session.log, app.log, desktop.log, the Steam desktop client's
     * steam-desktop.log, ...) were written as they happened, unscrubbed; this pass puts every one
     * through the redactor before the folder can be shared. A file is rewritten only if a line
     * changed. steam/ was scrubbed on the way in.
     */
    /** A file's size and modification time as they stood right after it was scrubbed. */
    private class Scrubbed(val length: Long, val modified: Long)

    /** Files of a folder as they stood right after scrubbing, filled by [scrubTree] and [copySteamLogs]. */
    private class Record(val dir: File) {
        val files = HashMap<File, Scrubbed>()
        var failed = false
    }

    private fun scrubFolder(dir: File, record: Record) {
        dir.listFiles { f -> f.isFile && !f.name.startsWith(".") && f !in record.files }?.forEach { f ->
            if (!LogRedactor.isText(f)) return@forEach
            val done = scrubFile(dir, f)
            if (done == null) record.failed = true else record.files[f] = done
        }
    }

    /**
     * Puts [f] through the redactor in place. Returns its size and modification time as it then
     * stands, or null if it could not, and [f] may still hold the original. A writer still
     * appending to [f] (the app's own log capture) changes its size afterwards, and a share then
     * scrubs it again.
     */
    private fun scrubFile(dir: File, f: File): Scrubbed? {
        val tmp = File(dir, ".${f.name}.scrub")
        return try {
            val before = Scrubbed(f.length(), f.lastModified())
            tmp.bufferedWriter().use { w -> LogRedactor.scrubTo(f, w) }
            val scrubbed = tmp.readBytes()
            if (scrubbed.contentEquals(f.readBytes())) {
                tmp.delete()
                // Unchanged by the redactor, and by anything else while it read.
                before.takeIf { f.length() == it.length && f.lastModified() == it.modified }
            } else {
                // Shared storage can refuse a rename; then the scrubbed bytes are written over the
                // original instead, so it never stays behind unscrubbed.
                if (!tmp.renameTo(f)) { f.writeBytes(scrubbed); tmp.delete() }
                Scrubbed(scrubbed.size.toLong(), f.lastModified()).takeIf { f.length() == it.length }
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not scrub ${f.name}", e)
            tmp.delete()
            null
        }
    }

    /**
     * Records [record]'s folder as scrubbed - every file went through without a failure - or
     * leaves it unmarked, so the next share and the pass over older folders scrub it again.
     */
    private fun markScrubbed(record: Record): Boolean {
        val dir = record.dir
        val tree = File(dir, SCRUBBED_TREE_MARKER)
        // A marker from older rules vouches for nothing now, whether or not this pass succeeded.
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

    /**
     * The files of [dir] that its own ending scrubbed and that are unchanged since (same size and
     * modification time), by path relative to [dir]. Empty when the folder has no such record, or
     * one made under other redactor rules.
     */
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

    /** The end-of-session scrub of [dir] on its own (steam/ already in place); also the pass over older and moved folders. */
    internal fun scrubAndMark(dir: File): Boolean = markScrubbed(scrubTree(Record(dir)))

    /**
     * [record]'s folder and every folder under it (steam/, droiddeck-esync/): the session script
     * copied files there verbatim, and a Steam log the app did not copy over again (gone from the
     * runtime, or past its size limit) stayed as the script left it. Files [copySteamLogs] already
     * redacted on the way in are in [record] and left alone.
     */
    private fun scrubTree(record: Record): Record {
        record.dir.walkTopDown().filter { it.isDirectory }.forEach { scrubFolder(it, record) }
        return record
    }

    /** Steam's logs: redacted into steam/, never copied verbatim. What it wrote goes in [record]. */
    private fun copySteamLogs(context: Context, dir: File, record: Record) {
        val logs = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/logs")
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
     * Every session folder without a marker gets its ending now. Only the newest of them gets the
     * Steam logs - the runtime holds one set, and it belongs to the last session that ran; an
     * older folder would be handed logs that are not its own. Runs on a worker thread at app
     * start; nothing here touches the session that is about to begin.
     */
    @Synchronized
    fun finishAbandoned(context: Context) {
        val parent = LinuxRuntime.logDir(context)
        val abandoned = parent.listFiles { f ->
            SessionPaths.isSessionFolder(f) && !File(f, COMPLETE_MARKER).exists()
        }?.sortedWith(SessionPaths.chronological) ?: return
        if (abandoned.isEmpty()) return
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
                        "session died of a crash, the entry is in there unless the device rebooted.\n"
                )
                val record = Record(dir)
                if (newest) copySteamLogs(context, dir, record)
                SessionLogCapture.dumpCrashBuffer(File(dir, "crash.log"))
                SessionEvents.record("session.artifacts_recovered", mapOf("newest" to newest), dir)
                markScrubbed(scrubTree(record))
                File(dir, COMPLETE_MARKER).writeText("collected: late, at next app start, ${now()}\n")
                Log.i(TAG, "finished the abandoned session folder $dir")
            } catch (e: Exception) {
                Log.w(TAG, "could not finish $dir", e)
            }
        }
    }

    /**
     * Keeps the newest [SessionPaths.KEEP_SESSIONS] session folders and deletes the rest: a few
     * days of testing left hundreds, and the one a report needed was lost among them. Only folders
     * that are finished; the session in progress is never touched. Runs at app start after
     * [finishAbandoned], on its worker thread.
     */
    @Synchronized
    fun prune(context: Context) {
        val current = SessionPaths.current()
        val finished = SessionPaths.sessionFolders(context).filter { it != current && File(it, COMPLETE_MARKER).exists() }
        val old = finished.dropLast(SessionPaths.KEEP_SESSIONS)
        old.forEach { com.droiddeck.launcher.core.FileUtils.delete(it) }
        if (old.isNotEmpty()) Log.i(TAG, "deleted ${old.size} session folder(s) past the newest ${SessionPaths.KEEP_SESSIONS}")
    }

    /**
     * Every session folder but the one in progress, and the one-off command logs (tools/, or loose
     * beside the folders from before it): the Setup page's Clear logs. Returns how many session
     * folders went.
     */
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
