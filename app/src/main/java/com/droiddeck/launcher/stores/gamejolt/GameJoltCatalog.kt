package com.droiddeck.launcher.stores.gamejolt

import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.cleanStoreText
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

internal object GameJoltCatalog {
    private const val PAID = "paid"
    private val sellableLogged = AtomicBoolean(false)

    fun items(games: JSONArray?): List<CatalogItem> {
        if (games == null) return emptyList()
        return (0 until games.length()).mapNotNull { games.optJSONObject(it)?.let(::item) }.distinctBy { it.id }
    }

    fun item(game: JSONObject): CatalogItem? {
        val id = game.optLong("id")
        if (id <= 0L) return null
        val slug = game.optString("slug")
        val sellable = game.optJSONObject("sellable")
        val paid = sellable?.optString("type") == PAID
        if (paid) noteSellable(sellable)
        val developer = game.optJSONObject("developer")
        val author = developer?.optString("display_name").orEmpty().ifBlank { developer?.optString("username").orEmpty() }
        val thumbnail = game.optJSONObject("thumbnail_media_item")?.optString("img_url").orEmpty().ifBlank { game.optString("img_thumbnail") }
        val header = game.optJSONObject("header_media_item")?.optString("img_url").orEmpty().ifBlank { game.optString("img_thumbnail") }
        val compatibility = game.optJSONObject("compatibility")
        val tags = buildList {
            if (compatibility?.optBoolean("os_windows") == true) add("Windows")
            if (compatibility?.optBoolean("os_linux") == true) add("Linux")
            if (compatibility?.optBoolean("os_mac") == true) add("macOS")
        }.joinToString(", ")
        return CatalogItem(
            store = Store.GAMEJOLT,
            id = id.toString(),
            title = game.optString("title").ifBlank { "Game Jolt game $id" },
            imageUrl = header.ifBlank { null },
            tallImageUrl = thumbnail.ifBlank { null },
            tags = tags,
            isFree = !paid,
            hasPrice = paid,
            storeUrl = GameJoltApi.pageUrl(slug.ifBlank { "game" }, id.toString()),
            developer = author,
            publisher = author,
            releaseDate = date(game.optLong("published_on")),
            description = cleanStoreText(game.optString("description")),
            mature = game.optBoolean("has_adult_content"),
            extra = mapOf("gameId" to id.toString(), "slug" to slug),
        )
    }

    private fun noteSellable(sellable: JSONObject?) {
        if (sellable == null || !sellableLogged.compareAndSet(false, true)) return
        val pricing = sellable.optJSONArray("pricings")?.optJSONObject(0)
        StoresState.logLine("Game Jolt: a paid game carries sellable keys [${sellable.keys().asSequence().joinToString(",")}] and pricing keys [${pricing?.keys()?.asSequence()?.joinToString(",").orEmpty()}]")
    }

    private fun date(stamp: Long): String {
        if (stamp <= 0L) return ""
        val millis = if (stamp > 100_000_000_000L) stamp else stamp * 1000L
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))
    }
}
