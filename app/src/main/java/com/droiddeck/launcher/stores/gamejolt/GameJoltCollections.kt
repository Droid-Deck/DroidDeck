package com.droiddeck.launcher.stores.gamejolt

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.StoresState
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

internal class GameJoltCollection(val id: String, val title: String)

internal object GameJoltCollections {
    const val FOLLOWED = "followed"
    private const val PLAYLIST = "playlist:"

    var list by mutableStateOf<List<GameJoltCollection>>(emptyList())
        private set
    var selected by mutableStateOf<String?>(null)
        private set
    private val games = mutableStateMapOf<String, List<CatalogItem>>()
    private val loading = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var generation = 0

    fun items(): List<CatalogItem> = games.values.flatten()

    fun gamesOf(id: String): List<CatalogItem>? = games[id]

    fun chosen(): GameJoltCollection? = list.firstOrNull { it.id == selected }

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
                val cookies = GameJoltAuth.cookies(app)
                if (cookies.isBlank()) return@Thread
                val playlists = playlists(cookies) ?: return@Thread
                val parsed = listOf(GameJoltCollection(FOLLOWED, app.getString(R.string.stores_gamejolt_followed))) + parse(playlists)
                if (started != generation) return@Thread
                StoresState.post {
                    if (started != generation) return@post
                    list = parsed
                    if (selected != null && parsed.none { it.id == selected }) selected = null
                }
                StoresState.logLine("Game Jolt: ${parsed.size} collections")
                selected?.let { fetchGames(app, it, started) }
            } finally {
                loading.remove(guard)
            }
        }, "gamejolt-collections").start()
    }

    fun select(context: Context, id: String?) {
        selected = id
        if (id == null) return
        val app = context.applicationContext
        val started = generation
        Thread({ fetchGames(app, id, started) }, "gamejolt-collection-games").start()
    }

    private fun playlists(cookies: String): JSONArray? {
        val library = GameJoltApi.get("/web/library", cookies)
        if (!library.ok) return null
        library.payload?.optJSONArray("playlists")?.let { return it }
        val nested = library.payload?.optJSONArray("collections")
        if (nested != null) {
            val out = JSONArray()
            for (i in 0 until nested.length()) nested.optJSONObject(i)?.optJSONObject("playlist")?.let { out.put(it) }
            if (out.length() > 0) return out
        }
        return GameJoltApi.get("/web/library/playlists", cookies).takeIf { it.ok }?.payload?.optJSONArray("playlists") ?: JSONArray()
    }

    internal fun parse(playlists: JSONArray): List<GameJoltCollection> = (0 until playlists.length()).mapNotNull { i ->
        val playlist = playlists.optJSONObject(i) ?: return@mapNotNull null
        val id = playlist.optLong("id").takeIf { it > 0L } ?: return@mapNotNull null
        val title = playlist.optString("name").ifBlank { return@mapNotNull null }
        GameJoltCollection("$PLAYLIST$id", title)
    }

    internal fun path(id: String, username: String): String? = when {
        id == FOLLOWED -> "/web/library/games/followed/@${URLEncoder.encode(username, "UTF-8")}"
        id.startsWith(PLAYLIST) -> id.removePrefix(PLAYLIST).toLongOrNull()?.let { "/web/library/games/playlist/$it" }
        else -> null
    }

    private fun fetchGames(app: Context, id: String, started: Int) {
        val guard = "$started:$id"
        if (!loading.add(guard)) return
        try {
            val path = path(id, GameJoltAuth.username(app)) ?: return
            val found = GameJoltLibrary.games(app, path, false)
            if (found == null) {
                StoresState.logLine("Game Jolt: collection $id failed")
                return
            }
            StoresState.post { if (started == generation) games[id] = found }
            StoresState.logLine("Game Jolt: collection $id has ${found.size} games")
        } finally {
            loading.remove(guard)
        }
    }
}
