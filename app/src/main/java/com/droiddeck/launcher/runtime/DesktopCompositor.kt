package com.droiddeck.launcher.runtime

import android.content.Context
import android.util.Log
import java.io.File

/**
 * The desktop package's `/usr/share/droiddeck/kwin-compositor` marker selects how KWin draws.
 * `opengl` (the desktop default) asks for GPU frames through Zink; `software` forces the QPainter
 * compositor, which the app's Wayland layer can present when a device cannot provide a usable DRM
 * render node.
 */
object DesktopCompositor {
    private const val TAG = "DesktopCompositor"
    private const val MARKER = "usr/share/droiddeck/kwin-compositor"
    private const val BACKUP = "$MARKER.orig"
    private const val SOFTWARE = "software"

    private fun marker(context: Context) = File(LinuxRuntime.rootDir(context), MARKER)
    private fun backup(context: Context) = File(LinuxRuntime.rootDir(context), BACKUP)

    /**
     * Whether the marker reads `software` because the switch put it there: only then may the switch
     * put the backup back. A marker someone edited by hand is theirs.
     */
    fun forceSoftware(context: Context): Boolean = runCatching {
        marker(context).readText().trim() == SOFTWARE && backup(context).isFile
    }.getOrDefault(false)

    fun setForceSoftware(context: Context, on: Boolean): Boolean = runCatching {
        val marker = marker(context)
        val backup = backup(context)
        val current = if (marker.isFile) marker.readText().trim() else null
        if (on) {
            if (current != SOFTWARE) {
                if (current == null) return@runCatching false
                marker.copyTo(backup, overwrite = true)
            }
            marker.writeText("$SOFTWARE\n")
        } else if (backup.isFile) {
            backup.copyTo(marker, overwrite = true)
        } else if (current == SOFTWARE) {
            // The marker says software and nothing here put it there: what belongs on disk is unknown,
            // so leave it untouched. The switch already reads as off.
            Log.w(TAG, "kwin-compositor is software with no backup of its own; leaving it as it is")
        }
        true
    }.onFailure { Log.w(TAG, "could not change the desktop compositor marker", it) }.getOrDefault(false)
}
