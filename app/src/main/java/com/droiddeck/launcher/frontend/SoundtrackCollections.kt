package com.droiddeck.launcher.frontend

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

object SoundtrackCollections {
    const val FAVORITES_ID = 2_000_000_000

    class TrackRef(val path: String, val title: String, val artist: String, val album: String, val durationMs: Long, val cover: String = "")
    class Collection(val id: Int, val name: String, val tracks: List<TrackRef>)
    class Snapshot(val favorites: List<TrackRef>, val collections: List<Collection>)

    private val state = mutableStateOf(Snapshot(emptyList(), emptyList()))
    private var loaded = false
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "soundtrack-collections").apply { isDaemon = true } }

    val snapshot: Snapshot get() = state.value

    private fun file(context: Context) = File(context.filesDir, "soundtrack-collections.json")

    internal fun toggled(list: List<TrackRef>, ref: TrackRef): List<TrackRef> =
        if (list.any { it.path == ref.path }) list.filterNot { it.path == ref.path } else list + ref

    internal fun merged(list: List<TrackRef>, add: List<TrackRef>): List<TrackRef> {
        val have = list.mapTo(HashSet()) { it.path }
        return list + add.filter { have.add(it.path) }
    }

    internal fun cleanName(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")

    internal fun nameTaken(collections: List<Collection>, name: String, except: Int? = null): Boolean =
        collections.any { it.id != except && it.name.equals(name, ignoreCase = true) }

    internal fun nextId(collections: List<Collection>): Int =
        (collections.maxOfOrNull { it.id } ?: FAVORITES_ID).coerceAtLeast(FAVORITES_ID) + 1

    private fun encode(s: Snapshot): String {
        fun refs(list: List<TrackRef>) = JSONArray().also { array ->
            list.forEach {
                array.put(
                    JSONObject().put("path", it.path).put("title", it.title).put("artist", it.artist).put("album", it.album)
                        .put("duration", it.durationMs).put("cover", it.cover),
                )
            }
        }
        val collections = JSONArray()
        s.collections.forEach { collections.put(JSONObject().put("id", it.id).put("name", it.name).put("tracks", refs(it.tracks))) }
        return JSONObject().put("favorites", refs(s.favorites)).put("collections", collections).toString()
    }

    private fun decode(text: String): Snapshot {
        fun refs(array: JSONArray?): List<TrackRef> = if (array == null) emptyList() else List(array.length()) { i ->
            array.getJSONObject(i).let {
                TrackRef(it.getString("path"), it.optString("title"), it.optString("artist"), it.optString("album"), it.optLong("duration"), it.optString("cover"))
            }
        }
        val root = JSONObject(text)
        val collections = root.optJSONArray("collections")
        return Snapshot(
            refs(root.optJSONArray("favorites")),
            if (collections == null) emptyList() else List(collections.length()) { i ->
                collections.getJSONObject(i).let { Collection(it.getInt("id"), it.getString("name"), refs(it.optJSONArray("tracks"))) }
            },
        )
    }

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val f = file(context)
        if (f.isFile) runCatching { state.value = decode(f.readText()) }
    }

    private fun commit(context: Context, next: Snapshot) {
        state.value = next
        val f = file(context)
        val json = encode(next)
        writer.execute {
            runCatching {
                val tmp = File(f.path + ".tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(f)) tmp.delete()
            }
        }
    }

    fun tracksOf(id: Int): List<TrackRef> =
        if (id == FAVORITES_ID) snapshot.favorites else snapshot.collections.firstOrNull { it.id == id }?.tracks.orEmpty()

    fun isFavorite(path: String): Boolean = snapshot.favorites.any { it.path == path }

    @Synchronized
    fun toggleFavorite(context: Context, ref: TrackRef) {
        load(context)
        val s = snapshot
        commit(context, Snapshot(toggled(s.favorites, ref), s.collections))
    }

    @Synchronized
    fun create(context: Context, rawName: String, tracks: List<TrackRef>): Int? {
        load(context)
        val s = snapshot
        val name = cleanName(rawName)
        if (name.isEmpty() || nameTaken(s.collections, name)) return null
        val id = nextId(s.collections)
        commit(context, Snapshot(s.favorites, s.collections + Collection(id, name, merged(emptyList(), tracks))))
        return id
    }

    @Synchronized
    fun rename(context: Context, id: Int, rawName: String): Boolean {
        load(context)
        val s = snapshot
        val name = cleanName(rawName)
        if (id == FAVORITES_ID || name.isEmpty() || nameTaken(s.collections, name, id)) return false
        commit(context, Snapshot(s.favorites, s.collections.map { if (it.id == id) Collection(id, name, it.tracks) else it }))
        return true
    }

    @Synchronized
    fun delete(context: Context, id: Int) {
        load(context)
        val s = snapshot
        if (id == FAVORITES_ID) return
        commit(context, Snapshot(s.favorites, s.collections.filterNot { it.id == id }))
    }

    @Synchronized
    fun add(context: Context, id: Int, tracks: List<TrackRef>) {
        load(context)
        val s = snapshot
        if (id == FAVORITES_ID) commit(context, Snapshot(merged(s.favorites, tracks), s.collections))
        else commit(context, Snapshot(s.favorites, s.collections.map { if (it.id == id) Collection(id, it.name, merged(it.tracks, tracks)) else it }))
    }

    @Synchronized
    fun remove(context: Context, id: Int, paths: Set<String>) {
        load(context)
        val s = snapshot
        if (id == FAVORITES_ID) commit(context, Snapshot(s.favorites.filterNot { it.path in paths }, s.collections))
        else commit(context, Snapshot(s.favorites, s.collections.map { if (it.id == id) Collection(id, it.name, it.tracks.filterNot { t -> t.path in paths }) else it }))
    }

    private fun coverOf(tracks: List<TrackRef>): File? =
        tracks.firstNotNullOfOrNull { t -> t.cover.takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.isFile } }

    private fun game(id: Int, name: String, tracks: List<TrackRef>): Library.SteamGame = Library.SteamGame(
        appId = id, name = name, art = coverOf(tracks), library = Library.ADDED, gameId = id.toLong(),
        source = if (id == FAVORITES_ID) Library.SOURCE_FAVORITES else Library.SOURCE_COLLECTION, soundtrack = true,
    )

    fun games(favoritesLabel: String): List<Library.SteamGame> {
        val s = snapshot
        return listOf(game(FAVORITES_ID, favoritesLabel, s.favorites)) + s.collections.map { game(it.id, it.name, it.tracks) }
    }

    fun album(id: Int): Soundtrack.Album {
        val tracks = tracksOf(id).filter { File(it.path).isFile }.mapIndexed { i, t ->
            Soundtrack.Track(File(t.path), t.title, 1, i + 1, t.durationMs, t.artist)
        }
        return Soundtrack.Album(mapOf(1 to tracks), "", null)
    }
}
