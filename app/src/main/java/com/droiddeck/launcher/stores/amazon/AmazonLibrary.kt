package com.droiddeck.launcher.stores.amazon

import android.content.Context
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreNet
import com.droiddeck.launcher.stores.StoreShelves
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.mergeItems
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class AmazonGame(
    val productId: String,
    val entitlementId: String,
    val title: String,
    val artUrl: String = "",
    val heroUrl: String = "",
    val developer: String = "",
    val publisher: String = "",
    val productSku: String = "",
    val versionId: String = "",
)

object AmazonLibrary {
    const val KEY_CACHE = "library_cache"
    const val KEY_SYNCED_AT = "library_synced_at"
    private const val THROTTLE_MS = 6 * 60 * 60 * 1000L
    private const val POSTERS_PER_SYNC = 40
    private const val POSTER_THREADS = 4
    private const val STORE_URL = "https://gaming.amazon.com/home"

    private val running = AtomicBoolean(false)
    private val store = Store.AMAZON

    fun toCacheJson(games: List<AmazonGame>): String = JSONArray().apply {
        for (g in games) put(
            JSONObject()
                .put("productId", g.productId).put("entitlementId", g.entitlementId).put("title", g.title)
                .put("artUrl", g.artUrl).put("heroUrl", g.heroUrl).put("developer", g.developer)
                .put("publisher", g.publisher).put("productSku", g.productSku).put("versionId", g.versionId),
        )
    }.toString()

