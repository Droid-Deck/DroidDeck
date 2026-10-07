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

    /** Everything the end of a session gathers, into [dir]. Safe to call for a dead session. */
    fun collect(context: Context, dir: File, reason: String) {
        SessionLogCapture.stopFor(dir)
        try {
            val wayland = File(dir, "wayland.log")
            if (!wayland.exists()) {
                WaylandCompositor.currentLogFile()?.takeIf { it.isFile }?.let { src ->
                    src.copyTo(wayland, overwrite = true)
                }
            }
            LogRedactor.learnFromRuntime(LinuxRuntime.rootDir(context))
            copySteamLogs(context, dir)
            // A session the system killed leaves its trace here and nowhere else.
            SessionLogCapture.dumpCrashBuffer(File(dir, "crash.log"))
            scrubTree(dir)
            SessionEvents.record("session.artifacts_collected", mapOf("reason" to reason), dir)
            File(dir, COMPLETE_MARKER).writeText("collected: $reason at ${now()}\n")
        } catch (e: Exception) {
            Log.w(TAG, "collecting session artifacts", e)
        }
    }

    @Synchronized
    fun scrubOlder(context: Context) {
        LogRedactor.learnFromRuntime(LinuxRuntime.rootDir(context))
        SessionPaths.sessionFolders(context).filter {
            it.parentFile == LinuxRuntime.debugLogDir(context) && it != SessionPaths.current() &&
                !File(it, COMPLETE_MARKER).exists()
        }.forEach { scrubTree(it) }
    }

    internal fun scrubTree(dir: File) {
        SessionLogFiles.candidates(dir).forEach { file ->
            if (!SessionLogFiles.safeFile(dir, file)) return@forEach
            var tmp: File? = null
            try {
                tmp = File.createTempFile(".redacted-", ".tmp", dir)
                val size = file.length()
                val modified = file.lastModified()
                val limit = if (file.parentFile?.name == "steam") SessionLogFiles.STEAM_MAX_BYTES else SessionLogFiles.MAX_BYTES
                tmp.bufferedWriter().use { SessionLogFiles.scrubTo(dir, file, it, limit) }
                if (file.length() == size && file.lastModified() == modified) {
                    check(tmp.renameTo(file))
                }
            } catch (e: Exception) {
                Log.w(TAG, "could not scrub ${file.name}", e)
            } finally {
                tmp?.delete()
            }
        }
    }

    private fun copySteamLogs(context: Context, dir: File) {
        val logs = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/logs")
        if (!logs.isDirectory || java.nio.file.Files.isSymbolicLink(logs.toPath())) return
        val out = File(dir, "steam")
        if (java.nio.file.Files.isSymbolicLink(out.toPath()) || !(out.isDirectory || out.mkdirs())) return
        SessionLogFiles.steamNames.map { File(logs, it) }.filter { it.exists() }.forEach { src ->
            val tmp = File.createTempFile(".redacted-", ".tmp", out)
            try {
                tmp.bufferedWriter().use { SessionLogFiles.scrubTo(logs, src, it, SessionLogFiles.STEAM_MAX_BYTES) }
                check(tmp.renameTo(File(out, src.name)))
            } catch (e: Exception) {
                Log.w(TAG, "could not scrub ${src.name}", e)
            } finally {
                tmp?.delete()
            }
        }
    }

    /**
     * Every session folder without a marker gets its ending now. Only the newest of them gets the
     * Steam logs - the runtime holds one set, and it belongs to the last session that ran; an
     * older folder would be handed logs that are not its own. Runs on a worker thread at app
     * start; nothing here touches the session that is about to begin.
     */
    @Synchronized
    fun finishAbandoned(context: Context) {
        SessionLogMigration.run(context)
        val abandoned = SessionPaths.sessionFolders(context).filter {
            it.parentFile == LinuxRuntime.debugLogDir(context) && !File(it, COMPLETE_MARKER).exists()
        }
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
                if (newest) copySteamLogs(context, dir)
                SessionLogCapture.dumpCrashBuffer(File(dir, "crash.log"))
                SessionEvents.record("session.artifacts_recovered", mapOf("newest" to newest), dir)
                scrubTree(dir)
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
        old.forEach { SessionLogFiles.deleteTree(it) }
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
        gone.forEach { SessionLogFiles.deleteTree(it) }
        LinuxRuntime.debugLogDir(context).listFiles { f -> f.name == SessionPaths.TOOLS_DIR || f.name.matches(Regex("tools-\\d+")) }
            ?.forEach { SessionLogFiles.deleteTree(it) }
        // Before tools/, those logs sat loose beside the session folders.
        LinuxRuntime.debugLogDir(context).listFiles { f -> f.isFile && f.name.endsWith(".log") }?.forEach { it.delete() }
        Log.i(TAG, "cleared ${gone.size} session folder(s)")
        return gone.size
    }

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
