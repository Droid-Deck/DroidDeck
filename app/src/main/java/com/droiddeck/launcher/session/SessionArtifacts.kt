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

    /** Every file in the folder has been through the redactor, own addresses and accounts included (-2: accounts added). */
    private const val SCRUBBED_MARKER = ".scrubbed-2"

    /**
     * Written by a session's own ending: every file, subfolders included (whatever the session
     * script copied into steam/ and droiddeck-esync/ as well as what the app wrote), has been through
     * the redactor. A file no newer than it needs no second pass when the folder is shared
     * (SessionLogShare). Older folders lack it and are scrubbed whole on the way into the zip.
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
            val scrubbed = copySteamLogs(context, dir)
            // A session the system killed leaves its trace here and nowhere else.
            SessionLogCapture.dumpCrashBuffer(File(dir, "crash.log"))
            scrubTree(dir, scrubbed)
            markScrubbed(dir)
            SessionEvents.record("session.artifacts_collected", mapOf("reason" to reason), dir)
            File(dir, COMPLETE_MARKER).writeText("collected: $reason at ${now()}\n")
        } catch (e: Exception) {
            Log.w(TAG, "collecting session artifacts", e)
        }
    }

    /**
     * Once, for session folders written before every file was scrubbed and before the device's own
     * addresses were (network.txt listed them; the client's IPv6 check logs "external address"
     * into steam/connection_log.txt): the whole folder, steam/ included, through the redactor.
     * A marker records it. Runs at app start with [finishAbandoned].
     */
    @Synchronized
    fun scrubOlder(context: Context) {
        val current = SessionPaths.current()
        val dirs = LinuxRuntime.debugLogDir().listFiles { f ->
            SessionPaths.isSessionFolder(f) && f != current && !File(f, SCRUBBED_MARKER).exists()
        } ?: return
        if (dirs.isEmpty()) return
        LogRedactor.learnFromRuntime(LinuxRuntime.rootDir(context))
        dirs.forEach { dir ->
            scrubFolder(dir)
            File(dir, "steam").takeIf { it.isDirectory }?.let { scrubFolder(it) }
            try { File(dir, SCRUBBED_MARKER).writeText("scrubbed ${now()}\n") } catch (e: Exception) {}
        }
        Log.i(TAG, "scrubbed ${dirs.size} older session folder(s)")
    }

    /**
     * The session's own files (session.log, app.log, desktop.log, the Steam desktop client's
     * steam-desktop.log, ...) were written as they happened, unscrubbed; this pass puts every one
     * through the redactor before the folder can be shared. A file is rewritten only if a line
     * changed. steam/ was scrubbed on the way in.
     */
    private fun scrubFolder(dir: File, skip: Set<File> = emptySet()) {
        dir.listFiles { f -> f.isFile && !f.name.startsWith(".") && f !in skip }?.forEach { f ->
            if (!LogRedactor.isText(f)) return@forEach
            try {
                val tmp = File(dir, ".${f.name}.scrub")
                tmp.bufferedWriter().use { w -> LogRedactor.scrubTo(f, w) }
                if (tmp.length() != f.length() || tmp.readBytes().contentEquals(f.readBytes()).not()) tmp.renameTo(f) else tmp.delete()
            } catch (e: Exception) {
                Log.w(TAG, "could not scrub ${f.name}", e)
            }
        }
    }

    private fun markScrubbed(dir: File) {
        try {
            File(dir, SCRUBBED_MARKER).writeText("scrubbed ${now()}\n")
            File(dir, SCRUBBED_TREE_MARKER).writeText("scrubbed ${now()}\n")
        } catch (e: Exception) {}
    }

    /**
     * [dir] and the folders in it (steam/, droiddeck-esync/): the session script copied files there
     * verbatim, and a Steam log the app did not copy over again (gone from the runtime, or past its
     * size limit) stayed as the script left it. [skip] are files already redacted on the way in.
     */
    private fun scrubTree(dir: File, skip: Set<File> = emptySet()) {
        scrubFolder(dir, skip)
        dir.listFiles { f -> f.isDirectory }?.forEach { scrubFolder(it, skip) }
    }

    /** Steam's logs: redacted into steam/, never copied verbatim. Returns the files it wrote. */
    private fun copySteamLogs(context: Context, dir: File): Set<File> {
        val logs = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/logs")
        if (!logs.isDirectory) return emptySet()
        val out = File(dir, "steam").apply { mkdirs() }
        val written = HashSet<File>()
        logs.listFiles { f -> f.isFile && f.length() < 8L * 1024 * 1024 }?.forEach { src ->
            try {
                val dst = File(out, src.name)
                dst.bufferedWriter().use { w ->
                    src.forEachLine { line -> w.write(LogRedactor.redact(line)); w.newLine() }
                }
                written += dst
            } catch (e: Exception) {
                Log.w(TAG, "could not scrub ${src.name}", e)
            }
        }
        Log.i(TAG, "collected ${out.listFiles()?.size ?: 0} Steam log(s), scrubbed, into $out")
        return written
    }

    /**
     * Every session folder without a marker gets its ending now. Only the newest of them gets the
     * Steam logs - the runtime holds one set, and it belongs to the last session that ran; an
     * older folder would be handed logs that are not its own. Runs on a worker thread at app
     * start; nothing here touches the session that is about to begin.
     */
    @Synchronized
    fun finishAbandoned(context: Context) {
        val parent = LinuxRuntime.debugLogDir()
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
                val scrubbed = if (newest) copySteamLogs(context, dir) else emptySet()
                SessionLogCapture.dumpCrashBuffer(File(dir, "crash.log"))
                SessionEvents.record("session.artifacts_recovered", mapOf("newest" to newest), dir)
                scrubTree(dir, scrubbed)
                markScrubbed(dir)
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
        com.droiddeck.launcher.core.FileUtils.delete(File(LinuxRuntime.debugLogDir(), SessionPaths.TOOLS_DIR))
        // Before tools/, those logs sat loose beside the session folders.
        LinuxRuntime.debugLogDir().listFiles { f -> f.isFile && f.name.endsWith(".log") }?.forEach { it.delete() }
        Log.i(TAG, "cleared ${gone.size} session folder(s)")
        return gone.size
    }

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
