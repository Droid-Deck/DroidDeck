package com.droiddeck.launcher.frontend

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.session.SteamWebLogin
import com.droiddeck.launcher.stores.steam.SteamAccount
import com.droiddeck.launcher.stores.StoresState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object SteamOwnedLibrary {
    private const val TAG = "SteamOwnedLibrary"
    private const val CACHE = "steam-owned-library.json"
    private const val REFRESH_MS = 15 * 60 * 1000L
    private const val OWNED_URL = "https://store.steampowered.com/dynamicstore/userdata/?v=1"
    private val stateLock = Any()
    private var generation = 0L
    private val syncLock = Any()
    private val callbacksLock = Any()
    private val activeCallbacks = mutableListOf<PendingCallback>()
    private val forcedCallbacks = mutableListOf<PendingCallback>()
    private var asyncRunning = false
    private var forceAfterActive = false
    private val main = Handler(Looper.getMainLooper())

    var games by mutableStateOf<List<Library.SteamGame>>(emptyList())
        private set

    private data class Saved(val steamId: String, val syncedAt: Long, val games: List<Pair<Int, String>>)
    private data class Session(val steamId: String, val cookie: String)
    private data class SyncResult(val generation: Long, val steamId: String?, val games: List<Library.SteamGame>)
    private data class PendingCallback(
        val generation: Long,
        val callback: (steamId: String?, games: List<Library.SteamGame>) -> Unit,
    )

    fun syncAsync(
        context: Context,
        force: Boolean = false,
        onResult: (steamId: String?, games: List<Library.SteamGame>) -> Unit = { _, _ -> },
    ) {
        val pending = PendingCallback(currentGeneration(), onResult)
        var start = false
        synchronized(callbacksLock) {
            if (!asyncRunning) {
                asyncRunning = true
                activeCallbacks += pending
                start = true
            } else if (force) {
                forcedCallbacks += pending
                forceAfterActive = true
            } else {
                activeCallbacks += pending
            }
        }
        if (!start) return

        val app = context.applicationContext
        Thread({
            var forcePass = force
            while (true) {
                val result = runCatching { syncForCurrentAccount(app, forcePass) }.getOrElse {
                    Log.w(TAG, "Steam library sync failed (${it.javaClass.simpleName})")
                    SyncResult(currentGeneration(), null, emptyList())
                }
                val (completed, runForcedPass) = synchronized(callbacksLock) {
                    val done = activeCallbacks.toList()
                    activeCallbacks.clear()
                    if (forceAfterActive) {
                        activeCallbacks += forcedCallbacks
                        forcedCallbacks.clear()
                        forceAfterActive = false
                        true
                    } else {
                        asyncRunning = false
                        false
                    }.let { done to it }
                }
                publish(app, result, completed)
                if (!runForcedPass) break
                forcePass = true
            }
        }, "steam-owned-library").start()
    }

    fun sync(context: Context, force: Boolean = false): List<Library.SteamGame> =
        syncForCurrentAccount(context.applicationContext, force).games

    private fun syncForCurrentAccount(app: Context, force: Boolean): SyncResult = synchronized(syncLock) {
        val runGeneration = currentGeneration()
        val accountSession = SteamAccount.read(app)?.let { Session(it.steamId, it.cookieHeader) }
        val clientWebSession = readClientSessionOrNull(app)
        val clientSession = clientWebSession?.let { Session(it.steamId, it.cookieHeader) }
        val session = clientSession ?: accountSession
            ?: return@synchronized SyncResult(runGeneration, null, emptyList())
        val saved = readSaved(app)
        val cacheIsFresh = !force && saved?.steamId == session.steamId &&
            System.currentTimeMillis() - saved.syncedAt < REFRESH_MS
        val clientNeedsAuthentication = clientSession != null && accountSession?.cookie != clientSession.cookie
        if (cacheIsFresh && !clientNeedsAuthentication && isCurrent(app, runGeneration, session.steamId)) {
            return@synchronized SyncResult(runGeneration, session.steamId, saved!!.games.toSteamGames())
        }

        var authenticatedClient = false
        val ids = try {
            ownedApps(session.cookie).also { authenticatedClient = clientSession != null }
        } catch (primaryError: Exception) {
            val fallback = accountSession?.takeIf {
                it.steamId == session.steamId && it.cookie != session.cookie
            }
            if (fallback == null) {
                Log.w(TAG, "Steam library refresh failed (${primaryError.javaClass.simpleName})")
                val cached = saved?.takeIf { it.steamId == session.steamId }?.games.toSteamGames()
                return@synchronized SyncResult(runGeneration, session.steamId.takeIf { isCurrent(app, runGeneration, it) }, cached)
            }
            try {
                ownedApps(fallback.cookie)
            } catch (fallbackError: Exception) {
                Log.w(TAG, "Steam library refresh failed (${fallbackError.javaClass.simpleName})")
                val cached = saved?.takeIf { it.steamId == session.steamId }?.games.toSteamGames()
                return@synchronized SyncResult(runGeneration, session.steamId.takeIf { isCurrent(app, runGeneration, it) }, cached)
            }
        }

        if (authenticatedClient && clientWebSession != null) {
            val savedClientSession = synchronized(stateLock) {
                if (generation != runGeneration || currentSteamId(app) != session.steamId) false else {
                    SteamAccount.save(app, clientWebSession)
                    StoresState.refresh(app)
                    true
                }
            }
            if (!savedClientSession) {
                return@synchronized SyncResult(runGeneration, null, emptyList())
            }
        }

        SteamAppInfo.lookup(app, ids.toList())
        val freshGames = ids.sorted().mapNotNull { id ->
            val info = runCatching { SteamAppInfo.info(app, id) }.getOrNull() ?: return@mapNotNull null
            if (!info.type.equals("game", ignoreCase = true) || info.name.isBlank()) return@mapNotNull null
            id to info.name
        }
        val stillCurrent = synchronized(stateLock) {
            if (generation != runGeneration || currentSteamId(app) != session.steamId) {
                false
            } else {
                writeSaved(app, Saved(session.steamId, System.currentTimeMillis(), freshGames))
                true
            }
        }
        SyncResult(runGeneration, session.steamId.takeIf { stillCurrent }, if (stillCurrent) freshGames.toSteamGames() else emptyList())
    }

    fun clear(context: Context) {
        val app = context.applicationContext
        val clearedGeneration = synchronized(stateLock) {
            generation += 1
            SteamAccount.clear(app)
            File(app.filesDir, CACHE).delete()
            generation
        }
        main.post {
            synchronized(stateLock) {
                if (generation == clearedGeneration) games = emptyList()
            }
        }
    }

    private fun publish(context: Context, result: SyncResult, callbacks: List<PendingCallback>) {
        main.post {
            synchronized(stateLock) {
                val current = generation == result.generation && result.steamId != null &&
                    currentSteamId(context) == result.steamId
                if (current) games = result.games
                else if (generation == result.generation && result.steamId == null) games = emptyList()
                callbacks.forEach { pending ->
                    val callbackAccount = result.steamId.takeIf { current && pending.generation == generation }
                    runCatching { pending.callback(callbackAccount, if (callbackAccount != null) result.games else emptyList()) }
                }
            }
        }
    }

    private fun currentGeneration(): Long = synchronized(stateLock) { generation }

    private fun isCurrent(context: Context, expectedGeneration: Long, steamId: String): Boolean =
        synchronized(stateLock) {
            generation == expectedGeneration && currentSteamId(context) == steamId
        }

    private fun currentSteamId(context: Context): String? =
        readClientSessionOrNull(context)?.steamId ?: SteamAccount.read(context)?.steamId

    private fun readClientSessionOrNull(context: Context): com.droiddeck.launcher.stores.steam.SteamWebSession? {
        if (!LinuxRuntime.isInstalled(context)) return null
        return runCatching { readClientSession(context) }.getOrElse {
            Log.w(TAG, "Steam web session is unavailable (${it.javaClass.simpleName})")
            null
        }
    }

    private fun readClientSession(context: Context): com.droiddeck.launcher.stores.steam.SteamWebSession? {
        val cookies = SteamWebLogin.read(
            LinuxRuntime.rootDir(context), SessionState.steamClientArch,
            File(context.cacheDir, "steam-owned-library-cookies"),
            SteamWebLogin.HOSTS + SteamWebLogin.STORE_HOSTS,
        )
        return SteamAccount.fromWebCookies(cookies)
    }

    private fun ownedApps(cookie: String): Set<Int> {
        val connection = URL(OWNED_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 20_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Cookie", cookie)
        connection.setRequestProperty("Referer", "https://store.steampowered.com/")
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 DroidDeck/0.7")
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw java.io.IOException("Steam returned HTTP $status")
            val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            val apps = json.opt("rgOwnedApps")
            val ids = when (apps) {
                is JSONArray -> (0 until apps.length()).mapNotNull { apps.optInt(it).takeIf { id -> id > 0 } }
                is JSONObject -> apps.keys().asSequence().mapNotNull { it.toIntOrNull()?.takeIf { id -> id > 0 } }.toList()
                else -> emptyList()
            }
            if (apps == null) throw java.io.IOException("Steam did not return its owned game list")
            return ids.toSet()
        } finally {
            connection.disconnect()
        }
    }

    private fun List<Pair<Int, String>>?.toSteamGames(): List<Library.SteamGame> = this.orEmpty().map { (id, name) ->
        Library.SteamGame(
            appId = id,
            name = name,
            art = null,
            library = Library.NOT_INSTALLED,
            installed = false,
            remoteArt = "https://cdn.cloudflare.steamstatic.com/steam/apps/$id/library_600x900.jpg",
            remoteHero = "https://cdn.cloudflare.steamstatic.com/steam/apps/$id/library_hero.jpg",
        )
    }

    private fun readSaved(context: Context): Saved? = runCatching {
        val json = JSONObject(AtomicFile(File(context.filesDir, CACHE)).readFully().toString(Charsets.UTF_8))
        val games = json.optJSONArray("games") ?: JSONArray()
        Saved(
            steamId = json.optString("steamId"),
            syncedAt = json.optLong("syncedAt"),
            games = (0 until games.length()).mapNotNull { index ->
                val item = games.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optInt("appId").takeIf { it > 0 } ?: return@mapNotNull null
                item.optString("name").takeIf { it.isNotBlank() }?.let { id to it }
            },
        )
    }.getOrNull()

    private fun writeSaved(context: Context, saved: Saved) {
        val games = JSONArray().apply { saved.games.forEach { (id, name) -> put(JSONObject().put("appId", id).put("name", name)) } }
        val json = JSONObject().put("steamId", saved.steamId).put("syncedAt", saved.syncedAt).put("games", games)
        val file = AtomicFile(File(context.filesDir, CACHE))
        val out = file.startWrite()
        try {
            out.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            Log.w(TAG, "could not save Steam library (${e.javaClass.simpleName})")
        }
    }
}
