package com.droiddeck.launcher.runtime

import android.content.Context
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.Downloader
import com.droiddeck.launcher.core.Hashes
import com.droiddeck.launcher.stores.StoreNet
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant

object WowAddonCatalog {
    enum class Source { WOWINTERFACE, CURSEFORGE }

    enum class Sort { POPULAR, UPDATED, NAME, DOWNLOADS }

    class Category(val id: String, val name: String)

    class Client(val folder: String, val family: String?)

    class Query(val text: String = "", val category: String? = null, val client: Client, val sort: Sort = Sort.POPULAR)

    class Addon(
        val source: Source,
        val id: String,
        val name: String,
        val author: String,
        val summary: String,
        val downloads: Long,
        val updated: Long,
        val thumb: String?,
        val fileId: String?,
    )

    class Page(val items: List<Addon>, val more: Boolean)

    internal class Entry(val addon: Addon, val category: String, val flavors: Set<String>?, val monthly: Long = 0L)

    internal class Cat(val id: String, val name: String, val parents: List<String>)

    private const val WOWI = "https://api.mmoui.com/v3/game/WOW/"
    private const val CF = "https://api.curseforge.com"
    private const val CF_GAME = 1
    private const val PREFS = "wow-addons"
    private const val KEY_PREF = "curseforgeKey"
    private const val WOWI_FILES = "wowi-filelist.json"
    internal const val FRESH_MS = 6L * 60 * 60 * 1000
    private const val CF_MAX_WINDOW = 10_000

    internal const val RETAIL = "_retail_"
    internal const val ERA = "_classic_era_"
    internal const val CLASSIC = "_classic_"
    internal const val ANNIVERSARY = "_anniversary_"
    internal const val FOREVER = "_forever_"

    private val TEST_SUFFIX = Regex("_(beta|ptr|xptr)_$")

    internal fun flavorOf(version: String): String? {
        val major = version.substringBefore('.').trim().toIntOrNull() ?: return null
        return when {
            major == 1 -> ERA
            major == 2 -> ANNIVERSARY
            major in 3..5 -> CLASSIC
            major >= 10 -> RETAIL
            else -> null
        }
    }

    internal fun baseFolder(folder: String): String = folder.replace(TEST_SUFFIX, "_")

    internal fun folderFamily(folder: String): String? = when (val base = baseFolder(folder)) {
        FOREVER -> RETAIL
        RETAIL, ERA, CLASSIC, ANNIVERSARY -> base
        else -> null
    }

    internal fun buildInfoVersion(buildInfo: String, product: String): String? {
        val lines = buildInfo.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return null
        val header = lines.first().split('|').map { it.substringBefore('!').trim().lowercase() }
        val productAt = header.indexOf("product")
        val versionAt = header.indexOf("version")
        if (productAt < 0 || versionAt < 0) return null
        return lines.drop(1).map { it.split('|') }
            .firstOrNull { it.getOrNull(productAt)?.trim().equals(product, ignoreCase = true) }
            ?.getOrNull(versionAt)?.trim()?.ifEmpty { null }
    }

