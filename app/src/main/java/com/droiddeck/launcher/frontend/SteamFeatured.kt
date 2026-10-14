package com.droiddeck.launcher.frontend

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.stores.StoreNet
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object SteamFeatured {
    class Game(val appId: Int, val name: String, val art: String)

    private const val TAG = "SteamFeatured"
    private const val PREFS = "steam_featured"
    private const val KEY_GAMES = "games_v2"
    private const val KEY_AT = "at"
    private const val FRESH_MS = 6 * 60 * 60 * 1000L
    private val SECTIONS = listOf("top_sellers", "new_releases", "specials")
    private const val ASSET_ROOT = "https://shared.akamai.steamstatic.com/store_item_assets/"

    private val HIDDEN_DESCRIPTORS = setOf(1, 3, 4)

    var games by mutableStateOf<List<Game>>(emptyList())
        private set
    private val loading = AtomicBoolean(false)

    fun load(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (games.isEmpty()) parse(prefs.getString(KEY_GAMES, null))?.let { games = it }
        if (System.currentTimeMillis() - prefs.getLong(KEY_AT, 0L) in 0 until FRESH_MS && games.isNotEmpty()) return
        if (!loading.compareAndSet(false, true)) return
        Thread({
            try {
                val cc = Locale.getDefault().country.takeIf { it.length == 2 } ?: "US"
                val body = StoreNet.get("https://store.steampowered.com/api/featuredcategories?cc=$cc&l=english") ?: return@Thread
                val root = JSONObject(body)
                val ids = LinkedHashSet<Int>()
                for (section in SECTIONS) {
                    val items = root.optJSONObject(section)?.optJSONArray("items") ?: continue
                    for (i in 0 until items.length()) {
                        val o = items.optJSONObject(i) ?: continue
                        val id = o.optInt("id")
                        if (id > 0 && o.optInt("type", 0) == 0) ids += id
                    }
                }
                val list = withArt(ids, cc)
                if (list.isEmpty()) return@Thread
                prefs.edit().putString(KEY_GAMES, JSONArray(list.map { JSONObject().put("id", it.appId).put("name", it.name).put("art", it.art) }).toString())
                    .putLong(KEY_AT, System.currentTimeMillis()).apply()
                android.os.Handler(android.os.Looper.getMainLooper()).post { games = list }
                Log.i(TAG, "featured games: ${list.size}")
            } catch (e: Exception) {
                Log.w(TAG, "featured: ${e.javaClass.simpleName}")
            } finally {
                loading.set(false)
            }
        }, "steam-featured").start()
    }

    private fun withArt(ids: Collection<Int>, cc: String): List<Game> {
        if (ids.isEmpty()) return emptyList()
        val input = JSONObject()
            .put("ids", JSONArray(ids.map { JSONObject().put("appid", it) }))
            .put("context", JSONObject().put("language", "english").put("country_code", cc))
            .put("data_request", JSONObject().put("include_assets", true))
        val body = StoreNet.get(
            "https://api.steampowered.com/IStoreBrowseService/GetItems/v1/?input_json=" + java.net.URLEncoder.encode(input.toString(), "UTF-8"),
        ) ?: return emptyList()
        val items = JSONObject(body).optJSONObject("response")?.optJSONArray("store_items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val o = items.optJSONObject(i) ?: return@mapNotNull null
            val descriptors = o.optJSONArray("content_descriptorids")
            if (descriptors != null && (0 until descriptors.length()).any { descriptors.optInt(it) in HIDDEN_DESCRIPTORS }) return@mapNotNull null
            val assets = o.optJSONObject("assets") ?: return@mapNotNull null
            val format = assets.optString("asset_url_format").takeIf { "\${FILENAME}" in it } ?: return@mapNotNull null
            val file = listOf("library_capsule_2x", "library_capsule").firstNotNullOfOrNull { k -> assets.optString(k).takeIf { it.isNotEmpty() } }
                ?: return@mapNotNull null
            Game(o.optInt("appid"), o.optString("name"), ASSET_ROOT + format.replace("\${FILENAME}", file))
        }
    }

    private fun parse(text: String?): List<Game>? {
        val a = runCatching { JSONArray(text ?: return null) }.getOrNull() ?: return null
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Game(it.optInt("id"), it.optString("name"), it.optString("art")) }
            .filter { it.appId > 0 && it.art.isNotEmpty() }.takeIf { it.isNotEmpty() }
    }
}
