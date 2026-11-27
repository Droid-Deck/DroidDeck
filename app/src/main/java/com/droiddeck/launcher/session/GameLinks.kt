package com.droiddeck.launcher.session

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object GameLinks {
    enum class Kind { WIKI, STRATEGYWIKI, PCGAMINGWIKI, GAMEFAQS, IGN, HOWLONGTOBEAT, MAPGENIE }

    data class Link(val kind: Kind, val url: String)

    data class Search(val id: String, val url: String)

    internal fun sparql(appId: Long): String = """
        SELECT ?fandom ?pcgw ?gamefaqs ?ign ?hltb ?sw WHERE {
          ?g wdt:P1733 "$appId".
          OPTIONAL { ?g wdt:P4073 ?fandom }
          OPTIONAL { ?g wdt:P6337 ?pcgw }
          OPTIONAL { ?g wdt:P4769 ?gamefaqs }
          OPTIONAL { ?g wdt:P5385 ?ign }
          OPTIONAL { ?g wdt:P2816 ?hltb }
          OPTIONAL { ?g wdt:P9075 ?sw }
        } LIMIT 50
    """.trimIndent()

    internal fun parse(json: String): List<Link> {
        val rows = JSONObject(json).optJSONObject("results")?.optJSONArray("bindings") ?: return emptyList()
        val values = HashMap<String, MutableList<String>>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            for (key in row.keys()) {
                val v = row.optJSONObject(key)?.optString("value").orEmpty()
                if (v.isNotEmpty() && v !in values.getOrPut(key) { mutableListOf() }) values.getValue(key) += v
            }
        }
        fun first(key: String) = values[key]?.firstOrNull()
        // Wikidata is editable by anyone, and this id becomes a URL host.
        val fandomId = Regex("[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)?")
        val fandom = values["fandom"]?.filter(fandomId::matches)?.let { all -> all.firstOrNull { '.' !in it } ?: all.firstOrNull() }
        return listOfNotNull(
            fandom?.let { id -> if ('.' in id) id.split('.', limit = 2).let { (lang, wiki) -> "https://$wiki.fandom.com/$lang/" } else "https://$id.fandom.com/" }
                ?.let { Link(Kind.WIKI, it) },
            first("sw")?.let { Link(Kind.STRATEGYWIKI, "https://strategywiki.org/wiki/" + path(it)) },
            first("pcgw")?.let { Link(Kind.PCGAMINGWIKI, "https://www.pcgamingwiki.com/wiki/" + path(it)) },
            first("gamefaqs")?.takeIf { it.all(Char::isDigit) }?.let { Link(Kind.GAMEFAQS, "https://gamefaqs.gamespot.com/-/$it-") },
            first("ign")?.let { Link(Kind.IGN, "https://www.ign.com/games/" + path(it)) },
            first("hltb")?.takeIf { it.all(Char::isDigit) }?.let { Link(Kind.HOWLONGTOBEAT, "https://howlongtobeat.com/game/$it") },
        )
    }

    /** Site slugs to try for a game name, longest first: "Bully: Scholarship Edition" gives bully-scholarship-edition, then bully. */
    internal fun slugs(name: String): List<String> {
        fun slug(s: String) = s.lowercase().replace("&", "and").replace(Regex("['’™®©]"), "")
            .replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val short = name.split(Regex(":| - | – |\\("), limit = 2).first()
        return listOf(slug(name), slug(short)).filter { it.isNotEmpty() }.distinct()
    }

    /** Direct pages for what Wikidata has no ID for, found from each site's own routes rather than a web search. */
    internal fun direct(name: String, found: List<Link>, exists: (String) -> Boolean, strategyWiki: (String) -> String?): List<Link> =
        merge(found, discover(name, found, exists, strategyWiki))

    /** Wikidata's links with the discovered ones; a discovered IGN wiki replaces Wikidata's IGN game page. */
    internal fun merge(found: List<Link>, extra: List<Link>): List<Link> =
        found.filterNot { it.kind == Kind.IGN && extra.any { e -> e.kind == Kind.IGN } } +
            extra.filterNot { e -> e.kind != Kind.IGN && found.any { it.kind == e.kind } }

    internal fun discover(name: String, found: List<Link>, exists: (String) -> Boolean, strategyWiki: (String) -> String?): List<Link> {
        val have = found.map { it.kind }.toSet()
        val slugs = slugs(name)
        fun first(urls: List<String>) = urls.firstOrNull(exists)
        val ignWiki = found.firstOrNull { it.kind == Kind.IGN }?.url?.substringAfter("/games/", "")?.takeIf { it.isNotEmpty() }
        val extra = listOfNotNull(
            first(slugs.map { "https://mapgenie.io/$it" })?.let { Link(Kind.MAPGENIE, it) },
            first((listOfNotNull(ignWiki) + slugs).distinct().map { "https://www.ign.com/wikis/$it" })?.let { Link(Kind.IGN, it) },
            // Fandom answers pages with a bot check, but its API with 200 for a wiki that exists and 404 for one that does not.
            if (Kind.WIKI in have) null else slugs.map { it.replace("-", "") }.distinct()
                .firstOrNull { exists("https://$it.fandom.com/api.php?action=query&meta=siteinfo&format=json") }
                ?.let { Link(Kind.WIKI, "https://$it.fandom.com/") },
            if (Kind.STRATEGYWIKI in have) null else strategyWiki(name.split(":").first().trim())?.let { Link(Kind.STRATEGYWIKI, it) },
        )
        return extra
    }

    internal fun parseOpenSearch(json: String, title: String): String? = runCatching {
        val a = JSONArray(json)
        val titles = a.getJSONArray(1)
        val urls = a.getJSONArray(3)
        (0 until titles.length()).firstOrNull { titles.getString(it).equals(title, ignoreCase = true) }?.let { urls.getString(it) }
    }.getOrNull()

    /** Whether the page exists, or null when the site could not be asked. */
    private fun exists(url: String): Boolean? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 6_000
        c.readTimeout = 6_000
        c.setRequestProperty("User-Agent", BROWSER)
        try { c.responseCode.takeIf { it < 500 }?.let { it in 200..399 } } finally { c.disconnect() }
    }.getOrNull()

    private fun strategyWiki(title: String): Result<String?> = runCatching {
        parseOpenSearch(get("https://strategywiki.org/w/api.php?action=opensearch&namespace=0&limit=5&format=json&search=" +
            URLEncoder.encode(title, "UTF-8")), title)
    }

    fun links(context: Context, appId: Long, name: String? = null): List<Link> {
        val cache = File(context.cacheDir, "game-links/$appId.json")
        val fresh = cache.isFile && System.currentTimeMillis() - cache.lastModified() < CACHE_MS
        val text = if (fresh) runCatching { cache.readText() }.getOrNull() else null
        val fetched = text ?: runCatching {
            get("https://query.wikidata.org/sparql?format=json&query=" + URLEncoder.encode(sparql(appId), "UTF-8"))
        }.getOrNull()?.also { runCatching { cache.parentFile?.mkdirs(); cache.writeText(it) } }
        val json = fetched ?: runCatching { cache.readText() }.getOrNull()
        val wikidata = json?.let { runCatching { parse(it) }.getOrNull() }.orEmpty()
        val withFixes = if (wikidata.any { it.kind == Kind.PCGAMINGWIKI }) wikidata
            else wikidata + Link(Kind.PCGAMINGWIKI, "https://www.pcgamingwiki.com/api/appid.php?appid=$appId")
        if (name.isNullOrBlank()) return withFixes
        // Only the discovered pages are cached, and only when every site answered, so an offline lookup is retried.
        val directCache = File(context.cacheDir, "game-links/$appId-direct-v3.json")
        val cached = directCache.takeIf { it.isFile && System.currentTimeMillis() - it.lastModified() < CACHE_MS }
            ?.let { runCatching { readLinks(it.readText()) }.getOrNull() }
        if (cached != null) return merge(withFixes, cached)
        var complete = fetched != null
        val extra = discover(name, withFixes, { url -> exists(url).also { if (it == null) complete = false } == true }) { title ->
            strategyWiki(title).onFailure { complete = false }.getOrNull()
        }
        if (complete) runCatching { directCache.parentFile?.mkdirs(); directCache.writeText(writeLinks(extra)) }
        return merge(withFixes, extra)
    }

    internal fun writeLinks(links: List<Link>): String =
        JSONArray(links.map { JSONObject().put("kind", it.kind.name).put("url", it.url) }).toString()

    internal fun readLinks(json: String): List<Link> = JSONArray(json).let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map { Link(Kind.valueOf(it.getString("kind")), it.getString("url")) }
    }

    fun protonTier(appId: Long): String? = runCatching {
        parseTier(get("https://www.protondb.com/api/v1/reports/summaries/$appId.json"))
    }.getOrNull()

    internal fun parseTier(json: String): String? =
        JSONObject(json).optString("tier").takeIf { it in TIERS }

    /** Searches on the sites themselves, for what has no direct page. */
    fun searches(name: String, found: List<Link>): List<Search> {
        val q = URLEncoder.encode(name, "UTF-8")
        return listOfNotNull(
            Search("youtube", "https://m.youtube.com/results?search_query=$q+walkthrough"),
            Search("gamefaqs", "https://gamefaqs.gamespot.com/search?game=$q").takeIf { found.none { it.kind == Kind.GAMEFAQS } },
        )
    }

    private fun path(id: String) = id.replace(' ', '_')

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "DroidDeck (bottom screen guides)")
        c.setRequestProperty("Accept", "application/sparql-results+json, application/json")
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    val TIERS = listOf("platinum", "gold", "silver", "bronze", "borked")
    private const val CACHE_MS = 7L * 24 * 3600 * 1000
    private const val BROWSER = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
}

