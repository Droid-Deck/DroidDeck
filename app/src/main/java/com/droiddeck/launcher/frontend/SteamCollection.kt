package com.droiddeck.launcher.frontend

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.agent.AgentGuest
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object SteamCollection {
    private val refreshLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null
    var games by mutableStateOf<List<Library.SteamGame>>(emptyList())
        private set

    fun start(context: Context) {
        stop()
        if (SessionState.mode != SessionService.MODE_STEAM) return
        val app = context.applicationContext
        watcher = scope.launch {
            while (SessionState.running && !SessionState.stopRequested) {
                refresh(app)
                delay(30_000)
            }
        }
    }

    fun stop() {
        watcher?.cancel()
        watcher = null
    }

    suspend fun refresh(context: Context) = refreshLock.withLock {
        games = withContext(Dispatchers.IO) {
            val root = SessionPrefs.activeSteamRoot(context)
            val config = AddedGames.accountConfig(root) ?: return@withContext emptyList()
            runCatching { readSnapshot(JSONObject(AtomicFile(File(config, ".droiddeck-collection.json")).readFully().toString(Charsets.UTF_8)), config.parentFile!!.name, root) }
                .getOrDefault(emptyList())
        }
        val loaded = withContext(Dispatchers.IO) {
            val root = SessionPrefs.activeSteamRoot(context)
            val config = AddedGames.accountConfig(root) ?: return@withContext emptyList()
            val cache = AtomicFile(File(config, ".droiddeck-collection.json"))
            if (SessionState.running && SessionState.mode == SessionService.MODE_STEAM &&
                !SessionState.suspended && !SessionState.stopRequested) {
                runCatching {
                    val answer = AgentGuest.steamEval(context, SNAPSHOT, 20_000L)
                    if (answer.optBoolean("ok") && AddedGames.accountConfig(root) == config) {
                        val value = answer.opt("value")
                        if (value is String) {
                            val snapshot = JSONObject(value)
                            readSnapshot(snapshot, config.parentFile!!.name, root)
                            val bytes = snapshot.toString().toByteArray(Charsets.UTF_8)
                            val output = cache.startWrite()
                            try { output.write(bytes); cache.finishWrite(output) }
                            catch (e: Exception) { cache.failWrite(output); throw e }
                        }
                    }
                }.onFailure { Log.w("SteamCollection", "Collection sync failed", it) }
            }
            if (AddedGames.accountConfig(root) != config) return@withContext emptyList()
            runCatching { readSnapshot(JSONObject(cache.readFully().toString(Charsets.UTF_8)), config.parentFile!!.name, root) }
                .getOrDefault(emptyList())
        }
        games = loaded
    }

    internal fun readSnapshot(snapshot: JSONObject, accountId: String, root: File): List<Library.SteamGame> {
        val steamId = (accountId.toLong() + 76561197960265728L).toString()
        check(snapshot.getString("account") == steamId) { "Steam collection belongs to another account" }
        return read(snapshot.getJSONArray("games"), root)
    }

    internal fun read(entries: JSONArray, root: File): List<Library.SteamGame> =
        (0 until entries.length()).mapNotNull { i ->
            val entry = entries.getJSONObject(i)
            val id = entry.optInt("id")
            val name = entry.optString("name").trim()
            if (id <= 0 || name.isEmpty()) return@mapNotNull null
            val art = Library.SteamCacheImages(File(root, "appcache/librarycache/$id"))
            Library.SteamGame(id, name, art.find(listOf("library_capsule.jpg", "library_600x900.jpg")), Library.STEAM_OWNED,
                hero = art.find(listOf("library_hero.jpg")), lastPlayed = entry.optLong("lastPlayed"),
                icon = art.icon(), logo = art.find(Library.STEAM_LOGOS))
        }.distinctBy { it.appId }

    internal val SNAPSHOT = """
        (() => {
          const store = globalThis.appStore;
          if (!store?.m_bIsInitialized || !Array.isArray(store.allApps)) throw new Error('Steam library is not ready');
          const account = globalThis.App?.m_CurrentUser?.strSteamID;
          if (!account) throw new Error('Steam account is not ready');
          return JSON.stringify({account, games: store.allApps.filter(a => a.app_type === 1 && a.appid > 0 && a.display_name)
            .map(a => ({id: a.appid, name: a.display_name, lastPlayed: a.rt_last_time_played || 0}))});
        })()
    """.trimIndent()
}
