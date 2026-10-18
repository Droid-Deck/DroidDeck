package com.droiddeck.launcher.gpu

import com.droiddeck.launcher.core.Downloader
import com.droiddeck.launcher.core.Hashes
import com.droiddeck.launcher.R
import com.droiddeck.launcher.artifact.GitHubArtifacts
import com.droiddeck.launcher.artifact.RepositorySources
import android.content.Context
import com.droiddeck.launcher.core.FileUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

object TurnipReleases {
    private const val PREFS = "turnip_releases"
    private const val KEY_RELEASE = "latest"
    private const val KEY_DOWNLOADS = "downloads"

    const val SOURCE_SCOPE = "gpu/turnip"

    class Asset(val source: String, val tag: String, val name: String, val url: String, val size: Long, val linux: Boolean, val label: String,
                val sha256: String = "", val pair: String = "", val bundle: Boolean = false,
                val sourceId: String = "", val repository: String = "", val repositoryId: Long = 0,
                val releaseId: Long = 0, val assetId: Long = 0) {
        val id: String get() = if (sourceId.isEmpty()) name else "$sourceId:$assetId:$sha256"
    }
    class Check(val assets: List<Asset>, val latest: List<Pair<String, String>>, val failed: List<String>, val checkedAt: Long,
                val rateLimited: Boolean = false)

    private class Kind(val linux: Boolean, val label: String, val pair: String, val bundle: Boolean = false)

    private class Adapter(val label: String, val repo: String, val classify: (name: String, tag: String) -> Kind?)

