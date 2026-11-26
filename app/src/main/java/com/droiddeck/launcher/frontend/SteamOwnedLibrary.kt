package com.droiddeck.launcher.frontend

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.util.Log
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SteamWebLogin
import com.droiddeck.launcher.stores.steam.SteamAccount
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
    private val asyncRunning = java.util.concurrent.atomic.AtomicBoolean()
    private val callbacksLock = Any()
    private val callbacks = mutableListOf<(List<Library.SteamGame>) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    var games by mutableStateOf<List<Library.SteamGame>>(emptyList())
        private set

    private data class Saved(val steamId: String, val syncedAt: Long, val games: List<Pair<Int, String>>)

    fun syncAsync(context: Context, force: Boolean = false, onResult: (List<Library.SteamGame>) -> Unit = {}) {
        synchronized(callbacksLock) {
            callbacks += onResult
            if (!asyncRunning.compareAndSet(false, true)) return
        }
        val app = context.applicationContext
        Thread({
            val fresh = runCatching { sync(app, force) }.getOrElse {
                Log.w(TAG, "Steam library sync failed (${it.javaClass.simpleName})")
                emptyList()
            }
            main.post {
                games = fresh
                val completed = synchronized(callbacksLock) {
                    asyncRunning.set(false)
                    callbacks.toList().also { callbacks.clear() }
                }
                completed.forEach { callback -> runCatching { callback(fresh) } }
            }
        }, "steam-owned-library").start()
    }

    fun sync(context: Context, force: Boolean = false): List<Library.SteamGame> = synchronized(this) {
        val app = context.applicationContext
        val accountSession = SteamAccount.read(app)?.let { Session(it.steamId, it.cookieHeader) }
        val clientWebSession = if (!LinuxRuntime.isInstalled(app)) null else runCatching {
            readClientSession(app)
        }.getOrElse {
            Log.w(TAG, "Steam web session is unavailable (${it.javaClass.simpleName})")
            null
        }
        val clientSession = clientWebSession?.let { Session(it.steamId, it.cookieHeader) }
        val session = clientSession ?: accountSession ?: return emptyList()
        val useClientSession = clientSession != null
        if (clientSession != null && (
                accountSession?.steamId != clientSession.steamId || accountSession?.cookie != clientSession.cookie
            )
        ) {
            clientWebSession?.let { SteamAccount.save(app, it) }
            com.droiddeck.launcher.stores.StoresState.refresh(app)
        }
        val saved = readSaved(app)
        if (!force && saved?.steamId == session.steamId && System.currentTimeMillis() - saved.syncedAt < REFRESH_MS) {
            return saved.games.toSteamGames()
        }

        var usedClientSession = useClientSession
        val ids = try {
            ownedApps(session.cookie)
        } catch (primaryError: Exception) {
            val fallback = accountSession?.takeIf { it.steamId == session.steamId && it.cookie != session.cookie }
            if (fallback == null) {
                Log.w(TAG, "Steam library refresh failed (${primaryError.javaClass.simpleName})")
                return saved?.takeIf { it.steamId == session.steamId }?.games.toSteamGames()
            }
            usedClientSession = false
            try {
                ownedApps(fallback.cookie)
            } catch (fallbackError: Exception) {
                Log.w(TAG, "Steam library refresh failed (${fallbackError.javaClass.simpleName})")
                return saved?.takeIf { it.steamId == session.steamId }?.games.toSteamGames()
            }
        }
        if (usedClientSession) clientWebSession?.let { SteamAccount.save(app, it) }
        SteamAppInfo.lookup(app, ids.toList())
        val games = ids.sorted().mapNotNull { id ->
            val info = runCatching { SteamAppInfo.info(app, id) }.getOrNull() ?: return@mapNotNull null
            if (!info.type.equals("game", ignoreCase = true) || info.name.isBlank()) return@mapNotNull null
            id to info.name
        }
        writeSaved(app, Saved(session.steamId, System.currentTimeMillis(), games))
        games.toSteamGames()
    }

    fun clear(context: Context) {
        File(context.applicationContext.filesDir, CACHE).delete()
        main.post { games = emptyList() }
    }

    private data class Session(val steamId: String, val cookie: String)

    private fun readClientSession(context: Context): com.droiddeck.launcher.stores.steam.SteamWebSession? {
        val app = context.applicationContext
        val cookies = SteamWebLogin.read(
            LinuxRuntime.rootDir(app), SessionState.steamClientArch,
            File(app.cacheDir, "steam-owned-library-cookies"),
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
