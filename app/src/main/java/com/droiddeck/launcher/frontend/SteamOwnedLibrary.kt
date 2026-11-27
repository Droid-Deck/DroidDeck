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
import java.security.MessageDigest
import java.util.UUID

object SteamOwnedLibrary {
    private const val TAG = "SteamOwnedLibrary"
    private const val CACHE = "steam-owned-library.json"
    private const val PREFS = "steam-owned-library"
    private const val CLIENT_IMPORT_BLOCKED = "client-import-blocked"
    private const val BLOCKED_COOKIE_FINGERPRINT = "blocked-cookie-fingerprint"
    private const val REFRESH_MS = 15 * 60 * 1000L
    private const val OWNED_URL = "https://store.steampowered.com/dynamicstore/userdata/?v=1"
    private val stateLock = Any()
    private var generation = 0L
    private var accountId: String? = null
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
    internal data class SyncResult(
        val generation: Long,
        val steamId: String?,
        val games: List<Library.SteamGame>,
        val available: Boolean,
    )
    private data class PendingCallback(
        val generation: Long,
        val steamId: String?,
        val callback: (SyncResult) -> Unit,
    )

    fun syncAsync(
        context: Context,
        force: Boolean = false,
        onResult: (steamId: String?, games: List<Library.SteamGame>) -> Unit = { _, _ -> },
    ) = syncAsyncResult(context, force) { result -> onResult(result.steamId, result.games) }

