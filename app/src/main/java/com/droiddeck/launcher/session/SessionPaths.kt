package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One folder per session under `Download/DroidDeck/`, holding everything that session recorded:
 *
 * ```
 *   Download/DroidDeck/session-20260921-161256/
 *       device.txt     what this device is, and every setting the session ran with
 *       session.log    the guest session: proot, gamescope, the client's stdout
 *       wayland.log    the app's compositor
 *       steam.log      the Steam client's own log, scrubbed  (Steam mode)
 *       steam/         the rest of the client's logs, scrubbed  (Steam mode)
 *       desktop.log    labwc, the panel and the programs on it  (desktop mode)
 * ```
 *
 * The folder is claimed by whoever starts first - the activity starts the compositor before the
 * service starts the session - so both write into the same one, and a recreated activity (a
 * foldable opening mid-session) joins the folder in progress instead of opening another.
 */
object SessionPaths {
    private const val TAG = "SessionPaths"

    @Volatile
    private var dir: File? = null

    /**
     * The folder for the session now starting, or the one already in progress. With logs turned
     * off on the main screen the folder lives in the app's cache instead of Downloads - the
     * scripts and the compositor still need somewhere to write - and [release] deletes it.
     */
    @Synchronized
    fun beginOrCurrent(context: Context): File {
        dir?.let { if (it.isDirectory) return it }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val parent = if (SessionPrefs.logsEnabled(context)) SessionFiles.logDirectory(context)
            else File(context.cacheDir, "session-logs")
        val baseName = "session-$stamp"
        var made = File(parent, baseName)
        var suffix = 2
        while (made.exists()) made = File(parent, "$baseName-${suffix++}")
        if (!made.isDirectory && !made.mkdirs()) Log.e(TAG, "could not create $made")
        dir = made
        Log.i(TAG, "session logs: $made" + if (SessionPrefs.logsEnabled(context)) "" else " (logs off: discarded at the end)")
        return made
    }

    fun current(): File? = dir

    /**
     * The ending session takes its folder with it: the next session (one that replaces this one
     * in place) then claims a folder of its own instead of sharing, and this one's collector
     * finishes the folder it was handed. Called on the main thread as the session stops.
     */
    @Synchronized
    fun take(): File? {
        val ended = dir
        dir = null
        return ended
    }

    fun file(name: String): File? = dir?.let { File(it, name) }

    /** Let the next session claim a new folder. Called when a session ends. */
    @Synchronized
    fun release(context: Context, ended: File) {
        // Only the session that owns the folder lets go of it (take() usually has already): the
        // next session may have claimed its own by the time the last one's collector gets here.
        if (dir == ended) dir = null
        // A folder kept in the cache was never meant to outlive its session.
        if (ended != null && ended.path.startsWith(context.cacheDir.path)) {
            com.droiddeck.launcher.core.FileUtils.delete(ended)
        }
    }
}
