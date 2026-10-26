package com.droiddeck.launcher.stores.itch

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.StoresState
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

internal class ItchCollection(val id: String, val title: String, val count: Int)

internal object ItchCollections {
    private const val MAX_PAGES = 20

    var list by mutableStateOf<List<ItchCollection>>(emptyList())
        private set
    var selected by mutableStateOf<String?>(null)
        private set
    private val games = mutableStateMapOf<String, List<CatalogItem>>()
    private val loading = ConcurrentHashMap.newKeySet<String>()

    fun items(): List<CatalogItem> = games.values.flatten()

    fun gamesOf(id: String): List<CatalogItem>? = games[id]

    fun chosen(): ItchCollection? = list.firstOrNull { it.id == selected }

    fun load(context: Context) {
        val app = context.applicationContext
        if (!loading.add("")) return
        Thread({
            try {
                val key = ItchAuth.key(app)
                if (key.isBlank()) return@Thread
                val parsed = parseCollections(ItchApi.collections(key) ?: return@Thread)
                StoresState.post {
                    list = parsed
                    if (selected != null && parsed.none { it.id == selected }) selected = null
                }
                StoresState.logLine("itch.io: ${parsed.size} collections")
                selected?.let { fetchGames(app, key, it) }
            } finally {
                loading.remove("")
            }
        }, "itch-collections").start()
    }

    fun select(context: Context, id: String?) {
        selected = id
        if (id == null || games.containsKey(id)) return
        val app = context.applicationContext
        Thread({
            val key = ItchAuth.key(app)
            if (key.isNotBlank()) fetchGames(app, key, id)
        }, "itch-collection-games").start()
    }

    private fun fetchGames(context: Context, key: String, id: String) {
        if (!loading.add(id)) return
        try {
            val out = ArrayList<CatalogItem>()
            var page = 1
            while (page <= MAX_PAGES) {
                val body = ItchApi.collectionGames(key, id, page)
                if (body == null) {
                    StoresState.logLine("itch.io: collection $id page $page failed")
                    if (page == 1) return
                    break
                }
                val batch = parseGames(body)
                if (batch.isEmpty()) break
                out.addAll(batch)
                page++
            }
            val distinct = out.distinctBy { it.id }
            StoresState.post { games[id] = distinct }
            StoresState.logLine("itch.io: collection $id has ${distinct.size} games")
        } finally {
            loading.remove(id)
        }
    }

    internal fun parseCollections(array: JSONArray): List<ItchCollection> = (0 until array.length()).mapNotNull { i ->
        val c = array.optJSONObject(i) ?: return@mapNotNull null
        val id = c.opt("id")?.toString().orEmpty().takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
        val title = c.optString("title").ifBlank { return@mapNotNull null }
        ItchCollection(id, title, c.optInt("games_count", c.optInt("gamesCount")))
    }

    internal fun parseGames(body: JSONObject): List<CatalogItem> {
        val rows = body.optJSONArray("collection_games") ?: body.optJSONArray("collectionGames") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { i ->
            rows.optJSONObject(i)?.optJSONObject("game")?.let { ItchLibrary.item(it, "") }
        }
    }
}
