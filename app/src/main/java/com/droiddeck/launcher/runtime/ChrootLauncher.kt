package com.droiddeck.launcher.runtime

import android.content.Context
import java.io.File

/**
 * Launches the guest via chroot on rooted devices.
 *
 * A single `su` call elevates to root, then [CHROOT_SCRIPT] sets up bind mounts inside a private
 * mount namespace (so they release automatically on exit) and execs `chroot` into the rootfs.
 * No ptrace is involved — syscalls run at native speed.
 *
 * Requires: Magisk or equivalent `su`, and `unshare`/`mount`/`chroot` from busybox or the system.
 */
class ChrootLauncher : Launcher {
    override val isProot = false

    override fun buildCommand(
        context: Context,
        root: File,
        binds: List<String>,
        argv: List<String>,
    ): List<String> {
        val script = File(root, CHROOT_SCRIPT).path
        // Build the single shell string passed to su -c.
        // Android paths and DroidDeck args never contain spaces, so no quoting is needed.
        val shellCmd = buildString {
            append(script)
            append(' ')
            append(root.path)
            for (bind in binds) { append(' '); append(bind) }
            append(" --")
            for (arg in argv) { append(' '); append(arg) }
        }
        return listOf("su", "-c", shellCmd)
    }

    override fun hostEnv(context: Context): Map<String, String> = emptyMap()

    companion object {
        /** Path inside the rootfs — on the Android host this is rootDir + CHROOT_SCRIPT. */
        const val CHROOT_SCRIPT = "usr/local/bin/droiddeck-chroot-launch"
    }
}
