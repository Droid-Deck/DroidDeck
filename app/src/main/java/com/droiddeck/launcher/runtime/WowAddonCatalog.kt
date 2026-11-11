package com.droiddeck.launcher.runtime

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.Downloader
import com.droiddeck.launcher.core.Hashes
import com.droiddeck.launcher.stores.StoreNet
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.URLEncoder

object WowAddonCatalog {
    private const val TAG = "WowAddonCatalog"
    enum class Sort { POPULAR, UPDATED, NAME, DOWNLOADS }

    class Category(val id: String, val name: String)

    class Client(val folder: String, val family: String?)

    class Query(val text: String = "", val category: String? = null, val client: Client, val sort: Sort = Sort.POPULAR)

    class Addon(
        val id: String,
        val name: String,
        val author: String,
        val summary: String,
        val downloads: Long,
        val updated: Long,
        val thumb: String?,
    )

    class Page(val items: List<Addon>, val more: Boolean)

    internal class Entry(val addon: Addon, val category: String, val flavors: Set<String>?, val monthly: Long = 0L)

    internal class Cat(val id: String, val name: String, val parents: List<String>)

    private const val WOWI = "https://api.mmoui.com/v3/game/WOW/"
    private const val CURSEFORGE = "https://www.curseforge.com/wow/search?class=addons"
    private const val WOWI_FILES = "wowi-filelist.json"
    internal const val FRESH_MS = 6L * 60 * 60 * 1000

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
                id, o.optString("UIName"), o.optString("UIAuthorName"), o.optString("UIVersion"),
                o.optString("UIDownloadTotal").toLongOrNull() ?: 0L, o.optLong("UIDate"), thumb,
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
        val kept = if (file.isFile) file.readText().takeIf(::isJsonArray) else null
        if (kept != null && isFresh(file, now)) return kept
        val body = fetch()?.takeIf(::isJsonArray)
        if (body != null) {
            val part = File(file.parentFile, "${file.name}.${java.util.UUID.randomUUID()}.part")
            runCatching {
                file.parentFile?.mkdirs()
                part.writeText(body)
                part.setLastModified(now)
                if (!part.renameTo(file)) Log.w(TAG, "could not keep ${file.name}")
            }.onFailure { Log.w(TAG, "could not keep ${file.name}", it) }
            part.delete()
            return body
        }
        return kept
    }

    private fun isJsonArray(body: String): Boolean =
        runCatching { org.json.JSONTokener(body).nextValue() }.getOrNull() is JSONArray

    private fun isFresh(file: File, now: Long) = file.isFile && now - file.lastModified() < FRESH_MS

    private fun cacheFile(context: Context, name: String) = File(File(context.cacheDir, "wowaddons"), name)

    private fun cachedGet(context: Context, name: String, url: String): String? =
        cached(cacheFile(context, name), System.currentTimeMillis()) { StoreNet.get(url, timeoutMs = 60_000) }

    private fun unreachable(context: Context) =
        IOException(context.getString(R.string.addons_unreachable, context.getString(R.string.addons_source_wowi)))

    fun search(context: Context, query: Query, page: Int, pageSize: Int = 40): Page =
        pageOf(filterWowi(wowiEntries(context), wowiCategories(context), query), page, pageSize)

    private class Memo(val stamp: Long, val size: Long, val entries: List<Entry>)

    @Volatile private var wowiMemo: Memo? = null

    private fun wowiEntries(context: Context): List<Entry> {
        val file = cacheFile(context, WOWI_FILES)
        val memo = wowiMemo
        if (memo != null && isFresh(file, System.currentTimeMillis()) && memo.stamp == file.lastModified() && memo.size == file.length()) return memo.entries
        val body = cachedGet(context, WOWI_FILES, WOWI + "filelist.json") ?: throw unreachable(context)
        return parseWowiFiles(body).also { wowiMemo = Memo(file.lastModified(), file.length(), it) }
    }

    private fun wowiCategories(context: Context): List<Cat> =
        cachedGet(context, "wowi-categories.json", WOWI + "categorylist.json")?.let(::parseWowiCategories) ?: emptyList()

    fun categories(context: Context): List<Category> = wowiCategories(context).map { Category(it.id, it.name) }

    internal fun parseWowiDetails(json: String): Pair<String, String?>? {
        val o = JSONArray(json).optJSONObject(0) ?: return null
        val url = o.optString("UIDownload").ifEmpty { return null }
        return url to o.optString("UIMD5").ifEmpty { null }
    }

    internal fun isHttps(url: String) = url.startsWith("https://", ignoreCase = true)

    internal fun md5Matches(file: File, expected: String?): Boolean =
        expected.isNullOrBlank() || Hashes.md5(file).equals(expected.trim(), ignoreCase = true)

    fun download(context: Context, addon: Addon, dest: File, progress: (Int) -> Unit): File {
        val body = StoreNet.get(WOWI + "filedetails/${URLEncoder.encode(addon.id, "UTF-8")}.json", timeoutMs = 30_000)
            ?: throw unreachable(context)
        val (url, md5) = parseWowiDetails(body) ?: throw IOException(context.getString(R.string.addons_no_file))
        if (!isHttps(url)) throw IOException(context.getString(R.string.addons_no_file))
        dest.parentFile?.mkdirs()
        dest.delete()
        val ok = Downloader.downloadFile(url, dest, false) { f -> if (f >= 0) progress((f * 100).toInt().coerceIn(0, 100)) }
        if (!ok || !dest.isFile) {
            dest.delete()
            throw unreachable(context)
        }
        if (!md5Matches(dest, md5)) {
            dest.delete()
            throw IOException(context.getString(R.string.selfinst_checksum_mismatch))
        }
        progress(100)
        return dest
    }

    internal fun curseForgeUrl(text: String): String =
        text.trim().let { if (it.isEmpty()) CURSEFORGE else CURSEFORGE + "&search=" + URLEncoder.encode(it, "UTF-8") }
}
