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
    private const val OPENGL = "opengl"
    private const val SOFTWARE = "software"

    private fun marker(context: Context) = File(LinuxRuntime.rootDir(context), MARKER)
    private fun backup(context: Context) = File(LinuxRuntime.rootDir(context), BACKUP)

    fun forceSoftware(context: Context): Boolean =
        runCatching { marker(context).readText().trim() == SOFTWARE }.getOrDefault(false)

    fun setForceSoftware(context: Context, on: Boolean): Boolean = runCatching {
        val marker = marker(context)
        val backup = backup(context)
        if (on) {
            if (!backup.isFile) {
                if (!marker.isFile) return@runCatching false
                // A marker already set to software without a backup came from a manual fix; the
                // package default is the only original left to restore.
                if (marker.readText().trim() == SOFTWARE) backup.writeText("$OPENGL\n")
                else marker.copyTo(backup, overwrite = false)
            }
            marker.writeText("$SOFTWARE\n")
        } else {
            if (backup.isFile) backup.copyTo(marker, overwrite = true)
            else marker.writeText("$OPENGL\n")
        }
        true
    }.onFailure { Log.w(TAG, "could not change the desktop compositor marker", it) }.getOrDefault(false)
}
