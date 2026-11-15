package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SessionPaths {
    private const val TAG = "SessionPaths"

    const val KEEP_SESSIONS = 3

    const val TOOLS_DIR = "tools"

    private val NAMED = Regex("""^(\d{4})-(\d{2})-(\d{2})-(\d{2,})-.+$""")
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

    /** Whether the current session folder will survive teardown. No session means the next one can retain logs. */
    fun currentSessionLogsRetained(context: Context): Boolean =
        dir?.let { it.parentFile == SessionFiles.logDirectory(context) } ?: true

    fun lastEnded(): File? = ended?.takeIf { it.isDirectory }

    @Synchronized
    fun take(): File? {
        val taken = dir
        dir = null
        if (taken != null) ended = taken
        return taken
    }

    fun file(name: String): File? = dir?.let { File(it, name) }

    @Synchronized
    fun release(context: Context, ended: File) {
        if (dir == ended) dir = null
        if (ended != null && ended.path.startsWith(context.cacheDir.path)) {
            com.droiddeck.launcher.core.FileUtils.delete(ended)
        }
    }

    fun label(mode: String, program: String?): String = when (mode) {
        SessionService.MODE_RUN -> com.droiddeck.launcher.frontend.Library.nameForProgram(program)
            ?: program?.substringAfterLast('/')
            ?: mode
        SessionService.MODE_DESKTOP -> "desktop"
        else -> mode
    }

    fun isSessionFolder(f: File): Boolean =
        f.isDirectory && (NAMED.matches(f.name) || LEGACY.matches(f.name))

    val chronological: Comparator<File> = compareBy { f ->
        NAMED.matchEntire(f.name)?.groupValues?.let { g -> "${g[1]}${g[2]}${g[3]}1${g[4].padStart(6, '0')}" }
            ?: LEGACY.matchEntire(f.name)?.groupValues?.let { g -> "${g[1]}0${g[2]}${g[3].removePrefix("-").padStart(2, '0')}" }
            ?: f.name
    }

    fun sessionFolders(context: Context): List<File> =
        (com.droiddeck.launcher.runtime.LinuxRuntime.logDir(context).listFiles { f -> isSessionFolder(f) }?.toList() ?: emptyList())
            .sortedWith(chronological)

    internal fun slug(text: String): String =
        text.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24).trim('-').ifEmpty { "session" }
}
