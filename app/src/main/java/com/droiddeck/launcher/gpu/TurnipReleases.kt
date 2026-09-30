package com.droiddeck.launcher.gpu

import com.droiddeck.launcher.core.Hashes
import android.content.Context
import com.droiddeck.launcher.core.FileUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Driver downloads from the Turnip release repos, offered in both driver menus. Checked only when
 * the user taps refresh - the result is remembered, so the menu shows the last check without going
 * online. A download goes through the same importer as a zip picked by hand, so it gets the same
 * checks and lands as an ordinary imported driver.
 *
 * Each repo's recent releases are read newest first and every driver variant is offered from the
 * newest release that carries it: a repo that stops publishing one variant (Banners-Turnip's A8xx
 * Linux build after v26.3.0-20260923) still offers its last one, and a repo with two release lines
 * (WinNative's Android `v1.x` and Linux `linux-v0.x`) offers the newest of each.
 */
object TurnipReleases {
    private const val PREFS = "turnip_releases"
    private const val KEY_RELEASE = "latest"
    private const val KEY_DOWNLOADS = "downloads"

    /** [label] names the variant for the menu: the GPUs it is for, and a build flavour. */
    /** [sha256] is the release asset's digest from GitHub; empty for a list cached before it was kept. */
    /**
     * [pair] names the matched set an asset belongs to (DriverPairs): a display build and a Linux
     * build of the same pair are made to run together. "" for a list cached before pairs were kept.
     */
    /** [bundle]: one zip holding both halves of the pair (DriverBundle); [linux] is false for it. */
    class Asset(val source: String, val tag: String, val name: String, val url: String, val size: Long, val linux: Boolean, val label: String,
                val sha256: String = "", val pair: String = "", val bundle: Boolean = false)
    /** [latest] = each source's newest release tag, for the refresh line. */
    class Check(val assets: List<Asset>, val latest: List<Pair<String, String>>, val failed: List<String>, val checkedAt: Long)

    /** What an asset is: a Linux build or not, the menu label, and the pair it belongs to. */
    private class Kind(val linux: Boolean, val label: String, val pair: String, val bundle: Boolean = false)

    private class Source(val label: String, val repo: String, val classify: (name: String, tag: String) -> Kind?)

    private val SOURCES = listOf(
        // Turnip-<tag>[-variant][-Linux|-Wayland].zip; -Wayland is for a path this app does not have.
        Source("Banners-Turnip", "The412Banner/Banners-Turnip") { name, tag ->
            val prefix = "Turnip-$tag"
            if (!name.startsWith(prefix) || !name.endsWith(".zip")) return@Source null
            var variant = name.removePrefix(prefix).removeSuffix(".zip")
            if (variant.endsWith("-Wayland")) return@Source null
            val linux = variant.endsWith("-Linux")
            variant = variant.removeSuffix("-Linux")
            val label = when {
                variant.isEmpty() -> "Adreno 6xx/7xx"
                variant == "-A8xx" -> "Adreno 8xx"
                variant.startsWith("-710-720") -> "Adreno 710/720" + if (variant.contains("Test")) " (test)" else ""
                variant.contains("OneUI", ignoreCase = true) -> "8 Gen 2 on One UI"
                else -> variant.trimStart('-')
            }
            Kind(linux, label, "banner" + variant.lowercase())
        },
        // WN-Turnip-<ver>-<b|p>_Axxx.zip (Android) and WN-Linux-Turnip-<ver>-<b|p>_Axxx.zip (Linux):
        // b = Balanced, p = Performance, Axxx = every Adreno.
        Source("WinNative", "WinNative-Emu/Drivers") { name, _ ->
            val m = Regex("""^WN-(Linux-)?Turnip-[^-]+-([a-z]+)_(\w+)\.zip$""").find(name) ?: return@Source null
            val flavour = when (m.groupValues[2]) { "b" -> "Balanced"; "p" -> "Performance"; else -> m.groupValues[2] }
            val gpus = if (m.groupValues[3] == "Axxx") "all Adreno" else m.groupValues[3]
            Kind(m.groupValues[1].isNotEmpty(), "$gpus · $flavour", "wn-" + m.groupValues[2])
        },
        // DD-Turnip-v<ver>.zip: DroidDeck's own all-in-one build, both halves in one zip.
        Source("DroidDeck", "Droid-Deck/Drivers") { name, _ ->
            if (!Regex("""^DD-Turnip-v\d+\.\d+\.\d+\.zip$""").matches(name)) return@Source null
            Kind(false, "Android + Linux · all Adreno", DriverPairs.DD_TURNIP, bundle = true)
        },
    )

    /** The result of the last check, or null when the user has never checked. */
    fun cached(context: Context): Check? {
        val raw = prefs(context).getString(KEY_RELEASE, null) ?: return null
        return runCatching { parse(JSONObject(raw)) }.getOrNull()
    }

    /**
     * Read every source now and remember the result. A source that fails is named in [Check.failed]
     * and the others still count; throws only when every source failed.
     */
    fun refresh(context: Context): Check {
        val assets = JSONArray()
        val latest = JSONArray()
        val failed = ArrayList<String>()
        var lastError: Exception? = null
        for (src in SOURCES) {
            try {
                val releases = JSONArray(get("https://api.github.com/repos/${src.repo}/releases?per_page=15"))
                val seen = HashSet<String>()
                var newest: String? = null
                for (i in 0 until releases.length()) {
                    val r = releases.getJSONObject(i)
                    if (r.optBoolean("draft")) continue
                    val tag = r.getString("tag_name")
                    if (newest == null) newest = tag
                    val list = r.optJSONArray("assets") ?: continue
                    for (j in 0 until list.length()) {
                        val a = list.getJSONObject(j)
                        val name = a.getString("name")
                        val kind = src.classify(name, tag) ?: continue
                        val linux = kind.linux
                        val label = kind.label
                        // Only assets GitHub has a sha256 for are offered: the download is checked against it.
                        val sha = Hashes.githubSha256(a.optString("digest")) ?: continue
                        // Newest first: the first release carrying a variant is the one offered.
                        if (!seen.add("$linux|${kind.bundle}|$label")) continue
                        assets.put(JSONObject().put("source", src.label).put("tag", tag).put("name", name)
                            .put("url", a.getString("browser_download_url")).put("size", a.optLong("size"))
                            .put("linux", linux).put("label", label).put("sha256", sha).put("pair", kind.pair)
                            .put("bundle", kind.bundle))
                    }
                }
                if (newest != null) latest.put(JSONObject().put("source", src.label).put("tag", newest))
            } catch (e: Exception) {
                failed.add(src.label)
                lastError = e
            }
        }
        if (failed.size == SOURCES.size) throw lastError ?: IOException("no source answered")
        val stored = JSONObject().put("assets", assets).put("latest", latest)
            .put("failed", JSONArray(failed)).put("checkedAt", System.currentTimeMillis())
        prefs(context).edit().putString(KEY_RELEASE, stored.toString()).apply()
        return parse(stored)
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "DroidDeck-app")
        try {
            val code = c.responseCode
            if (code == 403 || code == 429) throw IOException("GitHub's rate limit was hit - try again later")
            if (code != 200) throw IOException("GitHub answered HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun parse(json: JSONObject): Check {
        val out = ArrayList<Asset>()
        val list = json.getJSONArray("assets")
        for (i in 0 until list.length()) {
            val a = list.getJSONObject(i)
            out.add(Asset(a.getString("source"), a.getString("tag"), a.getString("name"), a.getString("url"),
                a.optLong("size"), a.getBoolean("linux"), a.getString("label"), a.optString("sha256", ""), a.optString("pair", ""),
                a.optBoolean("bundle", false)))
        }
        val latest = ArrayList<Pair<String, String>>()
        val l = json.optJSONArray("latest") ?: JSONArray()
        for (i in 0 until l.length()) l.getJSONObject(i).let { latest.add(it.getString("source") to it.getString("tag")) }
        val failed = ArrayList<String>()
        val f = json.optJSONArray("failed") ?: JSONArray()
        for (i in 0 until f.length()) failed.add(f.getString(i))
        return Check(out, latest, failed, json.optLong("checkedAt"))
    }

    /** The id an asset was installed as, when it was downloaded before and is still installed. */
    fun installedId(context: Context, asset: Asset, isInstalled: (String) -> Boolean): String? =
        downloads(context).optString(asset.name, "").takeIf { it.isNotEmpty() && isInstalled(it) }

    /** True when [id] came from a release download rather than a zip picked by hand. */
    fun isDownloaded(context: Context, id: String): Boolean {
        val d = downloads(context)
        return d.keys().asSequence().any { d.optString(it) == id }
    }

    fun recordDownload(context: Context, asset: Asset, id: String) {
        prefs(context).edit().putString(KEY_DOWNLOADS, downloads(context).put(asset.name, id).toString()).apply()
    }

    /** Forget a deleted driver, so its release offers the download again. */
    fun forget(context: Context, id: String) {
        val d = downloads(context)
        val keys = d.keys().asSequence().filter { d.optString(it) == id }.toList()
        if (keys.isEmpty()) return
        keys.forEach { d.remove(it) }
        prefs(context).edit().putString(KEY_DOWNLOADS, d.toString()).apply()
    }

    /** Download an asset into the cache; the caller imports it and deletes the file. */
    fun download(context: Context, asset: Asset, progress: (Int) -> Unit): File {
        if (asset.sha256.isEmpty()) throw IOException("This driver list predates checksums - refresh it and try again")
        val target = File(context.cacheDir, asset.name)
        val c = URL(asset.url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", "DroidDeck-app")
        c.instanceFollowRedirects = true
        try {
            if (c.responseCode != 200) throw IOException("download answered HTTP ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: asset.size
            c.inputStream.use { input ->
                FileOutputStream(target).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val r = input.read(buf)
                        if (r <= 0) break
                        out.write(buf, 0, r)
                        done += r
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        if (pct != last) { last = pct; progress(pct) }
                    }
                }
            }
            if (!asset.sha256.equals(Hashes.sha256(target), ignoreCase = true)) {
                throw IOException("Checksum mismatch - the download was discarded")
            }
            return target
        } catch (e: Exception) {
            FileUtils.delete(target)
            throw e
        } finally {
            c.disconnect()
        }
    }

    private fun downloads(context: Context): JSONObject =
        runCatching { JSONObject(prefs(context).getString(KEY_DOWNLOADS, "{}")!!) }.getOrDefault(JSONObject())

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