    private val SOURCES = listOf(
        Adapter("Banners-Turnip", "the412banner/banners-turnip") { name, tag ->
            val prefix = "Turnip-$tag"
            if (!name.startsWith(prefix) || !name.endsWith(".zip")) return@Adapter null
            var variant = name.removePrefix(prefix).removeSuffix(".zip")
            if (variant.endsWith("-Wayland")) return@Adapter null
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
        Adapter("WinNative", "winnative-emu/drivers") { name, _ ->
            val m = Regex("""^WN-(Linux-)?Turnip-[^-]+-([a-z]+)_(\w+)\.zip$""").find(name) ?: return@Adapter null
            val flavour = when (m.groupValues[2]) { "b" -> "Balanced"; "p" -> "Performance"; else -> m.groupValues[2] }
            val gpus = if (m.groupValues[3] == "Axxx") "all Adreno" else m.groupValues[3]
            Kind(m.groupValues[1].isNotEmpty(), "$gpus · $flavour", "wn-" + m.groupValues[2])
        },
        Adapter("DroidDeck", "droid-deck/drivers-ci") { name, tag ->
            if (!Regex("""^DD-Turnip-v\d+\.\d+\.\d+\.zip$""").matches(name)) return@Adapter null
            Kind(false, "Android + Linux · all Adreno", DriverPairs.ddTurnip(tag), bundle = true)
        },
    )

    @JvmStatic fun sourceBuiltIns(): List<String> = SOURCES.map { it.repo }

    @JvmStatic fun sources(context: Context): List<RepositorySources.Source> =
        RepositorySources.list(context, SOURCE_SCOPE, sourceBuiltIns())

    @JvmStatic fun saveSources(context: Context, rows: List<RepositorySources.Source>): List<RepositorySources.Source> =
        RepositorySources.save(context, SOURCE_SCOPE, sourceBuiltIns(), rows)

    fun cached(context: Context): Check? {
        val raw = prefs(context).getString(KEY_RELEASE, null) ?: return null
        val visible = RepositorySources.visible(context, SOURCE_SCOPE, sourceBuiltIns()).map { it.id }.toSet()
        return runCatching { parse(JSONObject(raw), visible) }.getOrNull()
    }

    fun refresh(context: Context): Check {
        val previous = prefs(context).getString(KEY_RELEASE, null)?.let {
            runCatching { parse(JSONObject(it), null) }.getOrNull()
        }
        val assets = JSONArray()
        val latest = JSONArray()
        val failed = ArrayList<String>()
        var lastError: Exception? = null
        var rateLimited = false
        for (configured in RepositorySources.visible(context, SOURCE_SCOPE, sourceBuiltIns())) {
            try {
                val releases = GitHubArtifacts.refreshReleases(context, SOURCE_SCOPE, sourceBuiltIns(), configured)
                val source = RepositorySources.visible(context, SOURCE_SCOPE, sourceBuiltIns()).single { it.id == configured.id }
                val preferred = SOURCES.firstOrNull { it.repo == source.repository }
                val seen = HashSet<String>()
                var newest: String? = null
                for (r in releases) {
                    val tag = r.tag
                    if (newest == null) newest = tag
                    for (a in r.assets) {
                        val name = a.name
                        val matches = (listOfNotNull(preferred) + SOURCES.filter { it !== preferred })
                            .mapNotNull { adapter -> adapter.classify(name, tag)?.let { adapter to it } }
                        val (adapter, kind) = matches.firstOrNull() ?: continue
                        if (kind.bundle && r.prerelease) continue
                        val linux = kind.linux
                        val label = kind.label
                        val pair = qualifiedPair(source.repository, source.id, kind.pair)
                        if (!seen.add(if (kind.bundle) pair else "$linux|$label")) continue
                        assets.put(JSONObject().put("source", if (preferred != null) preferred.label else source.repository).put("tag", tag).put("name", name)
                            .put("url", a.url).put("size", a.size)
                            .put("linux", linux).put("label", label).put("sha256", a.sha256).put("pair", pair)
                            .put("bundle", kind.bundle).put("sourceId", source.id).put("repository", source.repository)
                            .put("repositoryId", source.repositoryId).put("releaseId", r.id).put("assetId", a.id))
                    }
                }
                if (newest != null) latest.put(JSONObject().put("source", preferred?.label ?: source.repository)
                    .put("tag", newest).put("sourceId", source.id).put("repository", source.repository))
            } catch (e: Exception) {
                failed.add(configured.repository)
                if (lastError == null || GitHubArtifacts.isRateLimited(e)) lastError = e
                rateLimited = rateLimited || GitHubArtifacts.isRateLimited(e)
                previous?.assets?.filter { it.sourceId == configured.id }?.forEach { assets.put(encode(it)) }
                val knownLabel = SOURCES.firstOrNull { it.repo == configured.repository }?.label
                previous?.latest?.filter { it.first == knownLabel || it.first == configured.repository }?.forEach {
                    latest.put(JSONObject().put("source", it.first).put("tag", it.second)
                        .put("sourceId", configured.id).put("repository", configured.repository))
                }
            }
        }
        if (failed.isNotEmpty() && failed.size == RepositorySources.visible(context, SOURCE_SCOPE, sourceBuiltIns()).size && assets.length() == 0) {
            throw lastError ?: IOException("no source answered")
        }
        val allFailed = failed.isNotEmpty() && failed.size == RepositorySources.visible(context, SOURCE_SCOPE, sourceBuiltIns()).size
        val stored = JSONObject().put("assets", assets).put("latest", latest)
            .put("failed", JSONArray(failed)).put("checkedAt", if (allFailed) previous?.checkedAt ?: 0L else System.currentTimeMillis())
        prefs(context).edit().putString(KEY_RELEASE, stored.toString()).apply()
        val current = RepositorySources.visible(context, SOURCE_SCOPE, sourceBuiltIns()).map { it.id }.toSet()
        return parse(stored, current, rateLimited)
    }

    internal fun qualifiedPair(repository: String, sourceId: String, pair: String): String =
        if (repository in sourceBuiltIns()) pair else "$pair:$sourceId"

    private fun encode(a: Asset): JSONObject = JSONObject().put("source", a.source).put("tag", a.tag)
        .put("name", a.name).put("url", a.url).put("size", a.size).put("linux", a.linux)
        .put("label", a.label).put("sha256", a.sha256).put("pair", a.pair).put("bundle", a.bundle)
        .put("sourceId", a.sourceId).put("repository", a.repository).put("repositoryId", a.repositoryId)
        .put("releaseId", a.releaseId).put("assetId", a.assetId)

    private fun parse(json: JSONObject, visible: Set<String>?, rateLimited: Boolean = false): Check {
        val out = ArrayList<Asset>()
        val list = json.getJSONArray("assets")
        for (i in 0 until list.length()) {
            val a = list.getJSONObject(i)
            val legacyAdapter = SOURCES.firstOrNull { it.label == a.optString("source") }
            val repository = a.optString("repository").ifEmpty { legacyAdapter?.repo.orEmpty() }
            val sourceId = a.optString("sourceId").ifEmpty {
                repository.takeIf { it.isNotEmpty() }?.let { RepositorySources.source(it).id }.orEmpty()
            }
            if (visible != null && (sourceId.isEmpty() || sourceId !in visible)) continue
            out.add(Asset(a.getString("source"), a.getString("tag"), a.getString("name"), a.getString("url"),
                a.optLong("size"), a.getBoolean("linux"), a.getString("label"), a.optString("sha256", ""), a.optString("pair", ""),
                a.optBoolean("bundle", false), sourceId, repository, a.optLong("repositoryId"),
                a.optLong("releaseId"), a.optLong("assetId")))
        }
        val latest = ArrayList<Pair<String, String>>()
        val l = json.optJSONArray("latest") ?: JSONArray()
        for (i in 0 until l.length()) l.getJSONObject(i).let { row ->
            val sourceId = row.optString("sourceId").ifEmpty {
                SOURCES.firstOrNull { it.label == row.optString("source") }?.repo?.let { RepositorySources.source(it).id }.orEmpty()
            }
            if (visible == null || sourceId in visible) latest.add(row.getString("source") to row.getString("tag"))
        }
        val failed = ArrayList<String>()
        val f = json.optJSONArray("failed") ?: JSONArray()
        for (i in 0 until f.length()) failed.add(f.getString(i))
        return Check(out, latest, failed, json.optLong("checkedAt"), rateLimited)
    }

    fun installedId(context: Context, asset: Asset, isInstalled: (String) -> Boolean): String? {
        val saved = downloads(context)
        saved.optString(asset.id, "").takeIf { it.isNotEmpty() && isInstalled(it) }?.let { return it }
        val legacy = saved.optString(asset.name, "").takeIf { it.isNotEmpty() && isInstalled(it) } ?: return null
        if (!canMigrateLegacyDownload(context, asset)) return null
        saved.remove(asset.name)
        saved.put(asset.id, legacy)
        prefs(context).edit().putString(KEY_DOWNLOADS, saved.toString()).apply()
        return legacy
    }

    private fun canMigrateLegacyDownload(context: Context, asset: Asset): Boolean {
        if (asset.repository !in sourceBuiltIns() || asset.sourceId != RepositorySources.source(asset.repository).id) return false
        val matches = cached(context)?.assets.orEmpty().filter {
            it.name == asset.name && it.repository in sourceBuiltIns() &&
                it.sourceId == RepositorySources.source(it.repository).id
        }
        return matches.size == 1 && matches.single().id == asset.id
    }

    fun isDownloaded(context: Context, id: String): Boolean {
        val d = downloads(context)
        return d.keys().asSequence().any { d.optString(it) == id }
    }

    fun recordDownload(context: Context, asset: Asset, id: String) {
        prefs(context).edit().putString(KEY_DOWNLOADS, downloads(context).put(asset.id, id).toString()).apply()
    }

    fun forget(context: Context, id: String) {
        val d = downloads(context)
        val keys = d.keys().asSequence().filter { d.optString(it) == id }.toList()
        if (keys.isEmpty()) return
        keys.forEach { d.remove(it) }
        prefs(context).edit().putString(KEY_DOWNLOADS, d.toString()).apply()
    }

    fun download(context: Context, asset: Asset, progress: (Int) -> Unit): File {
        if (asset.sha256.isEmpty()) throw IOException(context.getString(R.string.turnip_list_predates_checksums))
        val generation = if (asset.sourceId.isEmpty()) null else RepositorySources.generation(context, SOURCE_SCOPE, sourceBuiltIns()).also {
            if (!RepositorySources.isCurrent(context, SOURCE_SCOPE, sourceBuiltIns(), asset.sourceId, it)) {
                throw IOException("Driver source is hidden or unavailable")
            }
        }
        val sourceKey = asset.sourceId.ifEmpty { RepositorySources.digest(asset.source) }
        val target = File(context.cacheDir, "${asset.sha256}-$sourceKey.zip")
        val ok = Downloader.downloadFile(asset.url, target, true, asset.sha256) { f -> progress(if (f < 0) -1 else (f * 100).toInt()) }
        if (!ok) throw IOException(context.getString(R.string.turnip_download_failed))
        if (!asset.sha256.equals(Hashes.sha256(target), ignoreCase = true)) {
            FileUtils.delete(target)
            throw IOException(context.getString(R.string.turnip_checksum_mismatch))
        }
        if (generation != null && !RepositorySources.isCurrent(context, SOURCE_SCOPE, sourceBuiltIns(), asset.sourceId, generation)) {
            FileUtils.delete(target)
            throw IOException("Driver source changed while downloading")
        }
        return target
    }

    private fun downloads(context: Context): JSONObject =
        runCatching { JSONObject(prefs(context).getString(KEY_DOWNLOADS, "{}")!!) }.getOrDefault(JSONObject())

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
