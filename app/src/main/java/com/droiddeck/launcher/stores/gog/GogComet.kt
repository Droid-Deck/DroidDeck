package com.droiddeck.launcher.stores.gog

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.SessionEvents
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.AchievementNotifications
import com.droiddeck.launcher.stores.StoreAchievements
import com.droiddeck.launcher.stores.StoreAccounts
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.StoreInstallRoot
import com.droiddeck.launcher.stores.StoresState
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object GogComet {
    private const val TAG = "GogComet"
    private const val BIN = "/usr/local/lib/droiddeck-comet/comet"
    private val lock = Any()
    @Volatile private var process: Process? = null
    private var pid = -1
    private var started: String? = null
    private var account = ""
    private var session: String? = null
    private var accepting = false
    private var generation: String? = null
    @Volatile private var gamesByClient: Map<String, StoreGameSidecar> = emptyMap()
    var status by mutableStateOf("off")
        private set

    fun enabled(context: Context) = GogPrefs.get(context).getBoolean("achievements", false)

    fun setEnabled(context: Context, on: Boolean) {
        GogPrefs.get(context).edit().putBoolean("achievements", on).apply()

    }

    fun begin() = synchronized(lock) {
        stopLocked()
        accepting = true
        generation = SessionState.sessionId
        AchievementNotifications.begin(generation)
    }

    fun replaceAccount(change: () -> Unit) = synchronized(lock) {
        stopLocked()
        AchievementNotifications.begin(generation)
        change()
    }

    fun stop() = synchronized(lock) {
        stopLocked()
    }

    fun end(expected: String?) = synchronized(lock) {
        if (generation == expected) {
            accepting = false
            stopLocked()
            AchievementNotifications.end(expected)
            generation = null
        }
    }

    private fun report(value: String) {
        StoresState.post { status = value }
        Log.i(TAG, value)
        SessionEvents.record("gog.achievements", mapOf("status" to value))
    }

    private fun publishPolicy(context: Context): File {
        val dir = File(context.filesDir, "gog-achievements").apply { mkdirs() }
        val games = if (!enabled(context)) emptyMap() else StoreInstallRoot.gameFolders(context).mapNotNull { folder ->
            val game = StoreGameSidecar.read(folder) ?: return@mapNotNull null
            if (game.store != Store.GOG || !game.achievements) return@mapNotNull null
            val client = GogPrefs.get(context).getString("client_id_${game.id}", null)
                ?: runCatching { JSONObject(File(folder, "goggame-${game.id}.info").readText()).optString("clientId") }.getOrNull()
            client?.takeIf { it.matches(Regex("[0-9]+")) }?.let { it to game }
        }.toMap()
        gamesByClient = games
        val file = File(dir, "clients.json")
        val tmp = File(dir, "clients.json.tmp")
        tmp.writeText(org.json.JSONArray(games.keys.toList()).toString())
        check(tmp.renameTo(file))
        return file
    }

    fun prepare(context: Context, id: String): JSONObject = synchronized(lock) {
        fun reply(ready: Boolean, reason: String): JSONObject {
            report(reason)
            return JSONObject().put("ok", ready).put("ready", ready).put("reason", reason)
        }
        if (!accepting || !SessionState.running || generation != SessionState.sessionId) return reply(false, "no-session")
        val folder = StoreInstallRoot.gameFolders(context).firstOrNull { f -> StoreGameSidecar.read(f)?.let { it.store == Store.GOG && it.id == id } == true }
        val sidecar = folder?.let { StoreGameSidecar.read(it) }
        if (sidecar?.store != Store.GOG || sidecar.id != id) return reply(false, "no-game")
        val policy = publishPolicy(context)
        if (!enabled(context) || !sidecar.achievements) return reply(false, "off")
        val credentials = GogAuth.cometCredentials(context) ?: return reply(false,
            if (StoreAccounts.isUnavailable(Store.GOG)) "credentials-unavailable"
            else if (StoreAccounts.read(context, Store.GOG)?.optString("refresh_token").isNullOrEmpty()) "sign-in-needed" else "credentials-unavailable")
        if (!SessionState.running || generation != SessionState.sessionId) return reply(false, "no-session")
        val user = credentials.getString("user_id")
        if (process?.isAlive == true && account == user && session == SessionState.sessionId && identity(pid) == started) {
            return JSONObject().put("ok", true).put("ready", true).put("reason", "ready")
        }
        stopLocked()
        if (!File(LinuxRuntime.rootDir(context), BIN.removePrefix("/")).isFile) return reply(false, "not-installed")
        val data = File(context.filesDir, "gog-achievements").apply { mkdirs() }
        val ready = CountDownLatch(1)
        val command = LinuxRuntime.command(context, null, null, Environment.getExternalStorageDirectory(),
            listOf("/usr/bin/env", "HOME=/root", "XDG_DATA_HOME=${data.path}/data", "XDG_CONFIG_HOME=${data.path}/config", "COMET_LOG=off", "COMET_ALLOWED_CLIENTS=${policy.path}", BIN, "--credentials-stdin"))
        val pb = ProcessBuilder(command).redirectErrorStream(true)
        pb.environment().apply {
            remove("LD_PRELOAD"); remove("LD_AUDIT"); remove("LD_LIBRARY_PATH")
            put("PROOT_LOADER", LinuxRuntime.prootLoader(context).path)
            put("PROOT_TMP_DIR", context.cacheDir.path)
            if (SessionPrefs.prootNoSeccomp(context)) put("PROOT_NO_SECCOMP", "1")
            LinuxRuntime.prootLibraryPath(context).takeIf { it.isNotEmpty() }?.let { put("LD_LIBRARY_PATH", it) }
        }
        try {
            val child = pb.start()
            process = child; account = user; session = SessionState.sessionId
            Thread({
                runCatching { child.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                    when {
                        line.startsWith("DROIDDECK_COMET_READY:") -> {
                            val childPid = line.substringAfter(':').toIntOrNull() ?: -1
                            if (childPid > 1 && process === child) {
                                pid = childPid; started = identity(childPid); ready.countDown()
                            }
                        }
                        line.startsWith("DROIDDECK_COMET_UNLOCKED:") -> if (process === child) {
                            val event = runCatching { JSONObject(line.substringAfter(':')) }.getOrNull()
                            val game = event?.optString("client_id")?.let { gamesByClient[it] }
                            val unlock = event?.let { AchievementNotifications.parse(it, game?.title.orEmpty()) }
                            val owner = session
                            if (game != null && unlock != null && owner != null && owner == SessionState.sessionId && SessionState.running) {
                                report("unlocked-locally")
                                AchievementNotifications.unlock(owner, unlock)
                                SessionEvents.record("achievement.unlocked", mapOf("store" to "gog", "gameId" to game.id, "achievementId" to unlock.id))
                            }
                        }
                        line == "DROIDDECK_COMET_PENDING" -> if (process === child) report("pending-upload")
                        line.startsWith("DROIDDECK_COMET_UPLOADED:") -> if (process === child && line.substringAfter(':').toIntOrNull()?.let { it > 0 } == true) { report("uploaded"); StoresState.post { StoreAchievements.invalidate() } }
                    }
                } } }
                if (process === child) report("service-unavailable")
                ready.countDown()
            }, "gog-comet-output").start()
            child.outputStream.bufferedWriter().use { it.write(credentials.toString()) }
            if (!ready.await(5, TimeUnit.SECONDS) || pid <= 1 || started == null || !child.isAlive) {
                stopLocked(); return reply(false, "service-unavailable")
            }
            reply(true, "ready")
        } catch (e: Exception) {
            stopLocked()
            reply(false, "service-unavailable")
        }
    }

    private fun identity(pid: Int): String? = if (pid <= 1) null else runCatching {
        File("/proc/$pid/stat").readText().substringAfterLast(") ").split(' ')[19]
    }.getOrNull()

    private fun stopLocked() {
        val child = process ?: return
        if (started != null && identity(pid) == started) android.os.Process.sendSignal(pid, 2)
        if (!child.waitFor(5, TimeUnit.SECONDS)) {
            if (started != null && identity(pid) == started) android.os.Process.sendSignal(pid, 15)
            child.destroy()
        }
        process = null; pid = -1; started = null; account = ""; session = null
        report("stopped")
    }
}
