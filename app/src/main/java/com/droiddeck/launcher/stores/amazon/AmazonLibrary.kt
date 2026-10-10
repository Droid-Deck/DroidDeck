package com.droiddeck.launcher.stores.amazon

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreNet
import com.droiddeck.launcher.stores.StoresState
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The signed-in account's Amazon Games library: the entitlements, DLC folded under their base
 * game, cached so the tab paints at once, re-synced when forced or 15 minutes stale. Amazon
 * publishes a square icon and a wide background only, so a 2:3 poster per title is backfilled
 * from SteamGridDB (a few per pass). Ported from Bannerlator's AmazonLibraryRepo.
 */
object AmazonLibrary {
    private const val TAG = "AmazonLibrary"
    private const val CACHE_KEY = "library_cache"
    private const val LAST_SYNC_KEY = "library_synced_at"
    private const val THROTTLE_MS = 6L * 60L * 60L * 1000L
    private const val POSTER_BUDGET = 40

    private val syncing = AtomicBoolean(false)

    sealed interface SyncResult {
        class Ok(val games: List<AmazonGame>) : SyncResult
        class Failed(val message: String) : SyncResult
        object NotLoggedIn : SyncResult
        object Busy : SyncResult
        object Throttled : SyncResult
    }

    fun cached(context: Context): List<AmazonGame> {
        val json = AmazonPrefs.get(context).getString(CACHE_KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            val out = ArrayList<AmazonGame>(arr.length())
            for (i in 0 until arr.length()) {
                val j = arr.optJSONObject(i) ?: continue
                val g = AmazonGame()
                g.productId = j.optString("productId", ""); g.entitlementId = j.optString("entitlementId", ""); g.title = j.optString("title", "")
                g.artUrl = j.optString("artUrl", ""); g.heroUrl = j.optString("heroUrl", ""); g.developer = j.optString("developer", "")
                g.publisher = j.optString("publisher", ""); g.productSku = j.optString("productSku", ""); g.versionId = j.optString("versionId", "")
                if (g.productId.isNotEmpty()) out.add(g)
            }
            out.distinctBy { it.productId }.sortedBy { it.title.lowercase() }
        }.getOrDefault(emptyList())
    }

    fun poster(context: Context, productId: String): String? = AmazonPrefs.get(context).getString("vcover_$productId", null)?.ifBlank { null }

    fun rememberSize(context: Context, productId: String, bytes: Long) {
        if (bytes > 0) AmazonPrefs.get(context).edit().putLong("size_$productId", bytes).apply()
    }

    fun sync(context: Context, force: Boolean = false, onStatus: (String) -> Unit = {}): SyncResult {
        if (!syncing.compareAndSet(false, true)) return SyncResult.Busy
        try {
            val app = context.applicationContext
            val p = AmazonPrefs.get(app)
            val cachedList = cached(app)
            if (!force && cachedList.isNotEmpty() && System.currentTimeMillis() - p.getLong(LAST_SYNC_KEY, 0L) < THROTTLE_MS) {
                backfillPosters(p, cachedList, onStatus)
                return SyncResult.Throttled
            }
            onStatus("Checking sign-in…")
            val creds = AmazonCredentialStore.load(app) ?: return SyncResult.NotLoggedIn
            val token = AmazonCredentialStore.getValidAccessToken(app) ?: return SyncResult.Failed(StoresState.notSignedInLine(Store.AMAZON))
            onStatus("Fetching game list…")
            val all = AmazonApiClient.getEntitlements(token, creds.deviceSerial)
            if (all.isNullOrEmpty()) return if (cachedList.isEmpty()) SyncResult.Failed("No games found in your Amazon library") else SyncResult.Ok(cachedList)
            val games = all.filter { !(it.isDLC && it.parentProductId.isNotEmpty()) }.ifEmpty { all }
                .sortedWith { a, b -> a.title.compareTo(b.title, ignoreCase = true) }
            for (fresh in games) cachedList.firstOrNull { it.productId == fresh.productId }?.let { fresh.versionId = it.versionId }
            saveCache(p, games)
            p.edit().putLong(LAST_SYNC_KEY, System.currentTimeMillis()).apply()
            backfillPosters(p, games, onStatus)
            Log.i(TAG, "sync: entitlements=${all.size} games=${games.size}")
            return SyncResult.Ok(games)
        } catch (e: Exception) {
            Log.w(TAG, "sync failed: ${e.message}")
            return SyncResult.Failed(e.message ?: "Sync failed")
        } finally {
            syncing.set(false)
        }
    }

    private fun backfillPosters(p: SharedPreferences, games: List<AmazonGame>, onStatus: (String) -> Unit) {
        val missing = games.filter { it.productId.isNotEmpty() && !p.contains("vcover_${it.productId}") }.take(POSTER_BUDGET)
        if (missing.isEmpty()) return
        onStatus("Fetching cover art…")
        val pool = Executors.newFixedThreadPool(4)
        val futures = missing.map { g -> pool.submit(Callable { g.productId to StoreNet.sgdbPoster(g.title) }) }
        pool.shutdown()
        val ed = p.edit()
        for (f in futures) {
            val (pid, url) = runCatching { f.get() }.getOrNull() ?: continue
            // A miss is recorded as "" so it is not asked again every pass.
            ed.putString("vcover_$pid", url)
        }
        ed.apply()
    }

    private fun saveCache(p: SharedPreferences, games: List<AmazonGame>) {
        runCatching {
            val arr = JSONArray()
            for (g in games) arr.put(JSONObject().put("productId", g.productId).put("entitlementId", g.entitlementId).put("title", g.title).put("artUrl", g.artUrl)
                .put("heroUrl", g.heroUrl).put("developer", g.developer).put("publisher", g.publisher).put("productSku", g.productSku).put("versionId", g.versionId))
            p.edit().putString(CACHE_KEY, arr.toString()).apply()
        }
    }

    fun toCatalogItem(context: Context, g: AmazonGame): CatalogItem = CatalogItem(
        store = Store.AMAZON, id = g.productId, title = g.title.ifBlank { g.shortId() },
        imageUrl = g.heroUrl.ifBlank { null } ?: g.artUrl.ifBlank { null },
        tallImageUrl = poster(context, g.productId) ?: g.artUrl.ifBlank { null } ?: g.heroUrl.ifBlank { null },
        tags = listOfNotNull(g.developer.ifBlank { null }, g.publisher.ifBlank { null }.takeIf { it != g.developer }).joinToString(", "),
        developer = g.developer, owned = true,
        sizeBytes = AmazonPrefs.get(context).getLong("size_${g.productId}", 0L),
        storeUrl = "https://gaming.amazon.com/home",
        extra = mapOf("entitlementId" to g.entitlementId, "sku" to g.productSku, "version" to (g.versionId ?: "")),
    )
}
