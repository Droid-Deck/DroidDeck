package com.droiddeck.launcher.frontend

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.droiddeck.launcher.stores.CredentialCipher
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object SteamGridDb {
    private const val TAG = "SteamGridDb"
    private const val API = "https://www.steamgriddb.com/api/v2"
    private const val QUERY = "nsfw=false&humor=false"

    enum class Status { OK, NO_KEY, BAD_KEY, LIMIT, OFFLINE, SERVER, NO_GAME }

    class Failed(val status: Status) : Exception(status.name)

    class Game(val id: Int, val name: String, val verified: Boolean)

    @VisibleForTesting
    @Volatile
    internal var keys: CredentialCipher.KeyProvider = CredentialCipher.Keystore

    private fun keyFile(context: Context) = File(context.filesDir, "sgdb/key.json")

    fun builtInKey(): String = runCatching {
        Class.forName("com.droiddeck.launcher.BuildConfig").getField("SGDB_API_KEY").get(null) as? String
    }.getOrNull().orEmpty()

    fun pick(user: String, builtIn: String): String = user.trim().ifEmpty { builtIn.trim() }

    fun key(context: Context): String = pick(userKey(context), builtInKey())

    fun keyChangedAt(context: Context): Long = keyFile(context).lastModified()

    @Synchronized
    fun userKey(context: Context): String {
        val f = keyFile(context)
        if (!f.isFile) return ""
        return try {
            val json = JSONObject(f.readText())
            if (CredentialCipher.isEnvelope(json)) CredentialCipher.open(json, keys) else json.optString("key")
        } catch (e: Exception) {
            if (CredentialCipher.isPermanent(e)) f.delete()
            Log.w(TAG, "the saved key cannot be read (${e.javaClass.simpleName})")
            ""
        }
    }

    @Synchronized
    fun setUserKey(context: Context, key: String) {
        val f = keyFile(context)
        val value = key.trim()
        if (value.isEmpty()) { f.delete(); return }
        f.parentFile?.mkdirs()
        val text = try {
            CredentialCipher.seal(value, keys).toString()
        } catch (e: Exception) {
            Log.w(TAG, "keystore unavailable, the key stays plain (${e.javaClass.simpleName})")
            JSONObject().put("key", value).toString()
        }
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        tmp.setReadable(false, false); tmp.setReadable(true, true)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun gameId(key: String, steamAppId: Int?, name: String): Int? {
        if (steamAppId != null) {
            json(key, "$API/games/steam/$steamAppId")?.optJSONObject("data")?.optInt("id")?.takeIf { it > 0 }?.let { return it }
        }
        val term = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val results = json(key, "$API/search/autocomplete/$term")?.optJSONArray("data") ?: return null
        return match(name, (0 until results.length()).map { results.getJSONObject(it) }.map { it.optInt("id") to it.optString("name") })
    }

    internal fun match(name: String, results: List<Pair<Int, String>>): Int? =
        results.map { (id, got) -> id to AddedGameArt.nameMatch(name, got) }
            .filter { it.second > 0 && it.first > 0 }
            .maxByOrNull { it.second }?.first

    fun download(key: String, id: Int, cache: File): Boolean {
        var got = false
        for ((path, local) in listOf(
            "grids/game/$id?dimensions=600x900&$QUERY" to "p.jpg",
            "grids/game/$id?dimensions=460x215,920x430&$QUERY" to "header.jpg",
            "heroes/game/$id?$QUERY" to "hero.jpg",
            "logos/game/$id?$QUERY" to "logo.png",
            "icons/game/$id?$QUERY" to "icon-sgdb.png",
        )) {
            val dst = File(cache, local)
            if (dst.isFile) continue
            val items = json(key, "$API/$path")?.optJSONArray("data") ?: continue
            val url = best(items, local.endsWith(".png")) ?: continue
            val bytes = AddedGameArt.get(url) ?: continue
            val tmp = File(cache, "$local.tmp")
            tmp.writeBytes(bytes)
            if (tmp.renameTo(dst)) { got = true; File(cache, "$local.src").writeText(AddedGameArt.SGDB) }
        }
        return got
    }

    fun search(key: String, term: String): List<Game> {
        val q = term.trim()
        if (q.isEmpty()) return emptyList()
        val enc = URLEncoder.encode(q, "UTF-8").replace("+", "%20")
        val data = json(key, "$API/search/autocomplete/$enc")?.optJSONArray("data") ?: return emptyList()
        return games(data)
    }

    internal fun games(data: JSONArray): List<Game> =
        (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            .filter { it.optInt("id") > 0 && it.optString("name").isNotEmpty() }
            .map { Game(it.optInt("id"), it.optString("name"), it.optBoolean("verified")) }

    internal fun path(id: Int, slot: AddedGameArt.Slot): String =
        when (slot) {
            AddedGameArt.Slot.COVER -> "grids/game/$id?dimensions=600x900&$QUERY"
            AddedGameArt.Slot.WIDE -> "grids/game/$id?dimensions=460x215,920x430&$QUERY"
            AddedGameArt.Slot.BACKGROUND -> "heroes/game/$id?$QUERY"
            AddedGameArt.Slot.LOGO -> "logos/game/$id?$QUERY"
            AddedGameArt.Slot.ICON -> "icons/game/$id?$QUERY"
        }

    fun page(key: String, id: Int, slot: AddedGameArt.Slot): List<Pair<String, String>> {
        val items = json(key, "$API/${path(id, slot)}")?.optJSONArray("data") ?: return emptyList()
        return ranked(items, pngOnly = slot == AddedGameArt.Slot.LOGO || slot == AddedGameArt.Slot.ICON)
    }

    private const val PAGE_SIZE = 50
    private const val MAX_PAGES = 4

    fun all(key: String, id: Int, slot: AddedGameArt.Slot): List<Pair<String, String>> {
        val raw = JSONArray()
        for (n in 0 until MAX_PAGES) {
            val url = "$API/${path(id, slot)}" + if (n > 0) "&page=$n" else ""
            val data: JSONArray? = try {
                json(key, url)?.optJSONArray("data")
            } catch (e: Failed) {
                if (n == 0) throw e
                null
            }
            val items = data ?: break
            for (i in 0 until items.length()) raw.put(items.get(i))
            if (items.length() < PAGE_SIZE) break
        }
        return ranked(raw, pngOnly = slot == AddedGameArt.Slot.LOGO || slot == AddedGameArt.Slot.ICON)
    }

    fun images(key: String, id: Int, slot: AddedGameArt.Slot, limit: Int): List<Pair<String, String>> =
        page(key, id, slot).take(limit)

    internal fun ranked(items: JSONArray, pngOnly: Boolean): List<Pair<String, String>> =
        (0 until items.length()).map { items.getJSONObject(it) }
            .filter { it.optString("url").startsWith("https://") && (!pngOnly || it.optString("mime") == "image/png" || it.optString("url").endsWith(".png", true)) }
            .sortedByDescending { it.optInt("score", 0) }
            .map { (it.optString("thumb").takeIf { t -> t.startsWith("https://") } ?: it.optString("url")) to it.optString("url") }

    internal fun best(items: JSONArray, pngOnly: Boolean): String? =
        (0 until items.length()).map { items.getJSONObject(it) }
            .filter { it.optString("url").startsWith("https://") && (!pngOnly || it.optString("mime") == "image/png" || it.optString("url").endsWith(".png", true)) }
            .maxByOrNull { it.optInt("score", 0) }?.optString("url")

    internal fun failure(code: Int): Status? = when {
        code == 401 || code == 403 -> Status.BAD_KEY
        code == 429 -> Status.LIMIT
        code >= 500 -> Status.SERVER
        else -> null
    }

    private fun json(key: String, url: String): JSONObject? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "DroidDeck-launcher")
        c.setRequestProperty("Authorization", "Bearer $key")
        return try {
            val code = c.responseCode
            if (code == 200) {
                JSONObject(c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)).takeIf { it.optBoolean("success", true) }
            } else {
                Log.w(TAG, "${URL(url).path}: HTTP $code")
                failure(code)?.let { throw Failed(it) }
                null
            }
        } catch (e: Failed) {
            throw e
        } catch (e: java.io.IOException) {
            Log.w(TAG, "${URL(url).path}: ${e.javaClass.simpleName}")
            throw Failed(Status.OFFLINE)
        } catch (e: Exception) {
            Log.w(TAG, "${URL(url).path}: ${e.javaClass.simpleName}"); null
        } finally {
            c.disconnect()
        }
    }
}
