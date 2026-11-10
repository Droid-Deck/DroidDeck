package com.droiddeck.launcher.frontend

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object AddedSoundtracks {
    private fun file(context: Context) = File(context.filesDir, "added-soundtracks.json")

    class Entry(val path: String, val name: String)

    @Synchronized
    fun entries(context: Context): List<Entry> = runCatching {
        val f = file(context).takeIf { it.isFile } ?: return emptyList()
        val array = JSONArray(f.readText())
        List(array.length()) { i -> array.getJSONObject(i).let { Entry(it.getString("path"), it.getString("name")) } }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun save(context: Context, list: List<Entry>): Boolean =
        runCatching {
            val array = JSONArray()
            list.forEach { array.put(JSONObject().put("path", it.path).put("name", it.name)) }
            val f = file(context)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(array.toString())
            if (tmp.renameTo(f)) true else { tmp.delete(); false }
        }.getOrDefault(false)

    fun add(context: Context, folder: File): Boolean {
        if (!folder.isDirectory || Soundtrack.audioFiles(folder).isEmpty()) return false
        return synchronized(this) {
            val list = entries(context)
            list.any { it.path == folder.path } || save(context, list + Entry(folder.path, folder.name))
        }
    }

    fun remove(context: Context, path: String) {
        synchronized(this) { save(context, entries(context).filterNot { it.path == path }) }
    }

    internal fun idOf(path: String): Int = path.hashCode() or Int.MIN_VALUE

    fun games(context: Context): List<Library.SteamGame> {
        val used = HashSet<Int>()
        return entries(context).mapNotNull { e ->
            val dir = File(e.path).takeIf { it.isDirectory } ?: return@mapNotNull null
            var id = idOf(e.path)
            while (!used.add(id)) id = (id + 1) or Int.MIN_VALUE
            game(e, dir, id)
        }
    }

    private fun game(e: Entry, dir: File, id: Int): Library.SteamGame {
        return Library.SteamGame(
            appId = id, name = e.name, art = Soundtrack.cover(dir), library = Library.ADDED, gameId = id.toLong() and 0xFFFFFFFFL,
            gameFiles = dir, source = Library.ADDED, soundtrack = true,
        )
    }
}