    internal fun syncAsyncResult(
        context: Context,
        force: Boolean = false,
        onResult: (SyncResult) -> Unit = {},
    ) {
        val (requestGeneration, requestSteamId) = accountSnapshot()
        val pending = PendingCallback(requestGeneration, requestSteamId, onResult)
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
                    val (currentGeneration, currentSteamId) = accountSnapshot()
                    val saved = currentSteamId?.let { id -> readSaved(app)?.takeIf { it.steamId == id } }
                    SyncResult(
                        currentGeneration,
                        currentSteamId,
                        saved?.games.toSteamGames(),
                        ownedLibraryAvailable(currentSteamId, fetched = false, cachedSteamId = saved?.steamId),
                    )
                }
                val currentAccount = accountSnapshot()
                val (completed, runForcedPass) = synchronized(callbacksLock) {
                    val done = activeCallbacks.filter { it.generation == result.generation && it.steamId == result.steamId }
                    val stale = activeCallbacks.filterNot { it.generation == result.generation && it.steamId == result.steamId }
                    activeCallbacks.clear()
                    if (forceAfterActive || stale.isNotEmpty()) {
                        activeCallbacks += stale.map { it.copy(generation = currentAccount.first, steamId = currentAccount.second) }
                        activeCallbacks += forcedCallbacks.map { it.copy(generation = currentAccount.first, steamId = currentAccount.second) }
                        forcedCallbacks.clear()
                        forceAfterActive = false
                        true
                    } else {
                        asyncRunning = false
                        false
                    }.let { done to it }
                }
                publish(result, completed)
                if (!runForcedPass) break
                forcePass = true
            }
        }, "steam-owned-library").start()
    }

    fun sync(context: Context, force: Boolean = false): List<Library.SteamGame> = syncResult(context, force).games

    internal fun syncResult(context: Context, force: Boolean = false): SyncResult {
        val app = context.applicationContext
        val result = syncForCurrentAccount(app, force)
        return if (result.steamId == null || accountStillCurrent(app, result.generation, result.steamId)) {
            result
        } else {
            result.copy(steamId = null, games = emptyList(), available = false)
        }
    }

    internal fun belongsToCurrentAccount(context: Context, result: SyncResult): Boolean =
        result.steamId?.let { steamId ->
            val current = accountStillCurrent(context.applicationContext, result.generation, steamId)
            val (currentGeneration, currentSteamId) = accountSnapshot()
            current && ownedGamesBelongToAccount(result, currentGeneration, currentSteamId)
        } == true

    private fun syncForCurrentAccount(app: Context, force: Boolean): SyncResult = synchronized(syncLock) {
        val accountSession = SteamAccount.read(app)?.let { Session(it.steamId, it.cookieHeader) }
        val clientWebSession = readClientSessionOrNull(app)
        val clientSession = clientWebSession?.let { Session(it.steamId, it.cookieHeader) }
        val session = clientSession ?: accountSession
        if (session == null) {
            val (runGeneration, currentSteamId) = accountSnapshot()
            val saved = currentSteamId?.let { id -> readSaved(app)?.takeIf { it.steamId == id } }
            return@synchronized SyncResult(
                runGeneration,
                currentSteamId,
                saved?.games.toSteamGames(),
                ownedLibraryAvailable(currentSteamId, fetched = false, cachedSteamId = saved?.steamId),
            )
        }
        val runGeneration = bindAccount(session.steamId)
        val saved = readSaved(app)
        val cacheIsFresh = !force && saved?.steamId == session.steamId &&
            System.currentTimeMillis() - saved.syncedAt < REFRESH_MS
        val clientNeedsAuthentication = clientSession != null && accountSession?.cookie != clientSession.cookie
        if (cacheIsFresh && !clientNeedsAuthentication && accountStillCurrent(app, runGeneration, session.steamId)) {
            return@synchronized SyncResult(runGeneration, session.steamId, saved!!.games.toSteamGames(), available = true)
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
                val current = accountStillCurrent(app, runGeneration, session.steamId)
                val cached = saved?.takeIf { it.steamId == session.steamId }
                return@synchronized SyncResult(
                    runGeneration,
                    session.steamId.takeIf { current },
                    if (current) cached?.games.toSteamGames() else emptyList(),
                    current && ownedLibraryAvailable(session.steamId, fetched = false, cachedSteamId = cached?.steamId),
                )
            }
            try {
                ownedApps(fallback.cookie)
            } catch (fallbackError: Exception) {
                Log.w(TAG, "Steam library refresh failed (${fallbackError.javaClass.simpleName})")
                val current = accountStillCurrent(app, runGeneration, session.steamId)
                val cached = saved?.takeIf { it.steamId == session.steamId }
                return@synchronized SyncResult(
                    runGeneration,
                    session.steamId.takeIf { current },
                    if (current) cached?.games.toSteamGames() else emptyList(),
                    current && ownedLibraryAvailable(session.steamId, fetched = false, cachedSteamId = cached?.steamId),
                )
            }
        }

        if (authenticatedClient && clientWebSession != null) {
            val sessionStillCurrent = accountStillCurrent(app, runGeneration, session.steamId)
            val savedClientSession = synchronized(stateLock) {
                if (!sessionStillCurrent || generation != runGeneration || accountId != session.steamId) false else {
                    SteamAccount.save(app, clientWebSession)
                    StoresState.refresh(app)
                    true
                }
            }
            if (!savedClientSession) {
                return@synchronized SyncResult(runGeneration, null, emptyList(), available = false)
            }
        }

        SteamAppInfo.lookup(app, ids.toList())
        var metadataIncomplete = false
        val freshGames = ids.sorted().mapNotNull { id ->
            val info = runCatching { SteamAppInfo.info(app, id) }.getOrNull()
            if (info == null) {
                metadataIncomplete = true
                return@mapNotNull null
            }
            if (!info.type.equals("game", ignoreCase = true) || info.name.isBlank()) return@mapNotNull null
            id to info.name
        }
        val sessionStillCurrent = accountStillCurrent(app, runGeneration, session.steamId)
        val stillCurrent = synchronized(stateLock) {
            if (!sessionStillCurrent || generation != runGeneration || accountId != session.steamId) {
                false
            } else {
                if (!metadataIncomplete) writeSaved(app, Saved(session.steamId, System.currentTimeMillis(), freshGames))
                true
            }
        }
        SyncResult(
            runGeneration,
            session.steamId.takeIf { stillCurrent },
            if (stillCurrent) freshGames.toSteamGames() else emptyList(),
            stillCurrent && ownedLibraryAvailable(session.steamId, fetched = true, cachedSteamId = saved?.steamId),
        )
    }

    fun clear(context: Context) {
        val app = context.applicationContext
        val cookie = SteamAccount.read(app)?.cookieHeader
        val clearedGeneration = synchronized(stateLock) {
            generation += 1
            accountId = null
            blockClientSessionImport(app, cookie)
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

    internal fun accountChanged(steamId: String?) {
        val changedGeneration = synchronized(stateLock) {
            if (accountId == steamId) return
            accountId = steamId
            ++generation
        }
        clearPublishedGames(changedGeneration, steamId)
    }

    fun allowClientSessionImport(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(CLIENT_IMPORT_BLOCKED)
            .remove(BLOCKED_COOKIE_FINGERPRINT)
            .apply()
    }

    internal fun blockClientSessionImport(context: Context, cookieHeader: String?) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(CLIENT_IMPORT_BLOCKED, true)
            .apply {
                cookieHeader?.let { putString(BLOCKED_COOKIE_FINGERPRINT, cookieFingerprint(it)) }
                    ?: remove(BLOCKED_COOKIE_FINGERPRINT)
            }
            .commit()
    }

    private fun publish(result: SyncResult, callbacks: List<PendingCallback>) {
        main.post {
            synchronized(stateLock) {
                val current = generation == result.generation && accountId == result.steamId
                if (current) games = result.games
                callbacks.forEach { pending ->
                    val accepted = current && pending.generation == generation && pending.steamId == accountId
                    val callbackResult = if (accepted) result else result.copy(
                        steamId = null,
                        games = emptyList(),
                        available = false,
                    )
                    runCatching { pending.callback(callbackResult) }
                }
                if (!current && generation == result.generation && accountId == null) games = emptyList()
            }
        }
    }

    private fun accountSnapshot(): Pair<Long, String?> = synchronized(stateLock) { generation to accountId }

    private fun bindAccount(steamId: String): Long {
        val changedGeneration = synchronized(stateLock) {
            if (accountId == steamId) return generation
            accountId = steamId
            ++generation
        }
        clearPublishedGames(changedGeneration, steamId)
        return changedGeneration
    }

    private fun clearPublishedGames(expectedGeneration: Long, expectedSteamId: String?) {
        main.post {
            synchronized(stateLock) {
                if (generation == expectedGeneration && accountId == expectedSteamId) games = emptyList()
            }
        }
    }

    private fun accountStillCurrent(context: Context, expectedGeneration: Long, expectedSteamId: String): Boolean {
        val latestSteamId = readClientSessionOrNull(context)?.steamId ?: SteamAccount.read(context)?.steamId
        var changed: Pair<Long, String>? = null
        val isCurrent = synchronized(stateLock) {
            val observed = observeSteamAccount(expectedGeneration, generation, accountId, expectedSteamId, latestSteamId)
            if (observed.generation != generation) {
                generation = observed.generation
                accountId = observed.accountId
                observed.accountId?.let { changed = observed.generation to it }
            }
            observed.isExpectedAccount
        }
        changed?.let { clearPublishedGames(it.first, it.second) }
        return isCurrent
    }

    private fun readClientSessionOrNull(context: Context): com.droiddeck.launcher.stores.steam.SteamWebSession? {
        if (!LinuxRuntime.isInstalled(context)) return null
        return runCatching { readClientSession(context) }.getOrElse {
            Log.w(TAG, "Steam web session is unavailable (${it.javaClass.simpleName})")
            null
        }?.takeUnless { isClientSessionBlocked(context, it.cookieHeader) }
    }

    internal fun clientSessionImportBlocked(context: Context): Boolean {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(CLIENT_IMPORT_BLOCKED, false)) return false
        val clientSession = if (LinuxRuntime.isInstalled(app)) {
            runCatching { readClientSession(app) }.getOrNull()
        } else {
            null
        }
        return isClientSessionBlocked(app, clientSession?.cookieHeader)
    }

    internal fun isClientSessionBlocked(context: Context, cookieHeader: String?): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(CLIENT_IMPORT_BLOCKED, false)) return false
        val blockedFingerprint = prefs.getString(BLOCKED_COOKIE_FINGERPRINT, null)
        if (blockedFingerprint == null || cookieHeader == null || blockedFingerprint == cookieFingerprint(cookieHeader)) return true
        prefs.edit().remove(CLIENT_IMPORT_BLOCKED).remove(BLOCKED_COOKIE_FINGERPRINT).apply()
        return false
    }

    private fun cookieFingerprint(cookieHeader: String): String =
        android.util.Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(cookieHeader.toByteArray(Charsets.UTF_8)),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        )

    private fun readClientSession(context: Context): com.droiddeck.launcher.stores.steam.SteamWebSession? {
        val scratch = File(context.cacheDir, "steam-owned-library-cookies-${UUID.randomUUID()}")
        check(scratch.mkdirs()) { "could not create Steam cookie scratch directory" }
        return try {
            val cookies = SteamWebLogin.read(
                LinuxRuntime.rootDir(context), SessionState.steamClientArch,
                scratch,
                SteamWebLogin.HOSTS + SteamWebLogin.STORE_HOSTS,
            )
            SteamAccount.fromWebCookies(cookies)
        } finally {
            scratch.deleteRecursively()
        }
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
            return parseSteamOwnedApps(json)
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

    private fun writeSaved(context: Context, saved: Saved): Boolean {
        val games = JSONArray().apply { saved.games.forEach { (id, name) -> put(JSONObject().put("appId", id).put("name", name)) } }
        val json = JSONObject().put("steamId", saved.steamId).put("syncedAt", saved.syncedAt).put("games", games)
        val file = AtomicFile(File(context.filesDir, CACHE))
        val out = try {
            file.startWrite()
        } catch (e: Exception) {
            Log.w(TAG, "could not open Steam library cache (${e.javaClass.simpleName})")
            return false
        }
        try {
            out.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
            return true
        } catch (e: Exception) {
            file.failWrite(out)
            Log.w(TAG, "could not save Steam library (${e.javaClass.simpleName})")
            return false
        }
    }
}

