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
    private val loginSync = LoginSync()
    private var loadedCache: Pair<String, Long>? = null
    private var cachedGames = emptyList<Library.SteamGame>()
    var games by mutableStateOf<List<Library.SteamGame>>(emptyList())
        private set

    fun start(context: Context) {
        stop()
        if (SessionState.mode != SessionService.MODE_STEAM) return
        val app = context.applicationContext
        watcher = scope.launch {
            while (SessionState.running && !SessionState.stopRequested) {
                refresh(app)
                delay(5_000)
            }
        }
    }

    fun stop() {
        watcher?.cancel()
        watcher = null
    }

    suspend fun refresh(context: Context) = refreshLock.withLock {
        games = withContext(Dispatchers.IO) { cached(context) }
        val loaded = withContext(Dispatchers.IO) {
            if (!SessionState.running || SessionState.mode != SessionService.MODE_STEAM ||
                SessionState.suspended || SessionState.stopRequested) return@withContext null
            val root = SessionPrefs.activeSteamRoot(context)
            val config = AddedGames.accountConfig(root) ?: return@withContext null
            runCatching {
                val probe = AgentGuest.steamEval(context, LOGIN, 20_000L)
                val login = probe.opt("value") as? String
                if (!probe.optBoolean("ok") || login.isNullOrEmpty()) return@runCatching null
                val saved = loginSync.sync(login) {
                    val answer = AgentGuest.steamEval(context, SNAPSHOT, 20_000L)
                    val value = answer.opt("value") as? String
                    if (!answer.optBoolean("ok") || value == null || AddedGames.accountConfig(root) != config) return@sync false
                    val snapshot = JSONObject(value)
                    check(snapshot.getString("login") == login) { "Steam login changed during collection sync" }
                    readSnapshot(snapshot, config.parentFile!!.name, root)
                    val cache = AtomicFile(File(config, ".droiddeck-collection.json"))
                    val output = cache.startWrite()
                    try { output.write(snapshot.toString().toByteArray(Charsets.UTF_8)); cache.finishWrite(output) }
                    catch (e: Exception) { cache.failWrite(output); throw e }
                    true
                }
                if (saved) cached(context) else null
            }.onFailure { Log.w("SteamCollection", "Collection sync failed", it) }.getOrNull()
        }
        loaded?.let { games = it }
    }

    private fun cached(context: Context): List<Library.SteamGame> {
        val root = SessionPrefs.activeSteamRoot(context)
        val config = AddedGames.accountConfig(root)
        if (config == null) {
            loadedCache = null
            cachedGames = emptyList()
            return cachedGames
        }
        val file = File(config, ".droiddeck-collection.json")
        val stamp = file.path to file.lastModified()
        if (loadedCache == stamp) return cachedGames
        cachedGames = runCatching {
            readSnapshot(JSONObject(AtomicFile(file).readFully().toString(Charsets.UTF_8)), config.parentFile!!.name, root)
        }.getOrDefault(emptyList())
        loadedCache = stamp
        return cachedGames
    }

    internal class LoginSync {
        private var completed: String? = null

        fun sync(login: String, save: () -> Boolean): Boolean {
            if (login == completed || !save()) return false
            completed = login
            return true
        }
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

    internal val LOGIN = """
        (() => {
          let state = globalThis.__droiddeckCollectionLogin;
          if (!state) {
            state = {account: '', revision: 0, nonce: Date.now() + ':' + Math.random()};
            state.observe = user => {
              const account = user?.strSteamID || '';
              if (account !== state.account) { state.account = account; state.revision++; }
            };
            SteamClient.User.RegisterForCurrentUserChanges(state.observe);
            globalThis.__droiddeckCollectionLogin = state;
          }
          state.observe(globalThis.App?.m_CurrentUser);
          const store = globalThis.appStore;
          if (!state.account || state.account === '0' || !store?.m_bIsInitialized || !Array.isArray(store.allApps)) return null;
          return state.nonce + ':' + state.revision + ':' + state.account;
        })()
    """.trimIndent()

    internal val SNAPSHOT = """
        (() => {
          const login = $LOGIN;
          if (!login) throw new Error('Steam library is not ready');
          const store = globalThis.appStore;
          const account = globalThis.App.m_CurrentUser.strSteamID;
          return JSON.stringify({account, login, games: store.allApps.filter(a => a.app_type === 1 && a.appid > 0 && a.display_name)
            .map(a => ({id: a.appid, name: a.display_name, lastPlayed: a.rt_last_time_played || 0}))});
        })()
    """.trimIndent()
}
