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
    var list by mutableStateOf<List<ItchCollection>>(emptyList())
        private set
    var selected by mutableStateOf<String?>(null)
        private set
    private val games = mutableStateMapOf<String, List<CatalogItem>>()
    private val loading = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var generation = 0

    fun items(): List<CatalogItem> = games.values.flatten()

    fun gamesOf(id: String): List<CatalogItem>? = games[id]

    fun chosen(): ItchCollection? = list.firstOrNull { it.id == selected }

    fun clear() {
        generation++
        StoresState.post {
            list = emptyList()
            selected = null
            games.clear()
        }
    }

    fun load(context: Context) {
        val app = context.applicationContext
        val started = generation
        val guard = "$started:"
        if (!loading.add(guard)) return
        Thread({
            try {
                val key = ItchAuth.key(app)
                if (key.isBlank()) return@Thread
                val parsed = parseCollections(ItchApi.collections(key) ?: return@Thread)
                if (started != generation) return@Thread
                StoresState.post {
                    if (started != generation) return@post
                    list = parsed
                    if (selected != null && parsed.none { it.id == selected }) selected = null
                }
                StoresState.logLine("itch.io: ${parsed.size} collections")
                selected?.let { fetchGames(key, it, started) }
            } finally {
                loading.remove(guard)
            }
        }, "itch-collections").start()
    }

    fun select(context: Context, id: String?) {
        selected = id
        if (id == null) return
        val app = context.applicationContext
        val started = generation
        Thread({
            val key = ItchAuth.key(app)
            if (key.isNotBlank()) fetchGames(key, id, started)
        }, "itch-collection-games").start()
    }

    private fun fetchGames(key: String, id: String, started: Int) {
        val guard = "$started:$id"
        if (!loading.add(guard)) return
        try {
            val out = ArrayList<CatalogItem>()
            val seen = HashSet<String>()
            var page = 1
            while (true) {
                val body = ItchApi.collectionGames(key, id, page)
                if (body == null) {
                    StoresState.logLine("itch.io: collection $id page $page failed")
                    return
                }
                val batch = parseGames(body)
                if (batch.isEmpty()) break
                val before = out.size
                out.addAll(batch.filter { seen.add(it.id) })
                if (out.size == before) break
                page++
            }
            val distinct = out.toList()
            StoresState.post { if (started == generation) games[id] = distinct }
            StoresState.logLine("itch.io: collection $id has ${distinct.size} games")
        } finally {
            loading.remove(guard)
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