object GuideLibrary {
    data class Saved(val file: File, val title: String, val url: String)

    data class PinnedMap(val file: File, val markers: List<Pair<Float, Float>>)

    private fun dir(context: Context, game: String, kind: String) =
        File(context.filesDir, "guides/${game.replace(Regex("[^A-Za-z0-9._-]"), "_")}/$kind")

    fun savedFile(context: Context, game: String): File = dir(context, game, "saved").apply { mkdirs() }.let { File(it, "${System.currentTimeMillis()}.mht") }

    fun recordSaved(file: File, title: String, url: String) {
        runCatching { File(file.path.removeSuffix(".mht") + ".json").writeText(JSONObject().put("title", title).put("url", url).toString()) }
    }

    fun saved(context: Context, game: String): List<Saved> = dir(context, game, "saved").listFiles { f -> f.name.endsWith(".mht") && f.length() > 0 }
        .orEmpty().sortedByDescending { it.lastModified() }.map { f ->
            val meta = runCatching { JSONObject(File(f.path.removeSuffix(".mht") + ".json").readText()) }.getOrNull()
            Saved(f, meta?.optString("title").orEmpty().ifEmpty { f.nameWithoutExtension }, meta?.optString("url").orEmpty())
        }

    fun isSaved(context: Context, file: File): Boolean =
        file.canonicalPath.startsWith(File(context.filesDir, "guides").canonicalPath + File.separator) && file.name.endsWith(".mht")

