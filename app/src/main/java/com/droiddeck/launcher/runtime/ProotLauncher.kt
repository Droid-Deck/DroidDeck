package com.droiddeck.launcher.runtime

import android.content.Context
import java.io.File

/** Launches the guest via proot — works on all devices, rooted or not. */
class ProotLauncher : Launcher {
    override val isProot = true

    override fun buildCommand(
        context: Context,
        root: File,
        binds: List<String>,
        argv: List<String>,
    ): List<String> {
        val cmd = LinuxRuntime.prootPrefix(context, root, "/root")
        binds.forEach { cmd.add("-b"); cmd.add(it) }
        cmd.addAll(argv)
        return cmd
    }

    override fun hostEnv(context: Context): Map<String, String> = buildMap {
        put("PROOT_LOADER", LinuxRuntime.prootLoader(context).path)
        put("PROOT_TMP_DIR", context.cacheDir.path)
        val libs = LinuxRuntime.prootLibraryPath(context)
        if (libs.isNotEmpty()) put("LD_LIBRARY_PATH", libs)
    }
}
