package com.droiddeck.launcher.stores.epic

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The signed-in account's Epic library: the library service's records, each enriched from the
 * catalog (title, art, DLC flag), cached so the tab paints at once and re-synced when forced or
 * 15 minutes stale. DLC entries are folded away under their base game. Ported from Bannerlator's
 * EpicLibraryRepo.
 */
object EpicLibrary {
    private const val TAG = "EpicLibrary"
    private const val CACHE_KEY = "library_cache"
    private const val LAST_SYNC_KEY = "library_synced_at"
    private const val THROTTLE_MS = 15L * 60L * 1000L

    private val syncing = AtomicBoolean(false)

    sealed interface SyncResult {
        class Ok(val games: List<EpicGame>) : SyncResult
        class Failed(val message: String) : SyncResult
        object NotLoggedIn : SyncResult
        object Busy : SyncResult
        object Throttled : SyncResult
    }

    fun cached(context: Context): List<EpicGame> {
        val json = EpicPrefs.get(context).getString(CACHE_KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            val list = ArrayList<EpicGame>(arr.length())
            for (i in 0 until arr.length()) {
                val j = arr.optJSONObject(i) ?: continue
                val g = EpicGame()
                g.appName = j.optString("appName", ""); g.namespace = j.optString("namespace", ""); g.catalogItemId = j.optString("catalogItemId", "")
                g.title = j.optString("title", ""); g.artCover = j.optString("artCover", ""); g.artSquare = j.optString("artSquare", "")
                g.developer = j.optString("developer", ""); g.description = j.optString("description", ""); g.version = j.optString("version", "")
                g.installSize = j.optLong("installSize", 0L).let { if (it > 1_099_511_627_776L) 0L else it }
                g.canRunOffline = j.optBoolean("canRunOffline", true)
                if (g.appName.isNotEmpty()) list.add(g)
            }
            list.distinctBy { it.appName }.sortedBy { it.title.lowercase() }
        }.getOrDefault(emptyList())
    }

    /** Remembers a game's install size once the manifest said it, for the Install button. */
    fun rememberSize(context: Context, appName: String, bytes: Long) {
        if (bytes <= 0) return
        val p = EpicPrefs.get(context)
        p.edit().putLong("size_$appName", bytes).apply()
    }

    fun sync(context: Context, force: Boolean = false, onStatus: (String) -> Unit = {}): SyncResult {
        if (!syncing.compareAndSet(false, true)) return SyncResult.Busy
        try {
            val app = context.applicationContext
            val p = EpicPrefs.get(app)
            val cachedList = cached(app)
            val lastSync = p.getLong(LAST_SYNC_KEY, 0L)
            if (!force && cachedList.isNotEmpty() && System.currentTimeMillis() - lastSync < THROTTLE_MS) return SyncResult.Throttled
            onStatus("Checking sign-in…")
            val token = EpicCredentialStore.getValidAccessToken(app) ?: return SyncResult.NotLoggedIn
            onStatus("Fetching game list…")
            val raw = EpicApiClient.getLibraryItems(token)
            if (raw.isNullOrEmpty()) return if (cachedList.isEmpty()) SyncResult.Failed("No games found in your Epic library") else SyncResult.Ok(cachedList)
            val total = raw.size
            var done = 0
            for (game in raw) {
                EpicApiClient.enrichFromCatalog(token, game)
                if (game.releaseDate.isNotEmpty()) p.edit().putString("release_${game.appName}", game.releaseDate).apply()
                done++
                if (done % 5 == 0) onStatus("Loading game details… ($done/$total)")
            }
            val main = raw.filter { !it.isDLC }
            val display = (if (main.isEmpty()) raw else main).distinctBy { it.appName }.sortedWith { a, b -> a.title.compareTo(b.title, ignoreCase = true) }
            for (fresh in display) {
                val old = cachedList.firstOrNull { it.appName == fresh.appName } ?: continue
                fresh.version = old.version
                fresh.installSize = old.installSize
            }
            saveCache(p, display)
            p.edit().putLong(LAST_SYNC_KEY, System.currentTimeMillis()).apply()
            Log.i(TAG, "sync: raw=${raw.size} shown=${display.size}")
            return SyncResult.Ok(display)
        } catch (e: Exception) {
            Log.w(TAG, "sync failed: ${e.message}")
            return SyncResult.Failed(e.message ?: "Sync failed")
        } finally {
            syncing.set(false)
        }
    }

    private fun saveCache(prefs: SharedPreferences, games: List<EpicGame>) {
        runCatching {
            val arr = JSONArray()
            for (g in games) arr.put(JSONObject().put("appName", g.appName).put("namespace", g.namespace).put("catalogItemId", g.catalogItemId)
                .put("title", g.title).put("artCover", g.artCover).put("artSquare", g.artSquare).put("developer", g.developer)
                .put("description", g.description).put("version", g.version).put("installSize", g.installSize).put("canRunOffline", g.canRunOffline))
            prefs.edit().putString(CACHE_KEY, arr.toString()).apply()
        }
    }

    fun toCatalogItem(context: Context, g: EpicGame): CatalogItem = CatalogItem(
        store = Store.EPIC, id = g.appName, title = g.title.ifBlank { g.appName },
        imageUrl = g.artSquare.ifBlank { null } ?: g.artCover.ifBlank { null },
        tallImageUrl = g.artCover.ifBlank { null } ?: g.artSquare.ifBlank { null },
        developer = g.developer, description = g.description, owned = true,
        sizeBytes = EpicPrefs.get(context).getLong("size_${g.appName}", g.installSize),
        storeUrl = "https://store.epicgames.com/en-US/browse?q=${java.net.URLEncoder.encode(g.title, "UTF-8")}",
        extra = mapOf("namespace" to g.namespace, "catalogItemId" to g.catalogItemId),
    )
}
