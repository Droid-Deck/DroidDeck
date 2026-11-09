package com.droiddeck.launcher.runtime

import android.content.Context
import android.util.Log
import java.io.File

/**
 * The desktop package's `/usr/share/droiddeck/kwin-compositor` marker selects how KWin draws.
 * `opengl` (the desktop default) asks for GPU frames through Zink; `software` forces the QPainter
 * compositor, which the app's Wayland layer can present when a device cannot provide a usable DRM
 * render node.
 *
 * The switch only ever writes `software`. `kwin-compositor.orig` is the marker it found when it last
 * turned on; turning it off puts that back and records the marker under [LAST_WRITTEN], so a marker
 * someone changed since then does not match and is left alone.
 *
 * The switch reads `software` whenever the marker does, because `software` is what it forces; the
 * backup only decides whether turning the switch off may put the marker back.
 */
object DesktopCompositor {
    private const val TAG = "DesktopCompositor"
    private const val MARKER = "usr/share/droiddeck/kwin-compositor"
    private const val BACKUP = "$MARKER.orig"
    private const val LAST_WRITTEN = "$MARKER.mine"
    private const val SOFTWARE = "software"

    private fun marker(root: File) = File(root, MARKER)
    private fun backup(root: File) = File(root, BACKUP)
    private fun lastWritten(root: File) = File(root, LAST_WRITTEN)

    private fun text(file: File): String? = runCatching { file.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    /**
     * Whether the desktop draws in software. A marker someone set to `software` by hand counts: the
     * switch forces what is already true, and turning it off leaves that marker alone.
     */
    fun forceSoftware(context: Context): Boolean = forceSoftwareAt(LinuxRuntime.rootDir(context))

    internal fun forceSoftwareAt(root: File): Boolean = text(marker(root)) == SOFTWARE

    /**
     * Whether this switch has to stay put: the marker says `software`, nothing here put it there, so
     * there is no backup that turning the switch off could put back.
     */
    fun conflicted(context: Context): Boolean = conflictedAt(LinuxRuntime.rootDir(context))

    internal fun conflictedAt(root: File): Boolean =
        text(marker(root)) == SOFTWARE && backup(root).isFile.not() && text(lastWritten(root)) != SOFTWARE

    fun setForceSoftware(context: Context, on: Boolean): Boolean = setForceSoftwareAt(LinuxRuntime.rootDir(context), on)

    internal fun setForceSoftwareAt(root: File, on: Boolean): Boolean = runCatching {
        val marker = marker(root)
        val backup = backup(root)
        val lastWritten = lastWritten(root)
        val current = text(marker) ?: return@runCatching false
        if (on) {
            if (current != SOFTWARE) {
                marker.copyTo(backup, overwrite = true)
            }
            marker.writeText("$SOFTWARE\n")
            lastWritten.writeText("$SOFTWARE\n")
        } else {
            if (text(lastWritten) != current) {
                Log.w(TAG, "kwin-compositor changed outside the switch; leaving it as it is")
            } else if (backup.isFile) {
                backup.copyTo(marker, overwrite = true)
            } else {
                Log.w(TAG, "kwin-compositor has no backup to restore; leaving it as it is")
            }
            lastWritten.delete()
            backup.delete()
        }
        true
    }.onFailure { Log.w(TAG, "could not change the desktop compositor marker", it) }.getOrDefault(false)
}
