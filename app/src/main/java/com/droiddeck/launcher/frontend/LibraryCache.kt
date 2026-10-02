package com.droiddeck.launcher.frontend

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The last game list the front end showed, kept in the app's files so the next start draws the
 * Steam wall on its first frame. Building the list reads every manifest and walks the added-games
 * folders, which can take a second or more; the fresh list replaces this one when it is ready.
 */
object LibraryCache {
    private const val TAG = "LibraryCache"
    private fun file(context: Context) = File(context.filesDir, "library-cache.json")

    fun load(context: Context): List<Library.SteamGame> = runCatching {
        val f = file(context)
        if (!f.isFile) return emptyList()
        val array = JSONArray(f.readText())
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            Library.SteamGame(
                appId = o.getInt("appId"), name = o.getString("name"), art = o.file("art"),
                library = o.getString("library"), gameId = o.getLong("gameId"), hero = o.file("hero"),
                lastPlayed = o.optLong("lastPlayed"), gameFiles = o.file("gameFiles"), protonPrefix = o.file("protonPrefix"),
            )
        }
    }.getOrElse { e -> Log.w(TAG, "unreadable, ignored: ${e.message}"); emptyList() }

    fun save(context: Context, games: List<Library.SteamGame>) {
        runCatching {
            val array = JSONArray()
            for (g in games) array.put(
                JSONObject().put("appId", g.appId).put("name", g.name).put("library", g.library).put("gameId", g.gameId)
                    .put("lastPlayed", g.lastPlayed).putFile("art", g.art).putFile("hero", g.hero)
                    .putFile("gameFiles", g.gameFiles).putFile("protonPrefix", g.protonPrefix),
            )
            val f = file(context)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(array.toString())
            tmp.renameTo(f)
        }.onFailure { e -> Log.w(TAG, "not saved: ${e.message}") }
    }

    private fun JSONObject.file(key: String): File? = optString(key).takeIf { it.isNotEmpty() }?.let(::File)
    private fun JSONObject.putFile(key: String, f: File?): JSONObject = if (f == null) this else put(key, f.path)
}
