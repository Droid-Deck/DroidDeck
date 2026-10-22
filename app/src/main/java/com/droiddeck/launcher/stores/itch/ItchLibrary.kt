package com.droiddeck.launcher.stores.itch

import android.content.Context
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.cleanStoreText
import com.droiddeck.launcher.stores.mergeItems
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

internal object ItchLibrary {
    private const val KEY_CACHE = "library_cache"
    private const val KEY_AT = "library_synced_at"
    private const val MAX_PAGES = 20
    private const val THROTTLE_MS = 6 * 60 * 60 * 1000L

    private val running = AtomicBoolean(false)

    fun cached(context: Context): List<CatalogItem> {
        val raw = ItchPrefs.get(context).getString(KEY_CACHE, null) ?: return emptyList()
        return runCatching { ItchCatalog.list(JSONArray(raw)) }.getOrDefault(emptyList())
    }

    fun sync(context: Context, force: Boolean) {
        val app = context.applicationContext
        if (!running.compareAndSet(false, true)) return
        try {
            val key = ItchAuth.key(app)
            val cache = cached(app)
            val prefs = ItchPrefs.get(app)
            if (key.isBlank()) {
                StoresState.post {
                    StoresState.status.remove(Store.ITCH)
                    if (cache.isNotEmpty()) StoresState.library[Store.ITCH] = cache
                    StoresState.problems[Store.ITCH] = app.getString(R.string.stores_sync_sign_in)
                }
                return
            }
            val quiet = cache.isNotEmpty() && !force
            if (quiet && System.currentTimeMillis() - prefs.getLong(KEY_AT, 0L) < THROTTLE_MS) {
                StoresState.post { StoresState.library[Store.ITCH] = cache }
                return
            }
            if (!quiet) StoresState.post { StoresState.status[Store.ITCH] = app.getString(R.string.stores_sync_game_list) }
            val items = fetch(key)
            if (items == null) {
                StoresState.post {
                    StoresState.status.remove(Store.ITCH)
                    if (cache.isNotEmpty()) StoresState.library[Store.ITCH] = cache
                    StoresState.problems[Store.ITCH] = app.getString(R.string.stores_sync_failed)
                }
                return
            }
            prefs.edit()
                .putString(KEY_CACHE, ItchCatalog.array(items).toString())
                .putLong(KEY_AT, System.currentTimeMillis())
                .apply()
            StoresState.post {
                StoresState.status.remove(Store.ITCH)
                StoresState.problems.remove(Store.ITCH)
                StoresState.library[Store.ITCH] = mergeItems(StoresState.library[Store.ITCH] ?: cache, items)
            }
            StoresState.logLine("itch.io: ${items.size} owned games")
        } finally {
            running.set(false)
        }
    }

    fun size(context: Context, item: CatalogItem): Long {
        val key = ItchAuth.key(context.applicationContext)
        if (key.isBlank()) return 0L
        val downloadKeyId = item.extra["downloadKeyId"].orEmpty().ifEmpty { return 0L }
        val gameId = item.extra["gameId"].orEmpty().ifEmpty { return 0L }
        val upload = ItchUploads.windows(ItchApi.uploads(key, gameId, downloadKeyId)) ?: return 0L
        return ItchUploads.size(upload)
    }

    private fun fetch(key: String): List<CatalogItem>? {
        val out = ArrayList<CatalogItem>()
        var page = 1
        var keys = 0
        while (page <= MAX_PAGES) {
            val body = ItchApi.ownedKeys(key, page) ?: return null
            val list = body.optJSONArray("owned_keys") ?: body.optJSONArray("ownedKeys") ?: return out
            if (list.length() == 0) break
            out.addAll(items(list))
            keys += list.length()
            page++
        }
        val games = out.distinctBy { it.id }
        StoresState.logLine("itch.io: $keys download keys, ${games.size} games")
        return games
    }

    internal fun items(keys: JSONArray): List<CatalogItem> {
        val out = ArrayList<CatalogItem>(keys.length())
        for (i in 0 until keys.length()) {
            val entry = keys.optJSONObject(i) ?: continue
            val game = entry.optJSONObject("game") ?: continue
            item(game, entry.optString("id"))?.let { out.add(it) }
        }
        return out
    }

    private fun JSONObject.text(snake: String, camel: String): String =
        optString(snake).ifBlank { optString(camel) }

    internal fun item(game: JSONObject, downloadKeyId: String): CatalogItem? {
        val gameId = game.optLong("id")
        if (gameId <= 0L) return null
        val user = game.optJSONObject("user")
        val url = game.optString("url").ifBlank {
            val owner = user?.optString("username").orEmpty()
            if (owner.isBlank()) ItchApi.pageUrl(gameId.toString()) else ItchApi.pageUrl("$owner/$gameId")
        }
        val traits = game.optJSONArray("traits")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }
        }.orEmpty()
        val platforms = game.optJSONObject("platforms")
        val sale = game.optJSONObject("sale")
        val rate = sale?.optDouble("rate", 0.0) ?: 0.0
        val cents = game.optLong("min_price", game.optLong("minPrice"))
        val canBuy = game.optBoolean("can_be_bought", game.optBoolean("canBeBought", "can_be_bought" in traits))
        val discount = if (rate > 0.0 && rate < 1.0) (rate * 100).roundToInt() else 0
        val price = if (canBuy && cents > 0) money(cents) else ""
        val salePrice = if (discount > 0 && price.isNotEmpty()) money((cents * (1.0 - rate)).roundToInt().toLong()) else ""
        val tags = buildList {
            if ("p_windows" in traits || platforms?.optBoolean("windows") == true) add("Windows")
            if ("p_linux" in traits || platforms?.optBoolean("linux") == true) add("Linux")
            if ("p_osx" in traits || platforms?.optBoolean("osx") == true) add("macOS")
        }.joinToString(", ")
        val cover = game.text("cover_url", "coverUrl").ifBlank { game.text("still_cover_url", "stillCoverUrl") }
        val tall = game.text("still_cover_url", "stillCoverUrl")
        val author = user?.text("display_name", "displayName").orEmpty().ifBlank { user?.optString("username").orEmpty() }
        return CatalogItem(
            store = Store.ITCH,
            id = ItchCatalog.slug(url).ifBlank { gameId.toString() },
            title = game.optString("title").ifBlank { "itch.io game $gameId" },
            imageUrl = cover.ifBlank { null },
            tallImageUrl = tall.ifBlank { null },
            tags = tags,
            isFree = price.isEmpty(),
            hasPrice = price.isNotEmpty(),
            finalPrice = salePrice.ifEmpty { price },
            originalPrice = if (salePrice.isNotEmpty()) price else "",
            discountPercent = if (salePrice.isNotEmpty()) discount else 0,
            storeUrl = url,
            developer = author,
            publisher = author,
            releaseDate = game.text("published_at", "publishedAt").take(10),
            description = cleanStoreText(game.text("short_text", "shortText")),
            owned = downloadKeyId.isNotBlank(),
            extra = if (downloadKeyId.isBlank()) emptyMap() else mapOf("downloadKeyId" to downloadKeyId, "gameId" to gameId.toString()),
        )
    }

    private fun money(cents: Long): String = "$" + (cents / 100) + "." + (cents % 100).toString().padStart(2, '0')
}
