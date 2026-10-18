package com.droiddeck.launcher.frontend

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object LibraryCache {
    private const val TAG = "LibraryCache"
    private fun file(context: Context) = File(context.filesDir, "library-cache.json")

    /** The cached games and whether they are the x86-64 client's; a cache written as a bare list is arm64's. */
    @Synchronized
    fun snapshot(context: Context): Pair<List<Library.SteamGame>, Boolean> = runCatching {
        val f = file(context)
        if (!f.isFile) return Pair(emptyList(), false)
        val text = f.readText().trim()
        val (array, x64) = if (text.startsWith("{")) JSONObject(text).let { it.getJSONArray("games") to (it.optString("client") == "x86_64") }
            else JSONArray(text) to false
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            Library.SteamGame(
                appId = o.getInt("appId"), name = o.getString("name"), art = o.file("art"),
                library = o.getString("library"), gameId = o.getLong("gameId"), hero = o.file("hero"),
                lastPlayed = o.optLong("lastPlayed"), gameFiles = o.file("gameFiles"), protonPrefix = o.file("protonPrefix"),
                icon = o.file("icon"),
                source = o.optString("source").ifEmpty { if (o.getString("library") == Library.ADDED) Library.ADDED else Library.SOURCE_STEAM },
                storeId = o.optString("storeId").ifEmpty { null },
            )
        } to x64
    }.getOrElse { e -> Log.w(TAG, "unreadable, ignored: ${e.message}"); Pair(emptyList(), false) }

    fun load(context: Context): List<Library.SteamGame> = snapshot(context).first

    fun listedX64(context: Context): Boolean = snapshot(context).second

    /** Without [x64] the list keeps the client already recorded. */
    @Synchronized
    fun save(context: Context, games: List<Library.SteamGame>, x64: Boolean? = null) {
        runCatching {
            val client = x64 ?: listedX64(context)
            val array = JSONArray()
            for (g in games) array.put(
                JSONObject().put("appId", g.appId).put("name", g.name).put("library", g.library).put("gameId", g.gameId)
                    .put("lastPlayed", g.lastPlayed).putFile("art", g.art).putFile("hero", g.hero)
                    .putFile("gameFiles", g.gameFiles).putFile("protonPrefix", g.protonPrefix).putFile("icon", g.icon)
                    .put("source", g.source).apply { g.storeId?.let { put("storeId", it) } },
            )
            val f = file(context)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(JSONObject().put("client", if (client) "x86_64" else "arm64").put("games", array).toString())
            if (!tmp.renameTo(f)) {
                tmp.delete()
                f.delete()
                Log.w(TAG, "not saved: rename failed; cache dropped so the next start scans")
            }
        }.onFailure { e -> Log.w(TAG, "not saved: ${e.message}") }
    }

    private fun JSONObject.file(key: String): File? = optString(key).takeIf { it.isNotEmpty() }?.let(::File)
    private fun JSONObject.putFile(key: String, f: File?): JSONObject = if (f == null) this else put(key, f.path)
}
