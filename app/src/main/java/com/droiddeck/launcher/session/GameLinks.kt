package com.droiddeck.launcher.session

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object GameLinks {
    enum class Kind { WIKI, STRATEGYWIKI, PCGAMINGWIKI, GAMEFAQS, IGN, HOWLONGTOBEAT }

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
        val fandom = values["fandom"]?.let { all -> all.firstOrNull { '.' !in it } ?: all.firstOrNull() }
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

    fun links(context: Context, appId: Long): List<Link> {
        val cache = File(context.cacheDir, "game-links/$appId.json")
        val fresh = cache.isFile && System.currentTimeMillis() - cache.lastModified() < CACHE_MS
        val text = if (fresh) runCatching { cache.readText() }.getOrNull() else null
        val json = text ?: runCatching {
            get("https://query.wikidata.org/sparql?format=json&query=" + URLEncoder.encode(sparql(appId), "UTF-8"))
        }.getOrNull()?.also { runCatching { cache.parentFile?.mkdirs(); cache.writeText(it) } }
            ?: runCatching { cache.readText() }.getOrNull()
        val found = json?.let { runCatching { parse(it) }.getOrNull() }.orEmpty()
        return if (found.any { it.kind == Kind.PCGAMINGWIKI }) found
            else found + Link(Kind.PCGAMINGWIKI, "https://www.pcgamingwiki.com/api/appid.php?appid=$appId")
    }

    fun protonTier(appId: Long): String? = runCatching {
        parseTier(get("https://www.protondb.com/api/v1/reports/summaries/$appId.json"))
    }.getOrNull()

    internal fun parseTier(json: String): String? =
        JSONObject(json).optString("tier").takeIf { it in TIERS }

    fun searches(name: String): List<Search> {
        val q = URLEncoder.encode(name, "UTF-8")
        return listOf(
            Search("youtube", "https://m.youtube.com/results?search_query=$q+walkthrough"),
            Search("gamefaqs", "https://gamefaqs.gamespot.com/search?game=$q"),
            Search("ign", "https://www.ign.com/search?q=$q+guide"),
        )
    }

    fun mapSearches(name: String): List<Search> {
        val q = URLEncoder.encode(name, "UTF-8")
        return listOf(
            Search("mapgenie", "https://duckduckgo.com/html/?q=site%3Amapgenie.io+$q"),
            Search("vgmaps", "https://duckduckgo.com/html/?q=site%3Avgmaps.com+$q"),
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

    fun pin(context: Context, game: String, url: String, cookies: String?): File? = runCatching {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) DroidDeck")
        cookies?.takeIf { it.isNotEmpty() }?.let { c.setRequestProperty("Cookie", it) }
        try {
            if (c.responseCode != 200) return null
            val ext = IMAGE_TYPES[c.contentType?.substringBefore(';')?.trim()?.lowercase()] ?: return null
            if (c.contentLengthLong > MAX_MAP_BYTES) return null
            val out = File(dir(context, game, "maps").apply { mkdirs() }, "${System.currentTimeMillis()}.$ext")
            c.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_MAP_BYTES) {
                            output.close()
                            out.delete()
                            return null
                        }
                        output.write(buf, 0, n)
                    }
                }
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