    fun fromCacheJson(text: String?): List<AmazonGame> {
        if (text.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        val games = (0 until array.length()).mapNotNull { array.optJSONObject(it) }.mapNotNull { o ->
            AmazonGame(
                productId = o.optString("productId", "").ifEmpty { return@mapNotNull null },
                entitlementId = o.optString("entitlementId", ""),
                title = o.optString("title", ""),
                artUrl = o.optString("artUrl", ""),
                heroUrl = o.optString("heroUrl", ""),
                developer = o.optString("developer", ""),
                publisher = o.optString("publisher", ""),
                productSku = o.optString("productSku", ""),
                versionId = o.optString("versionId", ""),
            )
        }
        return tidy(games)
    }

    private fun tidy(games: List<AmazonGame>): List<AmazonGame> =
        games.distinctBy { it.productId }.sortedBy { it.title.lowercase() }

    fun cached(context: Context): List<AmazonGame> = fromCacheJson(AmazonPrefs.get(context).getString(KEY_CACHE, null))

    fun forget(context: Context) {
        AmazonPrefs.get(context).edit().remove(KEY_CACHE).remove(KEY_SYNCED_AT).apply()
    }

    fun toCatalogItem(context: Context, game: AmazonGame): CatalogItem {
        val prefs = AmazonPrefs.get(context)
        val poster = prefs.getString("vcover_${game.productId}", null)?.takeIf { it.isNotBlank() }
        val art = game.artUrl.ifEmpty { null }
        val hero = game.heroUrl.ifEmpty { null }
        val tags = listOf(game.developer, game.publisher.takeIf { it != game.developer }.orEmpty())
            .filter { it.isNotBlank() }
            .joinToString(", ")
        return CatalogItem(
            store = store,
            id = game.productId,
            title = game.title.ifBlank { game.productId.substringAfterLast('.') },
            imageUrl = hero ?: art,
            tallImageUrl = poster ?: art ?: hero,
            tags = tags,
            developer = game.developer,
            owned = true,
            sizeBytes = prefs.getLong("size_${game.productId}", 0L).takeIf { it > 0 } ?: 0L,
            storeUrl = STORE_URL,
            extra = mapOf("entitlementId" to game.entitlementId, "sku" to game.productSku, "version" to ""),
        )
    }

    private fun publish(app: Context, games: List<AmazonGame>) {
        val items = games.map { toCatalogItem(app, it) }
        StoresState.post {
            StoresState.library[store] = mergeItems(StoresState.library[store], items)
            val installed = StoresState.installed.filter { it.sidecar.store == store }.map { it.sidecar.id }.toSet()
            StoresState.shelves[store] = StoreShelves(trending = items.filterNot { it.id in installed }, art = StoresState.shelves[store]?.art.orEmpty())
        }
    }

    private fun gameFrom(o: JSONObject, before: AmazonGame?): AmazonGame = AmazonGame(
        productId = o.optString("productId"),
        entitlementId = o.optString("entitlementId"),
        title = o.optString("title"),
        artUrl = o.optString("art"),
        heroUrl = o.optString("hero"),
        developer = o.optString("developer"),
        publisher = o.optString("publisher"),
        productSku = o.optString("sku"),
        versionId = before?.versionId.orEmpty(),
    )

    private fun backfillPosters(app: Context, games: List<AmazonGame>) {
        val prefs = AmazonPrefs.get(app)
        val todo = games.filter { !prefs.contains("vcover_${it.productId}") }.take(POSTERS_PER_SYNC)
        if (todo.isEmpty()) return
        val pool = Executors.newFixedThreadPool(POSTER_THREADS)
        try {
            for (g in todo) pool.execute {
                val poster = runCatching { StoreNet.sgdbPoster(g.title) }.getOrDefault("")
                prefs.edit().putString("vcover_${g.productId}", poster).apply()
            }
        } finally {
            pool.shutdown()
            pool.awaitTermination(5, TimeUnit.MINUTES)
        }
    }

    private fun entitlements(token: String, serial: String): JSONObject? =
        AmazonNative.call("library.entitlements", JSONObject().put("accessToken", token).put("deviceSerial", serial))

    private fun failed(answer: JSONObject?): Boolean = answer == null || answer.has("error")

    fun sync(context: Context, force: Boolean) {
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        val prefs = AmazonPrefs.get(app)
        val cache = cached(app)
        val quiet = cache.isNotEmpty() && !force
        fun status(id: Int) { if (!quiet) StoresState.post { StoresState.status[store] = app.getString(id) } }
        fun problem(text: String?) {
            StoresState.post {
                StoresState.status.remove(store)
                if (text == null) {
                    StoresState.problems.remove(store)
                } else {
                    StoresState.problems[store] = text
                }
            }
        }
        try {
            if (cache.isNotEmpty()) publish(app, cache)
            if (!AmazonNative.ready()) return problem(AmazonNative.ENGINE_MISSING)
            val syncedAt = prefs.getLong(KEY_SYNCED_AT, 0L)
            if (!force && cache.isNotEmpty() && System.currentTimeMillis() - syncedAt in 0 until THROTTLE_MS) {
                backfillPosters(app, cache)
                publish(app, cache)
                return problem(null)
            }

            status(R.string.stores_sync_sign_in)
            val account = AmazonCredentialStore.load(app) ?: return problem(StoresState.notSignedInLine(store))
            val token = AmazonCredentialStore.getValidAccessToken(app) ?: return problem(StoresState.notSignedInLine(store))
            status(R.string.stores_sync_game_list)
            var answer = entitlements(token, account.deviceSerial)
            if (failed(answer)) {
                StoresState.logLine("amazon: library call failed (${answer?.optString("error").orEmpty().ifEmpty { "no answer" }}); refreshing the session")
                AmazonCredentialStore.refreshNow(app, token)?.let { answer = entitlements(it, account.deviceSerial) }
            }
            val result = answer
            when {
                result != null && result.optBoolean("signIn", false) -> return problem(StoresState.notSignedInLine(store))
                failed(result) -> {
                    StoresState.logLine("amazon: library sync failed: ${result?.optString("error").orEmpty().ifEmpty { "no answer" }}")
                    return problem(if (cache.isEmpty()) app.getString(R.string.stores_sync_amazon_no_answer) else null)
                }
            }
            val list = result!!.optJSONArray("games") ?: JSONArray()
            if (list.length() == 0) {
                return problem(if (cache.isEmpty()) app.getString(R.string.stores_sync_no_games, store.label) else null)
            }
            val before = cache.associateBy { it.productId }
            val games = tidy(
                (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                    .filter { it.optString("productId").isNotEmpty() }
                    .map { gameFrom(it, before[it.optString("productId")]) },
            )
            prefs.edit().putString(KEY_CACHE, toCacheJson(games)).putLong(KEY_SYNCED_AT, System.currentTimeMillis()).apply()
            StoresState.logLine("amazon: library has ${games.size} games (${result.optInt("total", games.size)} entitlements)")
            status(R.string.stores_sync_cover_art)
            backfillPosters(app, games)
            publish(app, games)
            problem(null)
        } catch (e: Exception) {
            problem(e.message ?: app.getString(R.string.stores_sync_failed))
        } finally {
            running.set(false)
        }
    }
}