internal fun parseSteamOwnedApps(json: JSONObject): Set<Int> {
    val apps = json.opt("rgOwnedApps")
    val ids = when (apps) {
        is JSONArray -> (0 until apps.length()).mapNotNull { apps.optInt(it).takeIf { id -> id > 0 } }
        is JSONObject -> apps.keys().asSequence().mapNotNull { it.toIntOrNull()?.takeIf { id -> id > 0 } }.toList()
        else -> throw java.io.IOException("Steam did not return a valid owned game list")
    }
    return ids.toSet()
}

internal fun ownedLibraryAvailable(steamId: String?, fetched: Boolean, cachedSteamId: String?): Boolean =
    steamId != null && (fetched || steamId == cachedSteamId)

internal data class SteamAccountObservation(
    val generation: Long,
    val accountId: String?,
    val isExpectedAccount: Boolean,
)

internal fun observeSteamAccount(
    expectedGeneration: Long,
    currentGeneration: Long,
    currentAccountId: String?,
    expectedSteamId: String,
    latestSteamId: String?,
): SteamAccountObservation {
    if (currentGeneration != expectedGeneration) {
        return SteamAccountObservation(currentGeneration, currentAccountId, false)
    }
    if (latestSteamId != null && latestSteamId != expectedSteamId) {
        return SteamAccountObservation(currentGeneration + 1, latestSteamId, false)
    }
    return SteamAccountObservation(currentGeneration, currentAccountId, currentAccountId == expectedSteamId)
}

internal fun ownedGamesBelongToAccount(
    result: SteamOwnedLibrary.SyncResult,
    currentGeneration: Long,
    currentSteamId: String?,
): Boolean = result.available && result.generation == currentGeneration &&
    result.steamId != null && result.steamId == currentSteamId