    internal fun flavorProduct(flavorInfo: String): String? =
        flavorInfo.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && '!' !in it }.firstOrNull()

    internal fun clientVersion(flavorDir: File): String? {
        val product = runCatching { File(flavorDir, ".flavor.info").readText() }.getOrNull()?.let(::flavorProduct) ?: return null
        val info = runCatching { File(flavorDir.parentFile, ".build.info").readText() }.getOrNull() ?: return null
        return buildInfoVersion(info, product)
    }

    internal fun client(folder: String, version: String?): Client =
        Client(folder, version?.let(::flavorOf) ?: folderFamily(folder))

    fun client(target: WowAddons.Target): Client {
        val dir = target.addOns.parentFile?.parentFile
        return client(target.flavor, dir?.let(::clientVersion))
    }

    internal fun flavorMatches(flavors: Set<String>?, family: String?): Boolean =
        flavors == null || family == null || family in flavors

    internal fun parseWowiFiles(json: String): List<Entry> {
        val arr = JSONArray(json)
        val out = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("UID")
            if (id.isEmpty()) continue
            val compat = o.optJSONArray("UICompatibility")
            val flavors = if (compat == null || compat.length() == 0) null else {
                val set = HashSet<String>()
                for (k in 0 until compat.length()) compat.optJSONObject(k)?.optString("version")?.let(::flavorOf)?.let(set::add)
                set
            }
            val thumbs = o.optJSONArray("UIIMG_Thumbs")
            val thumb = if (thumbs != null && thumbs.length() > 0) thumbs.optString(0).ifEmpty { null } else null
            val addon = Addon(
                Source.WOWINTERFACE, id, o.optString("UIName"), o.optString("UIAuthorName"), o.optString("UIVersion"),
                o.optString("UIDownloadTotal").toLongOrNull() ?: 0L, o.optLong("UIDate"), thumb, null,
            )
            out.add(Entry(addon, o.optString("UICATID"), flavors, o.optString("UIDownloadMonthly").toLongOrNull() ?: 0L))
        }
        return out
    }

    internal fun parseWowiCategories(json: String): List<Cat> {
        val arr = JSONArray(json)
        val out = ArrayList<Cat>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val parents = o.optJSONArray("UICATParentIDs")
            out.add(Cat(o.optString("UICATID"), o.optString("UICATTitle"), (0 until (parents?.length() ?: 0)).map { parents!!.optString(it) }))
        }
        return out
    }

    internal fun filterWowi(entries: List<Entry>, cats: List<Cat>, query: Query): List<Addon> {
        val parents = cats.associate { it.id to it.parents }
        val text = query.text.trim().lowercase()
        val list = entries.filter { e ->
            flavorMatches(e.flavors, query.client.family) &&
                (query.category == null || e.category == query.category || query.category in (parents[e.category] ?: emptyList())) &&
                (text.isEmpty() || e.addon.name.lowercase().contains(text) || e.addon.author.lowercase().contains(text))
        }
        return when (query.sort) {
            Sort.POPULAR -> list.sortedWith(compareByDescending<Entry> { it.monthly }.thenByDescending { it.addon.downloads })
            Sort.DOWNLOADS -> list.sortedByDescending { it.addon.downloads }
            Sort.UPDATED -> list.sortedByDescending { it.addon.updated }
            Sort.NAME -> list.sortedBy { it.addon.name.lowercase() }
        }.map { it.addon }
    }

    internal fun pageOf(all: List<Addon>, page: Int, pageSize: Int): Page {
        val from = (page * pageSize).coerceAtMost(all.size)
        val to = (from + pageSize).coerceAtMost(all.size)
        return Page(all.subList(from, to), to < all.size)
    }

    internal fun cached(file: File, now: Long, fetch: () -> String?): String? {
        val kept = if (file.isFile) file.readText().takeIf(::isJson) else null
        if (kept != null && isFresh(file, now)) return kept
        val body = fetch()?.takeIf(::isJson)
        if (body != null) {
            file.parentFile?.mkdirs()
            val part = File(file.path + ".part")
            part.writeText(body)
            part.setLastModified(now)
            if (!part.renameTo(file)) part.delete()
            return body
        }
        return kept
    }

    private fun isJson(body: String): Boolean =
        runCatching { org.json.JSONTokener(body).nextValue() }.getOrNull().let { it is JSONObject || it is JSONArray }

    private fun isFresh(file: File, now: Long) = file.isFile && now - file.lastModified() < FRESH_MS

    private fun cacheFile(context: Context, name: String) = File(File(context.cacheDir, "wowaddons"), name)

    private fun cachedGet(context: Context, name: String, url: String, headers: Map<String, String> = emptyMap()): String? =
        cached(cacheFile(context, name), System.currentTimeMillis()) { StoreNet.get(url, timeoutMs = 60_000, headers = headers) }

    private fun unreachable(context: Context, source: Source) = IOException(
        context.getString(R.string.addons_unreachable, context.getString(if (source == Source.CURSEFORGE) R.string.addons_source_curse else R.string.addons_source_wowi)),
    )

    fun search(context: Context, source: Source, query: Query, page: Int, pageSize: Int = 40): Page = when (source) {
        Source.WOWINTERFACE -> pageOf(filterWowi(wowiEntries(context), wowiCategories(context), query), page, pageSize)
        Source.CURSEFORGE -> searchCurse(context, query, page, pageSize)
    }

    private class Memo(val stamp: Long, val size: Long, val entries: List<Entry>)

    @Volatile private var wowiMemo: Memo? = null

    private fun wowiEntries(context: Context): List<Entry> {
        val file = cacheFile(context, WOWI_FILES)
        val memo = wowiMemo
        if (memo != null && isFresh(file, System.currentTimeMillis()) && memo.stamp == file.lastModified() && memo.size == file.length()) return memo.entries
        val body = cachedGet(context, WOWI_FILES, WOWI + "filelist.json") ?: throw unreachable(context, Source.WOWINTERFACE)
        return parseWowiFiles(body).also { wowiMemo = Memo(file.lastModified(), file.length(), it) }
    }

    private fun wowiCategories(context: Context): List<Cat> =
        cachedGet(context, "wowi-categories.json", WOWI + "categorylist.json")?.let(::parseWowiCategories) ?: emptyList()

    fun categories(context: Context, source: Source): List<Category> = when (source) {
        Source.WOWINTERFACE -> wowiCategories(context).map { Category(it.id, it.name) }
        Source.CURSEFORGE -> {
            val key = apiKey(context)
            if (key.isBlank()) emptyList()
            else cachedGet(context, "cf-categories.json", "$CF/v1/categories?gameId=$CF_GAME", cfHeaders(key))?.let(::parseCurseCategories) ?: emptyList()
        }
    }

    fun apiKey(context: Context): String {
        val user = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PREF, null)?.trim().orEmpty()
        return user.ifBlank { BuildConfig.CURSEFORGE_API_KEY.trim() }
    }

    fun setUserKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PREF, key.trim()).apply()
        cacheFile(context, "cf-categories.json").delete()
        cacheFile(context, "cf-version-types.json").delete()
    }

    fun available(context: Context): Boolean = apiKey(context).isNotBlank()

    private fun cfHeaders(key: String) = mapOf("x-api-key" to key)

    internal fun parseCurseCategories(json: String): List<Category> {
        val arr = JSONObject(json).optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .filter { it.optBoolean("isClass", false).not() }
            .map { Category(it.optInt("id").toString(), it.optString("name")) }
            .sortedBy { it.name.lowercase() }
    }

    internal fun curseVersionType(json: String, client: Client): Int? {
        val arr = JSONObject(json).optJSONArray("data") ?: return null
        val types = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
            Triple(it.optInt("id"), words(it.optString("name")), words(it.optString("slug")))
        }
        fun best(test: (String, String) -> Boolean) = types.filter { (_, name, slug) -> test(name, slug) }.maxByOrNull { it.first }?.first
        if (baseFolder(client.folder) == FOREVER) best { name, slug -> "forever" in name || "forever" in slug }?.let { return it }
        return when (client.family) {
            RETAIL -> best { name, slug -> "retail" in name || "retail" in slug }
            ERA -> best { name, slug ->
                name == "classic" || name == "wow classic" || "classic era" in name || "vanilla" in name || slug == "wow classic" || slug == "classic"
            }
            ANNIVERSARY -> best { name, slug -> listOf("burning crusade", "anniversary", "tbc", "bcc").any { it in "$name $slug" } }
            CLASSIC -> best { name, slug -> listOf("cataclysm", "wotlk", "wrath", "mists", "pandaria", "progression").any { it in "$name $slug" } }
            else -> null
        }
    }

    private fun words(s: String) = s.lowercase().replace('-', ' ').replace('_', ' ').trim()

    private fun curseVersionType(context: Context, client: Client): Int? {
        val json = cachedGet(context, "cf-version-types.json", "$CF/v1/games/$CF_GAME/version-types", cfHeaders(apiKey(context))) ?: return null
        return runCatching { curseVersionType(json, client) }.getOrNull()
    }

    internal class CursePage(val items: List<Addon>, val more: Boolean)

    internal fun parseCurseSearch(json: String, typeId: Int?): CursePage {
        val root = JSONObject(json)
        val arr = root.optJSONArray("data") ?: JSONArray()
        val items = ArrayList<Addon>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            if (!m.optBoolean("allowModDistribution", true)) continue
            val authors = m.optJSONArray("authors")
            val indexes = m.optJSONArray("latestFilesIndexes")
            var fileId: String? = null
            if (indexes != null) {
                var fallback: String? = null
                for (k in 0 until indexes.length()) {
                    val ix = indexes.optJSONObject(k) ?: continue
                    val id = ix.optLong("fileId").takeIf { it > 0 }?.toString() ?: continue
                    if (fallback == null) fallback = id
                    if (typeId != null && ix.optInt("gameVersionTypeId") == typeId) { fileId = id; break }
                }
                if (fileId == null && typeId == null) fileId = fallback
            }
            if (typeId != null && fileId == null) continue
            items.add(Addon(
                Source.CURSEFORGE, m.optInt("id").toString(), m.optString("name"),
                authors?.optJSONObject(0)?.optString("name").orEmpty(), m.optString("summary"),
                m.optLong("downloadCount"), m.optString("dateModified").let { runCatching { Instant.parse(it).toEpochMilli() }.getOrDefault(0L) },
                m.optJSONObject("logo")?.optString("thumbnailUrl")?.ifEmpty { null }, fileId,
            ))
        }
        val p = root.optJSONObject("pagination")
        val more = p != null && p.optInt("index") + p.optInt("resultCount") < p.optInt("totalCount") &&
            p.optInt("index") + p.optInt("pageSize") + p.optInt("pageSize") <= CF_MAX_WINDOW
        return CursePage(items, more)
    }

    internal fun curseSearchUrl(query: Query, typeId: Int?, page: Int, pageSize: Int): String {
        val size = pageSize.coerceIn(1, 50)
        val sort = when (query.sort) { Sort.POPULAR -> 2; Sort.UPDATED -> 3; Sort.NAME -> 4; Sort.DOWNLOADS -> 6 }
        val order = if (query.sort == Sort.NAME) "asc" else "desc"
        val sb = StringBuilder("$CF/v1/mods/search?gameId=$CF_GAME&searchFilter=")
        sb.append(URLEncoder.encode(query.text.trim(), "UTF-8"))
        query.category?.let { sb.append("&categoryId=").append(URLEncoder.encode(it, "UTF-8")) }
        typeId?.let { sb.append("&gameVersionTypeId=").append(it) }
        sb.append("&sortField=$sort&sortOrder=$order&index=${page * size}&pageSize=$size")
        return sb.toString()
    }

    private fun searchCurse(context: Context, query: Query, page: Int, pageSize: Int): Page {
        val key = apiKey(context)
        if (key.isBlank()) throw IOException(context.getString(R.string.addons_curse_key_needed))
        val size = pageSize.coerceIn(1, 50)
        if ((page + 1) * size > CF_MAX_WINDOW) return Page(emptyList(), false)
        val typeId = curseVersionType(context, query.client)
        val body = StoreNet.get(curseSearchUrl(query, typeId, page, size), timeoutMs = 30_000, headers = cfHeaders(key))
            ?: throw unreachable(context, Source.CURSEFORGE)
        val parsed = parseCurseSearch(body, typeId)
        return Page(parsed.items, parsed.more)
    }

    internal fun parseWowiDetails(json: String): Pair<String, String?>? {
        val o = JSONArray(json).optJSONObject(0) ?: return null
        val url = o.optString("UIDownload").ifEmpty { return null }
        return url to o.optString("UIMD5").ifEmpty { null }
    }

    internal fun parseCurseDownloadUrl(json: String): String? {
        val o = JSONObject(json)
        return if (o.isNull("data")) null else o.optString("data").ifEmpty { null }
    }

    internal fun parseCurseFile(json: String): Pair<String?, String?> {
        val data = JSONObject(json).optJSONObject("data") ?: return null to null
        val url = if (data.isNull("downloadUrl")) null else data.optString("downloadUrl").ifEmpty { null }
        val hashes = data.optJSONArray("hashes")
        val md5 = (0 until (hashes?.length() ?: 0)).mapNotNull { hashes!!.optJSONObject(it) }
            .firstOrNull { it.optInt("algo") == 2 }?.optString("value")?.ifEmpty { null }
        return url to md5
    }

    internal fun pickCurseFile(json: String, typeId: Int?): String? {
        val m = JSONObject(json).optJSONObject("data") ?: return null
        if (!m.optBoolean("allowModDistribution", true)) return null
        val one = JSONObject().put("data", JSONArray().put(m)).toString()
        return parseCurseSearch(one, typeId).items.firstOrNull()?.fileId
    }

    internal fun isHttps(url: String) = url.startsWith("https://", ignoreCase = true)

    internal fun md5Matches(file: File, expected: String?): Boolean =
        expected.isNullOrBlank() || Hashes.md5(file).equals(expected.trim(), ignoreCase = true)

    fun download(context: Context, addon: Addon, client: Client, dest: File, progress: (Int) -> Unit): File {
        val (url, md5) = when (addon.source) {
            Source.WOWINTERFACE -> {
                val body = StoreNet.get(WOWI + "filedetails/${URLEncoder.encode(addon.id, "UTF-8")}.json", timeoutMs = 30_000)
                    ?: throw unreachable(context, Source.WOWINTERFACE)
                parseWowiDetails(body) ?: throw IOException(context.getString(R.string.addons_no_file))
            }
            Source.CURSEFORGE -> resolveCurse(context, addon, client)
        }
        if (!isHttps(url)) throw IOException(context.getString(R.string.addons_no_file))
        dest.parentFile?.mkdirs()
        dest.delete()
        val ok = Downloader.downloadFile(url, dest, false) { f -> if (f >= 0) progress((f * 100).toInt().coerceIn(0, 100)) }
        if (!ok || !dest.isFile) {
            dest.delete()
            throw unreachable(context, addon.source)
        }
        if (!md5Matches(dest, md5)) {
            dest.delete()
            throw IOException(context.getString(R.string.selfinst_checksum_mismatch))
        }
        progress(100)
        return dest
    }

    private fun resolveCurse(context: Context, addon: Addon, client: Client): Pair<String, String?> {
        val key = apiKey(context)
        if (key.isBlank()) throw IOException(context.getString(R.string.addons_curse_key_needed))
        val headers = cfHeaders(key)
        val fileId = addon.fileId ?: run {
            val mod = StoreNet.get("$CF/v1/mods/${addon.id}", timeoutMs = 30_000, headers = headers) ?: throw unreachable(context, Source.CURSEFORGE)
            pickCurseFile(mod, curseVersionType(context, client)) ?: throw IOException(context.getString(R.string.addons_no_file))
        }
        val file = StoreNet.get("$CF/v1/mods/${addon.id}/files/$fileId", timeoutMs = 30_000, headers = headers)?.let(::parseCurseFile)
            ?: throw unreachable(context, Source.CURSEFORGE)
        val url = file.first ?: StoreNet.get("$CF/v1/mods/${addon.id}/files/$fileId/download-url", timeoutMs = 30_000, headers = headers)
            ?.let(::parseCurseDownloadUrl)
            ?: throw IOException(context.getString(R.string.addons_not_distributable))
        return url to file.second
    }
}
