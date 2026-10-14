package com.droiddeck.launcher.stores.amazon

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.stores.StoreNet
import com.droiddeck.launcher.stores.StoreShelves
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object AmazonShelves {
    private const val TAG = "AmazonShelves"
    private const val PAGE = "https://luna.amazon.com/claims/home"
    private const val GRAPHQL = "https://luna.amazon.com/graphql"
    private const val KEY_ART = "store_art"
    private const val KEY_ART_AT = "store_art_at"
    private const val FRESH_MS = 3 * 60 * 60 * 1000L
    private const val QUERY = "query { items(collectionType: FEATURED_CONTENT, pageSize: 50) { items { assets { title " +
        "cardMedia { defaultMedia { src1x src2x } } } } } }"

    @Volatile private var memory: StoreShelves? = null

    fun cached(context: Context): StoreShelves? =
        memory ?: fromPrefs(AmazonPrefs.get(context).getString(KEY_ART, null))?.also { memory = it }

    fun featured(context: Context, force: Boolean): StoreShelves? {
        val prefs = AmazonPrefs.get(context)
        if (!force && System.currentTimeMillis() - prefs.getLong(KEY_ART_AT, 0L) in 0 until FRESH_MS) cached(context)?.let { return it }
        val art = runCatching { fetch() }.onFailure { Log.w(TAG, "featured: ${it.javaClass.simpleName}") }.getOrNull().orEmpty()
        if (art.isEmpty()) return cached(context)
        prefs.edit().putString(KEY_ART, JSONArray(art).toString()).putLong(KEY_ART_AT, System.currentTimeMillis()).apply()
        Log.i(TAG, "featured art: ${art.size}")
        return StoreShelves(art = art).also { memory = it }
    }

    private fun fromPrefs(text: String?): StoreShelves? {
        val a = runCatching { JSONArray(text ?: return null) }.getOrNull() ?: return null
        val art = List(a.length()) { a.optString(it) }.filter { it.isNotEmpty() }
        return if (art.isEmpty()) null else StoreShelves(art = art)
    }

    private fun fetch(): List<String> {
        val (page, cookies) = openPage() ?: return emptyList()
        val token = Regex("""name=['"]csrf-key['"] value=['"]([^'"]+)['"]""").find(page)?.groupValues?.get(1) ?: run {
            Log.w(TAG, "claims page had no csrf key")
            return emptyList()
        }
        val body = StoreNet.postJson(
            GRAPHQL, JSONObject().put("query", QUERY).toString(),
            headers = mapOf("client-id" to "CarboniteApp", "csrf-token" to token, "Cookie" to cookies, "Origin" to "https://luna.amazon.com", "Referer" to PAGE),
        ) ?: return emptyList()
        val items = JSONObject(body).optJSONObject("data")?.optJSONObject("items")?.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val media = items.optJSONObject(i)?.optJSONObject("assets")?.optJSONObject("cardMedia")?.optJSONObject("defaultMedia")
            media?.optString("src2x")?.takeIf { it.isNotEmpty() } ?: media?.optString("src1x")?.takeIf { it.isNotEmpty() }
        }.distinct()
    }

    private fun openPage(): Pair<String, String>? {
        val jar = LinkedHashMap<String, String>()
        var url = PAGE
        repeat(5) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 20_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", StoreNet.BROWSER_UA)
                setRequestProperty("Accept", "text/html")
                if (jar.isNotEmpty()) setRequestProperty("Cookie", jar.entries.joinToString("; ") { "${it.key}=${it.value}" })
            }
            try {
                val code = conn.responseCode
                conn.headerFields["Set-Cookie"].orEmpty().forEach { c ->
                    val pair = c.substringBefore(';')
                    val name = pair.substringBefore('=').trim()
                    if (name.isNotEmpty() && '=' in pair) jar[name] = pair.substringAfter('=')
                }
                if (code in 300..399) {
                    url = URL(URL(url), conn.getHeaderField("Location") ?: return null).toString()
                    return@repeat
                }
                if (code !in 200..299) { Log.w(TAG, "claims page $code"); return null }
                val page = conn.inputStream.bufferedReader().use { it.readText() }
                return page to jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
            } finally {
                runCatching { conn.disconnect() }
            }
        }
        return null
    }
}
