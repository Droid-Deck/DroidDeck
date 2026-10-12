package com.droiddeck.launcher.frontend

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.GameStorage
import com.droiddeck.launcher.session.SessionState
import java.io.File

/**
 * A Steam game whose download finishes during a session, noticed by its appmanifest (a new one,
 * or StateFlags turning to 4, fully installed) with a light look every five seconds while the
 * session runs, and handed to [AutoComponents] as a single job that may run in the session, so its
 * Windows components are in place when the user plays it right after the download.
 */
object SessionInstalls {
    private const val TAG = "SessionInstalls"
    private const val POLL_MS = 5_000L

    /** One appmanifest as read: the app, its name and folder, whether it is fully installed. */
    class Manifest(val appId: Int, val name: String, val installDir: String, val installed: Boolean, val steamapps: File)

    private val APPID = Regex("\"appid\"\\s*\"(\\d+)\"", RegexOption.IGNORE_CASE)
    private val NAME = Regex("\"name\"\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val DIR = Regex("\"installdir\"\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val STATE = Regex("\"StateFlags\"\\s*\"(\\d+)\"", RegexOption.IGNORE_CASE)

    internal fun parse(file: File): Manifest? = runCatching {
        val text = file.readText()
        val id = APPID.find(text)?.groupValues?.get(1)?.toInt() ?: return null
        Manifest(id, NAME.find(text)?.groupValues?.get(1).orEmpty(), DIR.find(text)?.groupValues?.get(1).orEmpty(),
            STATE.find(text)?.groupValues?.get(1)?.toIntOrNull() == 4, file.parentFile!!)
    }.getOrNull()

    /** The libraries the session's client installs to, as host folders. */
    internal fun steamappsDirs(context: Context): List<File> = listOfNotNull(
        File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/steamapps"),
        GameStorage.effective(context)?.let { File(it.path, "steamapps") },
    ).filter { it.isDirectory }

    /**
     * Keeps what the libraries hold between looks: a manifest re-read only when its time changed.
     * [poll] returns the apps that became fully installed since the last look (none on the first).
     */
    internal class Watch(private val dirs: () -> List<File>) {
        private val seen = HashMap<String, Pair<Long, Manifest?>>()
        private var first = true

        fun poll(): List<Manifest> {
            val fresh = ArrayList<Manifest>()
            for (dir in dirs()) for (f in dir.listFiles { x -> x.isFile && x.name.startsWith("appmanifest_") && x.name.endsWith(".acf") }.orEmpty()) {
                val before = seen[f.path]
                if (before != null && before.first == f.lastModified()) continue
                val now = parse(f)
                seen[f.path] = f.lastModified() to now
                if (!first && now != null && now.installed && before?.second?.installed != true) fresh.add(now)
            }
            first = false
            return fresh
        }
    }

    /** A finished install as the Games tab would list it. */
    internal fun gameOf(m: Manifest): Library.SteamGame =
        Library.SteamGame(m.appId, m.name.ifEmpty { m.appId.toString() }, null, m.steamapps.parentFile?.path ?: "steam",
            gameFiles = File(m.steamapps, "common/${m.installDir}"), source = Library.SOURCE_STEAM)

    /**
     * Looks every five seconds while the session runs, on a thread of its own at background
     * priority; each finished install becomes one job for its Windows components.
     */
    fun watch(context: Context) {
        val app = context.applicationContext
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val watch = Watch { steamappsDirs(app) }
            try {
                // The session is still starting when this is called: up to two minutes for it.
                var waited = 0L
                while (!SessionState.running && waited < 120_000L) { Thread.sleep(POLL_MS); waited += POLL_MS }
                watch.poll()
                while (SessionState.running) {
                    for (m in watch.poll()) {
                        Log.i(TAG, "wincomp: ${m.name} (${m.appId}) finished installing in the session")
                        AutoComponents.queueInSession(app, gameOf(m))
                    }
                    Thread.sleep(POLL_MS)
                }
            } catch (e: InterruptedException) {
            } catch (e: Exception) {
                Log.w(TAG, "install watch: ${e.message}")
            }
        }, "session-installs").apply { isDaemon = true }.start()
    }
}
