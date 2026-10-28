package com.droiddeck.launcher.artifact

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import com.droiddeck.launcher.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

object GitHubArtifacts {
    private const val PAGE_SIZE = 30
    private const val MAX_JSON_BYTES = 2 * 1024 * 1024
    private const val MAX_CACHE_BYTES = 12 * 1024 * 1024
    private const val MAX_REDIRECTS = 3
    private const val DEFAULT_RETRY_MS = 3 * 60_000L

    class RateLimitException(val retryAtMillis: Long) : IOException(
        "GitHub is rate limiting source refreshes. Try again after a few minutes.",
    )

    class HttpException(val status: Int) : IOException("GitHub metadata returned HTTP $status")

    private val retryAtByHost = HashMap<String, Long>()
    internal var currentTimeMillis: () -> Long = System::currentTimeMillis

    internal fun clearRateLimits() = synchronized(retryAtByHost) { retryAtByHost.clear() }

    @JvmStatic fun isRateLimited(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.take(16).any { it is RateLimitException }

    @JvmStatic fun failureMessage(context: Context, error: Throwable): String =
        if (isRateLimited(error)) context.getString(R.string.sources_github_rate_limited)
        else error.message ?: error.javaClass.simpleName

    data class Asset(val id: Long, val name: String, val url: String, val size: Long, val sha256: String)
    data class Release(
        val id: Long, val tag: String, val name: String, val body: String,
        val publishedAt: String, val prerelease: Boolean, val assets: List<Asset>,
    )

    data class ArtifactRef(
        val scope: String, val sourceId: String, val repository: String, val repositoryId: Long,
        val releaseId: Long, val tag: String, val assetId: Long, val name: String,
        val url: String, val size: Long, val sha256: String,
    )

    internal var openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }

    @JvmStatic fun release(
        context: Context, key: String, builtIns: List<String>, source: RepositorySources.Source, tag: String,
    ): Release {
        validateTag(tag)
        val generation = RepositorySources.generation(context, key, builtIns)
        val bound = resolve(context, key, builtIns, source, generation)
        val url = "https://api.github.com/repositories/${bound.repositoryId}/releases/tags/${segment(tag)}"
        val json = JSONObject(fetch(url, MAX_JSON_BYTES) { sameApiIdentity(it, bound.repositoryId!!) }.toString(Charsets.UTF_8))
        require(!json.optBoolean("draft", false) && json.getString("tag_name") == tag) { "GitHub release tag does not match" }
        val release = parseRelease(bound, json)
        return RepositorySources.whileCurrent(context, key, builtIns, source, generation) { release }
    }

