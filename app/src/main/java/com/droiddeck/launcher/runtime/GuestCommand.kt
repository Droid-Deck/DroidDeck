package com.droiddeck.launcher.runtime

import android.content.Context
import android.os.Environment
import com.droiddeck.launcher.core.HostProcess
import com.droiddeck.launcher.session.OrphanReaper
import com.droiddeck.launcher.session.SessionFiles
import com.droiddeck.launcher.session.SessionPaths
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File

object GuestCommand {
    fun run(context: Context, argv: List<String>, fakeRoot: Boolean = false, logName: String? = null,
            linkDir: File? = null, onLine: (String) -> Unit): Int {
        val log = logName?.let {
            try {
                File(File(LinuxRuntime.logDir(context), SessionPaths.TOOLS_DIR).apply { mkdirs() }, "$it.log").printWriter()
            } catch (e: Exception) { null }
        }
        log?.println("== ${java.util.Date()} ${argv.joinToString(" ")}")
        try {
            return runLogged(context, argv, fakeRoot, linkDir) { line -> log?.println(line); log?.flush(); onLine(line) }
        } finally {
            log?.close()
        }
    }

    private fun runLogged(context: Context, argv: List<String>, fakeRoot: Boolean, linkDir: File?, onLine: (String) -> Unit): Int {
        val root = LinuxRuntime.rootDir(context)
        LinuxRuntime.writeAccounts(context)
        SessionFiles.stage(context, root)
        LinuxNetworkLinkComponent(context, root).publish()
        val runtimeDir = File(context.filesDir, ".flatpak-rt").apply { mkdirs() }
        val cmd = LinuxRuntime.prootPrefix(context, root, "/root", fakeRoot)
        if (linkDir != null) cmd.add(1, "--link2symlink")
        LinuxRuntime.binds(context, null, runtimeDir, Environment.getExternalStorageDirectory(), null)
            .forEach { cmd.add("-b"); cmd.add(it) }
        cmd += listOf(
            "/usr/bin/env", "-i", "HOME=/root", "USER=root", "LANG=C.UTF-8",
            "PATH=/usr/local/bin:/usr/bin:/bin", "XDG_RUNTIME_DIR=${LinuxRuntime.GUEST_RUNTIME_DIR}",
            "XDG_DATA_HOME=/root/.local/share", "FLATPAK_BWRAP=${FlatpakManager.BWRAP}",
            "DROIDDECK_BWRAP_SOCKET=${BwrapSpawner.socket(context)}",
            "XDG_DATA_DIRS=/root/.local/share/flatpak/exports/share:/usr/local/share:/usr/share",
        )
        cmd += argv
        val builder = ProcessBuilder(cmd).directory(root).redirectErrorStream(true)
        builder.environment().apply {
            put("PROOT_LOADER", LinuxRuntime.prootLoader(context).path)
            put("PROOT_TMP_DIR", LinuxRuntime.prootTempDir(context).path)
            if (linkDir != null) put("PROOT_L2S_DIR", linkDir.path)
            if (SessionPrefs.prootNoSeccomp(context)) put("PROOT_NO_SECCOMP", "1")
            LinuxRuntime.prootLibraryPath(context).takeIf { it.isNotEmpty() }?.let { put("LD_LIBRARY_PATH", it) }
        }
        val process = builder.start()
        val pid = HostProcess.pidOf(process)
        OrphanReaper.keep(pid)
        try {
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach(onLine) }
            return process.waitFor()
        } finally {
            OrphanReaper.release(pid)
        }
    }
}
