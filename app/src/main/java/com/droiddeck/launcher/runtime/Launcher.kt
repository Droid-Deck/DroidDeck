package com.droiddeck.launcher.runtime

import android.content.Context
import java.io.File

/**
 * Builds the host command that starts a guest process inside the Linux rootfs.
 *
 * [ProotLauncher] works on all devices. [ChrootLauncher] requires root and bypasses proot's
 * ptrace overhead entirely — every syscall runs at native speed.
 */
interface Launcher {
    /**
     * Returns the full command list to pass to [com.droiddeck.launcher.core.HostProcess] or
     * [ProcessBuilder]. [binds] are host:guest path specs; [argv] is the guest command.
     */
    fun buildCommand(context: Context, root: File, binds: List<String>, argv: List<String>): List<String>

    /** Host-side environment variables this launcher requires (e.g. PROOT_LOADER). */
    fun hostEnv(context: Context): Map<String, String>

    /** False for [ChrootLauncher]; gates ProotFastPath and PROOT_* env setup in callers. */
    val isProot: Boolean
}
