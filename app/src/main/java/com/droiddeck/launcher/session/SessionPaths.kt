package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SessionPaths {
    private const val TAG = "SessionPaths"

    /** How many session folders are kept; older ones go at the next app start. */
    const val KEEP_SESSIONS = 30

    /** Beside the session folders: the logs of one-off commands (GuestCommand's logName). */
    const val TOOLS_DIR = "tools"

    /** `2026-09-30-03-steam`: day, that day's session number, what was run. */
    private val NAMED = Regex("""^(\d{4})-(\d{2})-(\d{2})-(\d{2,})-.+$""")
    /** `session-20260930-180642`, from before. */
    private val LEGACY = Regex("""^session-(\d{8})-(\d{6})(-\d+)?$""")

    @Volatile
    private var dir: File? = null

    @Volatile
    private var ended: File? = null

    @Synchronized
    fun beginOrCurrent(context: Context, label: String? = null): File {
        dir?.let { if (it.isDirectory) return it }
        val parent = if (SessionPrefs.logsEnabled(context)) SessionFiles.logDirectory(context)
            else File(context.cacheDir, "session-logs")
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        // The day's highest number plus one, not a count: pruning removes the oldest folders, and
        // a count would hand out a number already in use.
        val last = parent.listFiles()?.mapNotNull { f ->
            NAMED.matchEntire(f.name)?.takeIf { f.name.startsWith("$day-") }?.groupValues?.get(4)?.toIntOrNull()
        }?.maxOrNull() ?: 0
        val what = slug(label ?: label(SessionState.mode, SessionState.program))
        var number = last + 1
        var made = File(parent, "$day-${"%02d".format(Locale.US, number)}-$what")
        while (made.exists()) made = File(parent, "$day-${"%02d".format(Locale.US, ++number)}-$what")
        if (!made.isDirectory && !made.mkdirs()) Log.e(TAG, "could not create $made")
        dir = made
        Log.i(TAG, "session logs: $made" + if (SessionPrefs.logsEnabled(context)) "" else " (logs off: discarded at the end)")
        return made
    }

    fun current(): File? = dir

    /** The folder of the session that ended last, while it is still there: what its Share logs sends. */
    fun lastEnded(): File? = ended?.takeIf { it.isDirectory }

    /**
     * The ending session takes its folder with it: the next session (one that replaces this one
     * in place) then claims a folder of its own instead of sharing, and this one's collector
     * finishes the folder it was handed. Called on the main thread as the session stops.
     */
    @Synchronized
    fun take(): File? {
        val taken = dir
        dir = null
        if (taken != null) ended = taken
        return taken
    }

    fun file(name: String): File? = dir?.let { File(it, name) }

    /** Let the next session claim a new folder. Called when a session ends. */
    @Synchronized
    fun release(context: Context, ended: File) {
        // Only the session that owns the folder lets go of it (take() usually has already): the
        // next session may have claimed its own by the time the last one's collector gets here.
        if (dir == ended) dir = null
        // A folder kept in the cache was never meant to outlive its session.
        if (ended.parentFile == File(context.cacheDir, "session-logs")) {
            SessionLogFiles.deleteTree(ended)
        }
    }

    /**
     * What a session's folder is named for: Steam, the desktop, or the program started from the
     * rail by its own name (`2026-10-01-02-retroarch`).
     */
    fun label(mode: String, program: String?): String = when (mode) {
        SessionService.MODE_RUN -> com.droiddeck.launcher.frontend.Library.nameForProgram(program)
            ?: program?.substringAfterLast('/')
            ?: mode
        SessionService.MODE_DESKTOP -> "desktop"
        else -> mode
    }

    /** Whether [f] is a session's folder, in either naming. */
    fun isSessionFolder(f: File): Boolean =
        f.isDirectory && !java.nio.file.Files.isSymbolicLink(f.toPath()) &&
            (NAMED.matches(f.name) || LEGACY.matches(f.name))

    /**
     * Oldest first: by day, then by when in the day. A folder from before the new naming sorts
     * ahead of a new one from the same day, which it always predates.
     */
    val chronological: Comparator<File> = compareBy<File> { f ->
        NAMED.matchEntire(f.name)?.groupValues?.let { g -> "${g[1]}${g[2]}${g[3]}1${g[4].padStart(6, '0')}" }
            ?: LEGACY.matchEntire(f.name)?.groupValues?.let { g -> "${g[1]}0${g[2]}${g[3].removePrefix("-").padStart(2, '0')}" }
            ?: f.name
    }.thenBy { it.lastModified() }

    /** Every session folder in the places logs are written, oldest first. */
    fun sessionFolders(context: Context): List<File> =
        listOf(com.droiddeck.launcher.runtime.LinuxRuntime.debugLogDir(context),
            File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                com.droiddeck.launcher.runtime.LinuxRuntime.DEBUG_LOG_DIR))
            .flatMap { parent ->
                if (java.nio.file.Files.isSymbolicLink(parent.toPath())) emptyList()
                else parent.listFiles { f -> isSessionFolder(f) }?.toList() ?: emptyList()
            }
            .sortedWith(chronological)

    internal fun slug(text: String): String =
        text.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24).trim('-').ifEmpty { "session" }
}
