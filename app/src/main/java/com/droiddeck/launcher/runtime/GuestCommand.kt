package com.droiddeck.launcher.runtime

import android.content.Context
import android.os.Environment
import com.droiddeck.launcher.core.HostProcess
import com.droiddeck.launcher.session.OrphanReaper
import com.droiddeck.launcher.session.SessionFiles
import com.droiddeck.launcher.session.SessionPaths
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File

/**
 * One command in the Linux runtime outside any session - the store's Flatpak work, an AppImage
 * import - in a proot of its own. A session starting meanwhile leaves it running (OrphanReaper).
 */
object GuestCommand {
    /**
     * Runs [argv] and hands each output line to [onLine]; returns the exit status. [fakeRoot] is
     * for the package tools, which refuse any uid but 0. With [logName], everything the command
     * says also goes to Download/DroidDeck/tools/<logName>.log beside the session logs, so a problem can
     * be handed over like a session's. With [linkDir], hard links (which Android denies apps)
     * become symlinks to files proot keeps there (its link2symlink).
     */
    fun run(context: Context, argv: List<String>, fakeRoot: Boolean = false, logName: String? = null,
            linkDir: File? = null, onLine: (String) -> Unit): Int {
        val log = logName?.let {
            try {
                File(File(LinuxRuntime.debugLogDir(), SessionPaths.TOOLS_DIR).apply { mkdirs() }, "$it.log").printWriter()
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
        // The resolver otherwise names the DNS of whatever network the last session was on.
        LinuxNetworkLinkComponent(context, root).publish()
        val runtimeDir = File(context.filesDir, ".flatpak-rt").apply { mkdirs() }
        val binds = LinuxRuntime.binds(context, null, runtimeDir, Environment.getExternalStorageDirectory(), null)
        val guestEnv = listOf(
            "/usr/bin/env", "-i", "HOME=/root", "USER=root", "LANG=C.UTF-8",
            "PATH=/usr/local/bin:/usr/bin:/bin", "XDG_RUNTIME_DIR=${LinuxRuntime.GUEST_RUNTIME_DIR}",
            "XDG_DATA_HOME=/root/.local/share", "FLATPAK_BWRAP=${FlatpakManager.BWRAP}",
            "XDG_DATA_DIRS=/root/.local/share/flatpak/exports/share:/usr/local/share:/usr/share",
        )
        val launcher = LinuxRuntime.getLauncher(context)
        val cmd: List<String>
        if (launcher.isProot) {
            val mutable = LinuxRuntime.prootPrefix(context, root, "/root", fakeRoot)
            // link2symlink redirects hard links (which Android denies apps) to symlinks.
            if (linkDir != null) mutable.add(1, "--link2symlink")
            binds.forEach { mutable.add("-b"); mutable.add(it) }
            mutable += guestEnv + argv
            cmd = mutable
        } else {
            // Under chroot we are already root; fakeRoot and link2symlink are not needed.
            // guestEnv sets up a clean env via /usr/bin/env -i just as under proot.
            cmd = launcher.buildCommand(context, root, binds, guestEnv + argv)
        }
        val builder = ProcessBuilder(cmd).directory(root).redirectErrorStream(true)
        builder.environment().apply {
            if (launcher.isProot) {
                put("PROOT_LOADER", LinuxRuntime.prootLoader(context).path)
                put("PROOT_TMP_DIR", context.cacheDir.path)
                if (linkDir != null) put("PROOT_L2S_DIR", linkDir.path)
                if (SessionPrefs.prootNoSeccomp(context)) put("PROOT_NO_SECCOMP", "1")
                LinuxRuntime.prootLibraryPath(context).takeIf { it.isNotEmpty() }?.let { put("LD_LIBRARY_PATH", it) }
            }
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
