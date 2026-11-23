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

    @Volatile private var process: Process? = null
    @Volatile private var thread: Thread? = null

    internal fun parse(line: String): Pair<Int, Int>? {
        val match = DOWNLOADING.find(line) ?: return null
        val done = match.groupValues[1].toLongOrNull() ?: return null
        val total = match.groupValues[2].toLongOrNull()?.takeIf { it > 0 } ?: return null
        return (done * 100 / total).toInt().coerceIn(0, 100) to total.toInt()
    }

    fun running(): Boolean = thread?.isAlive == true

    fun pending(context: Context): Boolean = SteamDownloadMonitor.clientInstallPending(LinuxRuntime.rootDir(context))

    /** Stops a running download so a session can take over; the script resumes the partial files. */
    fun cancel() {
        process?.destroy()
    }

    @Synchronized
    fun start(context: Context, label: (Int) -> String, failedLabel: String) {
        if (running() || SessionState.running || !LinuxRuntime.isInstalled(context) || !pending(context)) return
        val app = context.applicationContext
        thread = Thread({
            Onboarding.report(Onboarding.Step.STEAM, label(-1), -1)
            val problem = runCatching { run(app, label) }.getOrElse { it.message ?: it.javaClass.simpleName }
            process = null
            if (problem == null && !pending(app)) Onboarding.finish(Onboarding.Step.STEAM)
            else if (SessionState.running) Onboarding.finish(Onboarding.Step.STEAM)
            else Onboarding.fail(Onboarding.Step.STEAM, problem ?: failedLabel)
            Onboarding.facts.value = Onboarding.facts.value.copy(steam = !pending(app))
        }, "steam-client-prefetch").also { it.start() }
    }

    private fun run(context: Context, label: (Int) -> String): String? {
        val root = LinuxRuntime.rootDir(context)
        LinuxRuntime.writeAccounts(context)
        SessionFiles.stage(context, root)
        val guest = listOf(
            "/usr/bin/env", "-i", "HOME=/root", "USER=root", "PATH=/usr/local/bin:/usr/bin:/bin",
            "LANG=C.UTF-8", "XDG_DATA_HOME=/root/.local/share", "/usr/local/bin/droiddeck-steam-install",
        )
        val child = ProtonExtras.guestProcess(context, guest).start()
        process = child
        child.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                Log.i(TAG, line)
                val progress = parse(line)
                if (progress != null) Onboarding.report(Onboarding.Step.STEAM, label(progress.second), progress.first)
            }
        }
        val status = child.waitFor()
        return if (status == 0) null else "droiddeck-steam-install exited $status"
    }
}
