package com.droiddeck.launcher.stores.itch

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.util.Xml
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreNet
import com.droiddeck.launcher.stores.StoreShelves
import com.droiddeck.launcher.stores.cleanStoreText
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

internal object ItchShelves {
    private const val TAG = "ItchShelves"
    private const val KEY = "shelves_cache"
    private const val KEY_AT = "shelves_at"
    private const val FRESH_MS = 3 * 60 * 60 * 1000L
    private const val GAP_MS = 1_200L
    private const val RETRY_MS = 20_000L

    private val FEEDS = listOf(
        "https://itch.io/games/newest.xml",
        "https://itch.io/games/on-sale.xml",
        "https://itch.io/games/price-free.xml",
        "https://itch.io/games/top-rated.xml",
    )
    private val PLATFORMS = setOf("windows", "linux", "osx", "mac", "android", "web", "vr", "mobile")

    @Volatile
    private var memory: StoreShelves? = null

    fun cached(context: Context): StoreShelves? {
        memory?.let { return it }
        val stored = stored(ItchPrefs.get(context.applicationContext))
        if (stored != null) memory = stored
        return stored
    }

    fun featured(context: Context, force: Boolean): StoreShelves? {
        val prefs = ItchPrefs.get(context.applicationContext)
        val fresh = System.currentTimeMillis() - prefs.getLong(KEY_AT, 0L) < FRESH_MS
        if (fresh && !force) {
            cached(context)?.let { return it }
        }
        val fetched = fetch()
        if (fetched != null) {
            memory = fetched
            prefs.edit().putString(KEY, ItchCatalog.shelvesJson(fetched)).putLong(KEY_AT, System.currentTimeMillis()).apply()
            return fetched
        }
        return cached(context)
    }

    fun search(context: Context, query: String): List<CatalogItem> {
        val key = ItchAuth.key(context.applicationContext)
        if (key.isBlank()) return emptyList()
        val out = ArrayList<CatalogItem>()
        for (page in 1..2) {
            val games = ItchApi.search(key, query, page) ?: break
            if (games.length() == 0) break
            for (i in 0 until games.length()) {
                val game = games.optJSONObject(i) ?: continue
                ItchLibrary.item(game, "")?.let { out.add(it) }
            }
            if (games.length() < 30) break
            Thread.sleep(GAP_MS)
        }
        return out.distinctBy { it.id }
    }

    private fun stored(prefs: SharedPreferences): StoreShelves? = ItchCatalog.shelves(prefs.getString(KEY, null))

    private fun fetch(): StoreShelves? {
        val lists = ArrayList<List<CatalogItem>>(FEEDS.size)
        var answered = false
        for ((index, url) in FEEDS.withIndex()) {
            if (index > 0) Thread.sleep(GAP_MS)
            var (code, body) = open(url)
            if (code == 429) {
                Log.w(TAG, "feed 429; backing off")
                Thread.sleep(RETRY_MS)
                val again = open(url)
                code = again.first
                body = again.second
                if (code == 429) {
                    Log.w(TAG, "feed still 429; keeping the cached shelves")
                    lists.add(emptyList())
                    break
                }
            }
            if (code in 200..299 && body != null) {
                answered = true
                lists.add(parse(body))
            } else {
                lists.add(emptyList())
            }
        }
        if (!answered) return null
        while (lists.size < FEEDS.size) lists.add(emptyList())
        val art = (lists[0] + lists[3]).mapNotNull { it.imageUrl }.distinct().take(12)
        val shelves = StoreShelves(lists[0], lists[1], lists[2], lists[3], art)
        return shelves.takeUnless { it.isEmpty }
    }

    internal fun open(url: String): Pair<Int, String?> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", StoreNet.BROWSER_UA)
                setRequestProperty("Accept", "application/xml, text/xml, application/rss+xml, */*")
            }
            val code = conn.responseCode
            code to if (code in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else null
        } catch (e: Exception) {
            Log.w(TAG, "feed ${e.javaClass.simpleName}")
            0 to null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    internal fun parse(xml: String): List<CatalogItem> {
        val out = ArrayList<CatalogItem>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))
        var event = parser.eventType
        var field: String? = null
        var current: HashMap<String, String>? = null
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "item") current = HashMap()
                    field = parser.name
                }
                XmlPullParser.TEXT -> {
                    val map = current
                    val name = field
                    if (map != null && name != null) map[name] = (map[name] ?: "") + parser.text
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "item") {
                        current?.let { item(it)?.let(out::add) }
                        current = null
                    }
                    field = null
                }
            }
            event = parser.next()
        }
        return out
    }

    internal fun item(m: Map<String, String>): CatalogItem? {
        val link = m["link"]?.trim().orEmpty().ifBlank { m["guid"]?.trim().orEmpty() }
        if (link.isBlank()) return null
        val title = m["plainTitle"]?.trim().orEmpty()
            .ifBlank { m["title"].orEmpty().substringBefore(" [").trim() }
            .ifBlank { ItchCatalog.slug(link) }
        val price = m["price"]?.trim().orEmpty()
        val free = price.isBlank() || price == "$0.00" || price == "0.00" || price.equals("free", ignoreCase = true)
        val tags = Regex("\\[([^]]+)]").findAll(m["title"].orEmpty())
            .map { it.groupValues[1].trim() }
            .filterNot { it.endsWith("% Off") || it.startsWith("$") || it.equals("Free", true) || it.lowercase() in PLATFORMS }
            .joinToString(", ")
        return CatalogItem(
            store = Store.ITCH,
            id = ItchCatalog.slug(link),
            title = title,
            imageUrl = m["imageurl"]?.trim()?.ifBlank { null },
            tags = tags,
            isFree = free,
            hasPrice = !free,
            finalPrice = if (free) "" else price,
            originalPrice = if (free) "" else m["fullPrice"]?.trim().orEmpty(),
            discountPercent = m["discountpercent"]?.trim()?.toIntOrNull() ?: 0,
            storeUrl = link,
            releaseDate = date(m["pubDate"]),
            description = cleanStoreText(m["description"]),
        )
    }

    private fun date(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return ""
        return runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(text)
        }.getOrNull()?.let { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(it) }.orEmpty()
    }
}