    @JvmStatic @JvmOverloads fun refreshReleases(
        context: Context, key: String, builtIns: List<String>, source: RepositorySources.Source,
        maxPages: Int = 3,
    ): List<Release> {
        require(maxPages in 1..5) { "Invalid release page limit" }
        val generation = RepositorySources.generation(context, key, builtIns)
        val bound = resolve(context, key, builtIns, source, generation)
        val releases = ArrayList<Release>()
        val raw = JSONArray()
        for (page in 1..maxPages) {
            requireCurrent(context, key, builtIns, source, generation)
            val url = "https://api.github.com/repositories/${bound.repositoryId}/releases?per_page=$PAGE_SIZE&page=$page"
            val array = JSONArray(fetch(url, MAX_JSON_BYTES) { sameApiIdentity(it, bound.repositoryId!!) }.toString(Charsets.UTF_8))
            if (array.length() > PAGE_SIZE) throw IOException("GitHub release page exceeds limit")
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                if (item.optBoolean("draft", false)) continue
                val release = parseRelease(bound, item)
                releases.add(release)
                raw.put(encodeRelease(release))
            }
            if (array.length() < PAGE_SIZE) break
        }
        val cache = JSONObject().put("schema", 1).put("repository", bound.repository)
            .put("repositoryId", bound.repositoryId).put("releases", raw)
            .toString().toByteArray(Charsets.UTF_8)
        if (cache.size > MAX_CACHE_BYTES) throw IOException("Release catalog exceeds size limit")
        RepositorySources.whileCurrent(context, key, builtIns, source, generation) {
            RepositorySources.atomicWrite(cacheFile(context, key, bound), cache)
        }
        return releases
    }

    @JvmStatic fun cachedReleases(
        context: Context, key: String, builtIns: List<String>, source: RepositorySources.Source,
    ): List<Release> = synchronized(RepositorySources.lock) {
        val current = RepositorySources.visible(context, key, builtIns).firstOrNull {
            it.id == source.id && it.repository == source.repository
        } ?: return@synchronized emptyList()
        if (current.repositoryId == null) return@synchronized emptyList()
        runCatching {
            val json = AtomicFile(cacheFile(context, key, current)).openRead().use {
                JSONObject(RepositorySources.readBounded(it, MAX_CACHE_BYTES).toString(Charsets.UTF_8))
            }
            require(json.getInt("schema") == 1 && json.getString("repository") == current.repository &&
                json.getLong("repositoryId") == current.repositoryId)
            val rows = json.getJSONArray("releases")
            require(rows.length() <= PAGE_SIZE * 5)
            (0 until rows.length()).map { parseRelease(current, rows.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    @JvmStatic @JvmOverloads fun readRepositoryFile(
        context: Context, key: String, builtIns: List<String>, source: RepositorySources.Source,
        path: String, ref: String?, maxBytes: Int = 1024 * 1024,
        expectedGeneration: Long = RepositorySources.generation(context, key, builtIns),
    ): ByteArray {
        require(maxBytes in 1..MAX_JSON_BYTES)
        validatePath(path)
        val branch = RepositorySources.normalizeCatalogBranch(ref)
        val generation = expectedGeneration
        requireCurrent(context, key, builtIns, source, generation)
        val bound = resolve(context, key, builtIns, source, generation)
        val encodedPath = path.split('/').joinToString("/") { segment(it) }
        val url = buildString {
            append("https://api.github.com/repositories/${bound.repositoryId}/contents/$encodedPath")
            branch?.let { append("?ref=${segment(it)}") }
        }
        val json = JSONObject(fetch(url, minOf(MAX_CACHE_BYTES, maxBytes * 2 + 8192)) {
            sameApiIdentity(it, bound.repositoryId!!)
        }.toString(Charsets.UTF_8))
        require(json.getString("type") == "file" && json.getString("path") == path &&
            json.getString("encoding") == "base64") { "GitHub content is not the requested file" }
        val size = json.getLong("size")
        require(size in 0..maxBytes.toLong()) { "Repository file exceeds size limit" }
        val data = Base64.decode(json.getString("content"), Base64.DEFAULT)
        require(data.size.toLong() == size) { "Repository file size does not match" }
        requireCurrent(context, key, builtIns, source, generation)
        return data
    }

    private fun readReleaseAsset(
        source: RepositorySources.Source, tag: String, asset: Asset, maxBytes: Int = 1024 * 1024,
    ): ByteArray {
        require(maxBytes in 1..MAX_JSON_BYTES && asset.size in 1..maxBytes.toLong()) { "Release metadata exceeds size limit" }
        require(!source.hidden && source.repositoryId != null && source.repositoryId > 0) { "Repository source is hidden or unbound" }
        validateAsset(source.repository, tag, asset)
        val initial = URI(asset.url)
        val data = fetch(asset.url, maxBytes) { candidate ->
            validHttps(candidate) && (candidate == initial || candidate.host.lowercase(Locale.ROOT) in ASSET_HOSTS)
        }
        if (data.size.toLong() != asset.size || RepositorySources.sha256(data) != asset.sha256) {
            throw IOException("Release metadata digest or size does not match")
        }
        return data
    }

    @JvmStatic @JvmOverloads fun readReleaseAsset(
        context: Context, key: String, builtIns: List<String>, source: RepositorySources.Source,
        tag: String, asset: Asset, maxBytes: Int = 1024 * 1024,
    ): ByteArray {
        val generation = RepositorySources.generation(context, key, builtIns)
        requireCurrent(context, key, builtIns, source, generation)
        val data = readReleaseAsset(source, tag, asset, maxBytes)
        requireCurrent(context, key, builtIns, source, generation)
        return data
    }

    @JvmStatic fun downloadReleaseAsset(
        context: Context, key: String, builtIns: List<String>, source: RepositorySources.Source,
        tag: String, asset: Asset, destination: File, maxBytes: Long,
    ): File {
        require(maxBytes in 1..8L * 1024 * 1024 * 1024 && asset.size in 1..maxBytes) { "Asset exceeds size limit" }
        require(!source.hidden && source.repositoryId != null && source.repositoryId > 0) { "Repository source is hidden or unbound" }
        validateAsset(source.repository, tag, asset)
        val generation = RepositorySources.generation(context, key, builtIns)
        requireCurrent(context, key, builtIns, source, generation)
        if (matchesAsset(destination, asset)) {
            return RepositorySources.whileCurrent(context, key, builtIns, source, generation) { destination }
        }
        val parent = destination.absoluteFile.parentFile ?: throw IOException("Missing download directory")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create download directory")
        val partial = File.createTempFile(".github-", ".part", parent)
        try {
            val initial = URI(asset.url)
            withResponse(asset.url, { candidate ->
                validHttps(candidate) && (candidate == initial || candidate.host.lowercase(Locale.ROOT) in ASSET_HOSTS)
            }) { connection ->
                val length = connection.contentLengthLong
                if (length >= 0 && length != asset.size) throw IOException("Asset size does not match")
                val hash = MessageDigest.getInstance("SHA-256")
                var count = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(partial).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            if (read == 0) continue
                            count += read
                            if (count > asset.size) throw IOException("Asset exceeds declared size")
                            hash.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                        output.fd.sync()
                    }
                }
                val actualHash = hash.digest().joinToString("") { "%02x".format(it) }
                if (count != asset.size || actualHash != asset.sha256) throw IOException("Asset digest or size does not match")
            }
            RepositorySources.whileCurrent(context, key, builtIns, source, generation) {
                if (!partial.renameTo(destination)) throw IOException("Cannot publish downloaded asset")
            }
            return destination
        } finally {
            partial.delete()
        }
    }

    private fun matchesAsset(file: File, asset: Asset): Boolean = runCatching {
        if (!file.isFile || file.length() != asset.size) return@runCatching false
        val hash = MessageDigest.getInstance("SHA-256")
        var count = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                if (read == 0) continue
                count += read
                if (count > asset.size) return@runCatching false
                hash.update(buffer, 0, read)
            }
        }
        count == asset.size && hash.digest().joinToString("") { "%02x".format(it) } == asset.sha256
    }.getOrDefault(false)

    @JvmStatic fun artifactRef(
        key: String, source: RepositorySources.Source, release: Release, asset: Asset,
    ): ArtifactRef {
        validateAsset(source.repository, release.tag, asset)
        require(release.assets.contains(asset)) { "Asset is not in this release" }
        val repositoryId = source.repositoryId ?: throw IllegalArgumentException("Repository identity is not bound")
        return ArtifactRef(key, source.id, source.repository, repositoryId, release.id, release.tag,
            asset.id, asset.name, asset.url, asset.size, asset.sha256)
    }

    @JvmStatic fun releaseAssetUrl(repository: String, tag: String, name: String): String {
        val repo = RepositorySources.canonicalRepository(repository)
        validateTag(tag)
        validateAssetName(name)
        val encodedTag = tag.split('/').joinToString("/") { segment(it) }
        return "https://github.com/$repo/releases/download/$encodedTag/${segment(name)}"
    }

    @JvmStatic fun releaseAssetUrlMatches(repository: String, tag: String, name: String, url: String): Boolean = runCatching {
        val expected = URI(releaseAssetUrl(repository, tag, name))
        val actual = URI(url)
        validHttps(actual) && actual.host.equals("github.com", true) && actual.rawQuery == null &&
            actual.rawPath.substringBefore("/releases/").equals(expected.rawPath.substringBefore("/releases/"), true) &&
            actual.path.substringAfter("/releases/", "") == expected.path.substringAfter("/releases/", "")
    }.getOrDefault(false)

    private fun resolve(
        context: Context, key: String, builtIns: List<String>, source: RepositorySources.Source,
        generation: Long,
    ): RepositorySources.Source {
        requireCurrent(context, key, builtIns, source, generation)
        val repo = RepositorySources.canonicalRepository(source.repository)
        val url = "https://api.github.com/repos/$repo"
        val json = JSONObject(fetch(url, 128 * 1024) { sameApiRepository(it, repo) }.toString(Charsets.UTF_8))
        if (RepositorySources.canonicalRepository(json.getString("full_name")) != repo) {
            throw IOException("GitHub repository name changed")
        }
        return RepositorySources.bind(context, key, builtIns, source, json.getLong("id"), generation)
    }

    private fun requireCurrent(
        context: Context, key: String, builtIns: List<String>, source: RepositorySources.Source, generation: Long,
    ) {
        if (!RepositorySources.isCurrent(context, key, builtIns, source, generation)) {
            throw IOException("Repository source changed while checking")
        }
    }

    private fun cacheFile(context: Context, key: String, source: RepositorySources.Source) =
        File(RepositorySources.cacheDirectory(context, key, source), "releases.json")

    private fun parseRelease(source: RepositorySources.Source, json: JSONObject): Release {
        val id = json.getLong("id")
        val tag = json.getString("tag_name")
        require(id > 0)
        validateTag(tag)
        val assetsJson = json.getJSONArray("assets")
        require(assetsJson.length() <= 200) { "Release has too many assets" }
        val seen = HashSet<String>()
        val assets = ArrayList<Asset>()
        for (index in 0 until assetsJson.length()) {
            val item = assetsJson.getJSONObject(index)
            val digest = item.optString("digest").lowercase(Locale.ROOT)
            if (!Regex("sha256:[0-9a-f]{64}").matches(digest)) continue
            val asset = Asset(item.getLong("id"), item.getString("name"), item.getString("browser_download_url"),
                item.getLong("size"), digest.removePrefix("sha256:"))
            validateAsset(source.repository, tag, asset)
            require(seen.add(asset.name)) { "Duplicate release asset" }
            assets.add(asset)
        }
        return Release(id, tag, json.optString("name", tag).take(1024), json.optString("body").take(128 * 1024),
            json.optString("published_at"), json.optBoolean("prerelease", false), assets)
    }

    private fun encodeRelease(release: Release): JSONObject {
        val assets = JSONArray()
        release.assets.forEach { asset -> assets.put(JSONObject().put("id", asset.id).put("name", asset.name)
            .put("browser_download_url", asset.url).put("size", asset.size).put("digest", "sha256:${asset.sha256}")) }
        return JSONObject().put("id", release.id).put("tag_name", release.tag).put("name", release.name)
            .put("body", release.body).put("published_at", release.publishedAt).put("prerelease", release.prerelease)
            .put("assets", assets)
    }

    private fun validateAsset(repository: String, tag: String, asset: Asset) {
        require(asset.id > 0 && asset.size > 0 && Regex("[0-9a-f]{64}").matches(asset.sha256)) {
            "Asset must have an identity, size, and SHA-256 digest"
        }
        require(releaseAssetUrlMatches(repository, tag, asset.name, asset.url)) {
            "Asset URL is not bound to the configured repository, tag, and filename"
        }
    }

    private fun validateTag(tag: String) {
        require(tag.isNotEmpty() && tag.length <= 256 &&
            tag.split('/').all { it.isNotEmpty() && it != "." && it != ".." } &&
            tag.none { it == '\\' || it.isISOControl() }) { "Invalid release tag" }
    }

    private fun validateAssetName(name: String) {
        require(name.isNotEmpty() && name.length <= 240 && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it.isISOControl() }) { "Invalid release asset filename" }
    }

    private fun validatePath(path: String) {
        require(path.isNotEmpty() && path.length <= 512 && path.none { it == '\\' || it == '%' || it.isISOControl() } &&
            path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "Invalid repository file path" }
    }

    private fun segment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun sameApiRepository(uri: URI, repository: String): Boolean {
        return sameApiPath(uri, "/repos/$repository")
    }

    private fun sameApiIdentity(uri: URI, repositoryId: Long): Boolean {
        return sameApiPath(uri, "/repositories/$repositoryId")
    }

    private fun sameApiPath(uri: URI, prefix: String): Boolean {
        if (!validHttps(uri) || !uri.host.equals("api.github.com", true)) return false
        if (uri.path.contains('\\') || uri.path.split('/').any { it == "." || it == ".." }) return false
        val path = uri.rawPath.lowercase(Locale.ROOT)
        return path == prefix || path.startsWith("$prefix/")
    }

    private fun validHttps(uri: URI): Boolean = uri.scheme.equals("https", true) &&
        uri.host != null && uri.rawUserInfo == null && uri.port == -1 && uri.rawFragment == null &&
        !uri.toASCIIString().contains('\\')

    internal fun fetch(url: String, maxBytes: Int, allowed: (URI) -> Boolean): ByteArray =
        withResponse(url, allowed) { connection ->
            val length = connection.contentLengthLong
            if (length > maxBytes) throw IOException("Metadata exceeds size limit")
            connection.inputStream.use { RepositorySources.readBounded(it, maxBytes) }
        }

    private fun <T> withResponse(url: String, allowed: (URI) -> Boolean, receive: (HttpURLConnection) -> T): T {
        var current = URI(url)
        for (hop in 0..MAX_REDIRECTS) {
            if (!validHttps(current) || !allowed(current)) throw IOException("Untrusted metadata URL")
            val host = current.host.lowercase(Locale.ROOT)
            synchronized(retryAtByHost) {
                retryAtByHost[host]?.let { retryAt ->
                    if (currentTimeMillis() < retryAt) throw RateLimitException(retryAt)
                    retryAtByHost.remove(host)
                }
            }
            val connection = openConnection(current.toURL())
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("User-Agent", "DroidDeck")
                if (current.host.equals("api.github.com", true)) {
                    connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                }
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    if (hop == MAX_REDIRECTS) throw IOException("Too many metadata redirects")
                    val location = connection.getHeaderField("Location") ?: throw IOException("Missing metadata redirect")
                    current = current.resolve(location)
                    continue
                }
                if (status != 200) {
                    rateLimit(connection, status)?.let { error ->
                        synchronized(retryAtByHost) {
                            retryAtByHost[host] = maxOf(retryAtByHost[host] ?: 0L, error.retryAtMillis)
                        }
                        throw error
                    }
                    throw HttpException(status)
                }
                return receive(connection)
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many metadata redirects")
    }

    private fun rateLimit(connection: HttpURLConnection, status: Int): RateLimitException? {
        if (status != 403 && status != 429) return null
        val remaining = connection.getHeaderField("X-RateLimit-Remaining")?.trim()?.toLongOrNull()
        val retryAfter = connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
        val message = runCatching {
            connection.errorStream?.use { input ->
                JSONObject(RepositorySources.readBounded(input, 16 * 1024).toString(Charsets.UTF_8))
                    .optString("message").lowercase(Locale.ROOT)
            }.orEmpty()
        }.getOrDefault("")
        if (status != 429 && remaining != 0L && retryAfter == null &&
            "rate limit" !in message && "abuse detection" !in message) return null
        val now = currentTimeMillis()
        val reset = if (remaining == 0L) connection.getHeaderField("X-RateLimit-Reset")?.trim()
            ?.toLongOrNull()?.takeIf { it in 1..Long.MAX_VALUE / 1000 }?.times(1000) else null
        val retryAt = retryAfter?.takeIf { it <= (Long.MAX_VALUE - now) / 1000 }?.let { now + it * 1000 }
        val advertised = listOfNotNull(reset, retryAt).maxOrNull()
        return RateLimitException(advertised?.takeIf { it > now } ?: now + DEFAULT_RETRY_MS)
    }

    private val ASSET_HOSTS = setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com")
}
