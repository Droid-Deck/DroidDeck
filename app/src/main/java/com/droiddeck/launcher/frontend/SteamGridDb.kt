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

/**
 * SteamGridDB, the art source after Steam's store: a game by its Steam appid, else by a search
 * whose result must carry the same name, then its best-rated grids, heroes, logos and icons.
 *
 * The API key: the user's own (Steam settings › Games), sealed with [CredentialCipher] in the
 * app's files, else the one the build carries (`BuildConfig.SGDB_API_KEY`, from a CI secret), else
 * none, and then this step is skipped. The key travels only in the Authorization header and is
 * never logged.
 */
object SteamGridDb {
    private const val TAG = "SteamGridDb"
    private const val API = "https://www.steamgriddb.com/api/v2"
    private const val QUERY = "nsfw=false&humor=false"

    @VisibleForTesting
    @Volatile
    internal var keys: CredentialCipher.KeyProvider = CredentialCipher.Keystore

    private fun keyFile(context: Context) = File(context.filesDir, "sgdb/key.json")

    /** The key the build carries, or "" (a build without the secret, a fork's PR build). */
    fun builtInKey(): String = runCatching {
        Class.forName("com.droiddeck.launcher.BuildConfig").getField("SGDB_API_KEY").get(null) as? String
    }.getOrNull().orEmpty()

    /** The user's key wins, then the build's; "" when there is neither. */
    fun pick(user: String, builtIn: String): String = user.trim().ifEmpty { builtIn.trim() }

    fun key(context: Context): String = pick(userKey(context), builtInKey())

    /** The key the user entered, or "". */
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

    /** Saves the user's key sealed (plain only where the Keystore does not work); "" clears it. */
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

    /** The SteamGridDB game id for [steamAppId], else for a search on [name] that returns the same name; null when neither. */
    fun gameId(key: String, steamAppId: Int?, name: String): Int? {
        if (steamAppId != null) {
            json(key, "$API/games/steam/$steamAppId")?.optJSONObject("data")?.optInt("id")?.takeIf { it > 0 }?.let { return it }
        }
        val term = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val results = json(key, "$API/search/autocomplete/$term")?.optJSONArray("data") ?: return null
        return match(name, (0 until results.length()).map { results.getJSONObject(it) }.map { it.optInt("id") to it.optString("name") })
    }

    /** The id of the first result whose name matches [name] ([AddedGameArt.nameMatch]); no blind first pick. */
    internal fun match(name: String, results: List<Pair<Int, String>>): Int? =
        results.map { (id, got) -> id to AddedGameArt.nameMatch(name, got) }
            .filter { it.second > 0 && it.first > 0 }
            .maxByOrNull { it.second }?.first

    /**
     * Fetches what is missing into [cache] under the names Steam's art uses there (p.jpg,
     * header.jpg, hero.jpg, logo.png) and the icon as icon-sgdb.png. True when anything arrived.
     */
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

    /** The best-scored [limit] images for [slot] of game [id], as (thumbnail, full image) URLs. */
    fun images(key: String, id: Int, slot: AddedGameArt.Slot, limit: Int): List<Pair<String, String>> {
        val path = when (slot) {
            AddedGameArt.Slot.COVER -> "grids/game/$id?dimensions=600x900&$QUERY"
            AddedGameArt.Slot.BACKGROUND -> "heroes/game/$id?$QUERY"
            AddedGameArt.Slot.LOGO -> "logos/game/$id?$QUERY"
            AddedGameArt.Slot.ICON -> "icons/game/$id?$QUERY"
        }
        val items = json(key, "$API/$path")?.optJSONArray("data") ?: return emptyList()
        return ranked(items, pngOnly = slot == AddedGameArt.Slot.LOGO || slot == AddedGameArt.Slot.ICON).take(limit)
    }

    /** [items] best-scored first, as (thumbnail, full image) URLs; for a .png slot only PNGs. */
    internal fun ranked(items: JSONArray, pngOnly: Boolean): List<Pair<String, String>> =
        (0 until items.length()).map { items.getJSONObject(it) }
            .filter { it.optString("url").startsWith("https://") && (!pngOnly || it.optString("mime") == "image/png" || it.optString("url").endsWith(".png", true)) }
            .sortedByDescending { it.optInt("score", 0) }
            .map { (it.optString("thumb").takeIf { t -> t.startsWith("https://") } ?: it.optString("url")) to it.optString("url") }

    /** The highest-scored image's URL; for a .png slot only a PNG. */
    internal fun best(items: JSONArray, pngOnly: Boolean): String? =
        (0 until items.length()).map { items.getJSONObject(it) }
            .filter { it.optString("url").startsWith("https://") && (!pngOnly || it.optString("mime") == "image/png" || it.optString("url").endsWith(".png", true)) }
            .maxByOrNull { it.optInt("score", 0) }?.optString("url")

    /** A GET with the key in its Authorization header; the body as JSON, or null. Logs the path only. */
    private fun json(key: String, url: String): JSONObject? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "DroidDeck-launcher")
        c.setRequestProperty("Authorization", "Bearer $key")
        return try {
            if (c.responseCode != 200) { Log.w(TAG, "${URL(url).path}: HTTP ${c.responseCode}"); null }
            else JSONObject(c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)).takeIf { it.optBoolean("success", true) }
        } catch (e: Exception) {
            Log.w(TAG, "${URL(url).path}: ${e.javaClass.simpleName}"); null
        } finally {
            c.disconnect()
        }
    }
}