    fun delete(file: File) {
        file.delete()
        File(file.path.removeSuffix(".mht") + ".json").delete()
        File(file.path + ".markers.json").delete()
    }

    fun maps(context: Context, game: String): List<PinnedMap> = dir(context, game, "maps")
        .listFiles { f -> f.extension in IMAGE_TYPES.values }.orEmpty().sortedByDescending { it.lastModified() }
        .map { PinnedMap(it, markers(it)) }

    fun markers(map: File): List<Pair<Float, Float>> = runCatching {
        val arr = JSONArray(File(map.path + ".markers.json").readText())
        List(arr.length()) { i -> arr.getJSONArray(i).let { it.getDouble(0).toFloat() to it.getDouble(1).toFloat() } }
    }.getOrDefault(emptyList())

    fun saveMarkers(map: File, markers: List<Pair<Float, Float>>) {
        runCatching { File(map.path + ".markers.json").writeText(JSONArray(markers.map { JSONArray(listOf(it.first.toDouble(), it.second.toDouble())) }).toString()) }
    }

    /** Follows redirects itself so the page's cookies reach only the host they were read for. */
    private const val MAX_REDIRECTS = 5

    private fun openMap(url: String, cookies: String?): HttpURLConnection? {
        val home = URL(url).host
        var target = URL(url)
        repeat(MAX_REDIRECTS + 1) {
            val c = target.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) DroidDeck")
            if (target.host.equals(home, ignoreCase = true)) cookies?.takeIf { it.isNotEmpty() }?.let { c.setRequestProperty("Cookie", it) }
            if (c.responseCode !in 300..399) return c
            val next = c.getHeaderField("Location")?.let { runCatching { URL(target, it) }.getOrNull() }
            c.disconnect()
            if (next == null || (next.protocol != "https" && next.protocol != "http")) return null
            target = next
        }
        return null
    }

    fun pin(context: Context, game: String, url: String, cookies: String?): File? = runCatching {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        val c = openMap(url, cookies) ?: return null
        try {
            if (c.responseCode != 200) return null
            val ext = IMAGE_TYPES[c.contentType?.substringBefore(';')?.trim()?.lowercase()] ?: return null
            if (c.contentLengthLong > MAX_MAP_BYTES) return null
            val out = File(dir(context, game, "maps").apply { mkdirs() }, "${System.currentTimeMillis()}.$ext")
            // Written beside the map and renamed when complete, so the Maps list never shows a partial image.
            val part = File(out.path + ".part")
            try {
                c.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_MAP_BYTES) return null
                            output.write(buf, 0, n)
                        }
                    }
                }
                if (!part.renameTo(out)) return null
            } finally {
                part.delete()
            }
            out
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    fun clear(context: Context, game: String) {
        File(context.filesDir, "guides/${game.replace(Regex("[^A-Za-z0-9._-]"), "_")}").deleteRecursively()
    }

    private val IMAGE_TYPES = mapOf("image/png" to "png", "image/jpeg" to "jpg", "image/webp" to "webp", "image/gif" to "gif")
    private const val MAX_MAP_BYTES = 40L * 1024 * 1024
}
