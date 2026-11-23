package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File

/**
 * Downloads Valve's Steam client while the session is stopped, so the first Play does not wait on it.
 * It runs the same droiddeck-steam-install the session runs, which keeps what arrived when it is cut
 * short and resumes from there.
 */
object SteamClientPrefetch {
    private const val TAG = "SteamClientPrefetch"
    private val DOWNLOADING = Regex("""downloading Steam: .*, (\d+) of (\d+) MB""")

    private const val CANCELLED = "cancelled"
    private val lock = Any()
    private var process: Process? = null
    private var cancelled = false
    @Volatile private var thread: Thread? = null

    internal fun parse(line: String): Pair<Int, Int>? {
        val match = DOWNLOADING.find(line) ?: return null
        val done = match.groupValues[1].toLongOrNull() ?: return null
        val total = match.groupValues[2].toLongOrNull()?.takeIf { it > 0 } ?: return null
        return (done * 100 / total).toInt().coerceIn(0, 100) to total.toInt()
    }

    fun running(): Boolean = thread?.isAlive == true

    private const val CACHED = "root/.local/share/Steam/package/droiddeck-cached"

    fun installPending(context: Context): Boolean = SteamDownloadMonitor.clientInstallPending(LinuxRuntime.rootDir(context))

    fun pending(context: Context): Boolean =
        installPending(context) || !File(LinuxRuntime.rootDir(context), CACHED).isFile

    /** Stops a running download so a session can take over; the script resumes the partial files. */
    fun cancel() {
        synchronized(lock) {
            cancelled = true
            process?.destroy()
        }
    }

    private fun isCancelled() = synchronized(lock) { cancelled }

    @Synchronized
    fun start(context: Context, label: (Int) -> String, failedLabel: String) {
        if (running() || SessionState.running || !LinuxRuntime.isInstalled(context) || !pending(context)) return
        val app = context.applicationContext
        synchronized(lock) { cancelled = false }
        thread = Thread({
            Onboarding.report(Onboarding.Step.STEAM, label(-1), -1)
            val problem = runCatching { run(app, label) }.getOrElse { it.message ?: it.javaClass.simpleName }
            synchronized(lock) { process = null }
            if (problem == null && !pending(app)) Onboarding.finish(Onboarding.Step.STEAM)
            else if (SessionState.running || problem == CANCELLED) Onboarding.finish(Onboarding.Step.STEAM)
            else Onboarding.fail(Onboarding.Step.STEAM, problem ?: failedLabel)
            Onboarding.facts.value = Onboarding.facts.value.copy(steam = !pending(app))
        }, "steam-client-prefetch").also { it.start() }
    }

    private fun run(context: Context, label: (Int) -> String): String? {
        if (isCancelled()) return CANCELLED
        val root = LinuxRuntime.rootDir(context)
        LinuxRuntime.writeAccounts(context)
        SessionFiles.stage(context, root)
        val install = installPending(context)
        if (install) {
            val problem = script(context, emptyList(), label, 0, 35)
            if (problem != null) return problem
        }
        return script(context, listOf("--cache"), label, if (install) 35 else 0, 100)
    }

    private fun script(context: Context, args: List<String>, label: (Int) -> String, from: Int, to: Int): String? {
        val guest = listOf(
            "/usr/bin/env", "-i", "HOME=/root", "USER=root", "PATH=/usr/local/bin:/usr/bin:/bin",
            "LANG=C.UTF-8", "XDG_DATA_HOME=/root/.local/share", "/usr/local/bin/droiddeck-steam-install",
        ) + args
        if (isCancelled()) return CANCELLED
        val child = ProtonExtras.guestProcess(context, guest).start()
        synchronized(lock) {
            if (cancelled) {
                child.destroy()
                return CANCELLED
            }
            process = child
        }
        child.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                Log.i(TAG, line)
                val progress = parse(line)
                if (progress != null) Onboarding.report(Onboarding.Step.STEAM, label(progress.second), from + progress.first * (to - from) / 100)
            }
        }
        val status = child.waitFor()
        return if (status == 0) null else "droiddeck-steam-install exited $status"
    }
}
