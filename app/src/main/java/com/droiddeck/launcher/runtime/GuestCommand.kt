package com.droiddeck.launcher.runtime

import android.content.Context
import android.os.Environment
import com.droiddeck.launcher.core.DownloadSource
import com.droiddeck.launcher.core.HostProcess
import com.droiddeck.launcher.session.OrphanReaper
import com.droiddeck.launcher.session.SessionFiles
import com.droiddeck.launcher.session.SessionPaths
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object GuestCommand {
    fun run(context: Context, argv: List<String>, fakeRoot: Boolean = false, logName: String? = null,
            linkDir: File? = null, extraBinds: List<String>? = null, fastPath: Boolean = false, cancelled: AtomicBoolean? = null,
            onLine: (String) -> Unit): Int {
        val log = logName?.let {
            try {
                File(File(LinuxRuntime.logDir(context), SessionPaths.TOOLS_DIR).apply { mkdirs() }, "$it.log").printWriter()
            } catch (e: Exception) { null }
        }
        log?.println("== ${java.util.Date()} ${argv.joinToString(" ")}")
        try {
            return runLogged(context, argv, fakeRoot, linkDir, extraBinds, fastPath, cancelled) { line -> log?.println(line); log?.flush(); onLine(line) }
        } finally {
            log?.close()
        }
    }

    private fun runLogged(context: Context, argv: List<String>, fakeRoot: Boolean, linkDir: File?, extraBinds: List<String>?,
                          fastPath: Boolean, cancelled: AtomicBoolean?, onLine: (String) -> Unit): Int {
        val root = LinuxRuntime.rootDir(context)
        LinuxRuntime.writeAccounts(context)
        SessionFiles.stage(context, root)
        LinuxNetworkLinkComponent(context, root).publish()
        val runtimeDir = File(context.filesDir, ".flatpak-rt").apply { mkdirs() }
        val cmd = LinuxRuntime.prootPrefix(context, root, "/root", fakeRoot)
        if (linkDir != null) cmd.add(1, "--link2symlink")
        val binds = LinuxRuntime.binds(context, null, runtimeDir, Environment.getExternalStorageDirectory(), extraBinds)
        binds.forEach { cmd.add("-b"); cmd.add(it) }
        // Fake root and link2symlink are emulated by proot, so their syscalls must stay on the traced path.
        val fastPathKey = if (fastPath && !fakeRoot && linkDir == null && ProotFastPath.enabled(context)) ProotFastPath.key(root, binds) else null
        cmd += listOf(
            "/usr/bin/env", "-i", "HOME=/root", "USER=root", "LANG=C.UTF-8",
            "PATH=/usr/local/bin:/usr/bin:/bin", "XDG_RUNTIME_DIR=${LinuxRuntime.GUEST_RUNTIME_DIR}",
            "XDG_DATA_HOME=/root/.local/share", "FLATPAK_BWRAP=${FlatpakManager.BWRAP}",
            "DROIDDECK_BWRAP_SOCKET=${BwrapSpawner.socket(context)}",
            "XDG_DATA_DIRS=/root/.local/share/flatpak/exports/share:/usr/local/share:/usr/share",
        )
        // The Steam client installer and the package tools inside the guest fetch over plain curl,
        // so the app proxy has to reach them through the environment proot starts them with.
        DownloadSource.proxyHostPort(context).takeIf { it.isNotEmpty() }?.let { hostPort ->
            cmd += listOf(
                "http_proxy=http://$hostPort", "https_proxy=http://$hostPort",
                "HTTP_PROXY=http://$hostPort", "HTTPS_PROXY=http://$hostPort",
                "no_proxy=localhost,127.0.0.1,::1", "NO_PROXY=localhost,127.0.0.1,::1",
            )
        }
        fastPathKey?.let { cmd += ProotFastPath.guestEnv(root, binds, it) }
        cmd += argv
        val builder = ProcessBuilder(cmd).directory(root).redirectErrorStream(true)
        builder.environment().apply {
            put("PROOT_LOADER", LinuxRuntime.prootLoader(context).path)
            put("PROOT_TMP_DIR", LinuxRuntime.prootTempDir(context).path)
            if (linkDir != null) put("PROOT_L2S_DIR", linkDir.path)
            if (SessionPrefs.prootNoSeccomp(context)) put("PROOT_NO_SECCOMP", "1")
            fastPathKey?.let { ProotFastPath.hostEnv(it).let { (k, v) -> put(k, v) } }
            LinuxRuntime.prootLibraryPath(context).takeIf { it.isNotEmpty() }?.let { put("LD_LIBRARY_PATH", it) }
        }
        if (cancelled?.get() == true) return 130
        val process = builder.start()
        val pid = HostProcess.pidOf(process)
        OrphanReaper.keep(pid)
        val watcher = cancelled?.let { flag ->
            Thread({
                try {
                    while (process.isAlive && !flag.get()) Thread.sleep(200)
                    if (flag.get()) {
                        // PRoot ignores SIGTERM; SIGQUIT makes it kill its tracees and exit cleanly.
                        if (pid > 0) {
                            runCatching { android.system.Os.kill(pid, android.system.OsConstants.SIGQUIT) }
                        } else {
                            process.destroy()
                        }
                        if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
                    }
                } catch (_: InterruptedException) {
                }
            }, "guest-cancel").apply { isDaemon = true; start() }
        }
        try {
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach(onLine) }
            return process.waitFor()
        } finally {
            watcher?.interrupt()
            OrphanReaper.release(pid)
        }
    }
}
