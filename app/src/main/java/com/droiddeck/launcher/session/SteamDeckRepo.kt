package com.droiddeck.launcher.session

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** steamdeckrepo.com's public boot video list, the same one its website and its own manager app read. */
object SteamDeckRepo {
    private const val TAG = "SteamDeckRepo"
    const val BASE = "https://steamdeckrepo.com"

    /** The site shows twelve movies a page. */
    const val PAGE_SIZE = 12
    const val MAX_DOWNLOAD_BYTES = 120L shl 20
    private const val MAX_JSON_BYTES = 1 shl 20
    private const val USER_AGENT = "DroidDeck (+https://github.com/Droid-Deck/DroidDeck)"
    private const val CACHE_MS = 10 * 60 * 1000L

    enum class Sort(val key: String) {
        TRENDING("trending"), DOWNLOADS("downloads-desc"), LIKES("likes-desc"), NEWEST("created_at-desc"),
    }

    /** `null` is every device. */
    val DEVICES = listOf("steam_deck", "steam_machine")

    class Post(
        val id: String, val title: String, val content: String, val author: String, val thumbnail: String, val video: String,
        val seconds: Int, val likes: Int, val downloads: Int, val devices: List<String>, val url: String,
    )

    sealed class Page {
        class Ok(val posts: List<Post>) : Page()
        object Failed : Page()
    }

    fun parse(json: String): List<Post>? {
        val array = runCatching { JSONObject(json).optJSONArray("posts") }.getOrNull() ?: return null
        val out = ArrayList<Post>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val video = o.optString("video")
            if (id.isEmpty() || !video.startsWith("https://")) continue
            val devices = o.optJSONArray("devices")?.let { d -> (0 until d.length()).map { d.optString(it) } }.orEmpty()
            out.add(
                Post(
                    id = id, title = o.optString("title").ifBlank { id }, content = o.optString("content"),
                    author = o.optJSONObject("user")?.optString("steam_name").orEmpty(),
                    thumbnail = o.optString("thumbnail").takeIf { it.startsWith("https://") }.orEmpty(), video = video,
                    seconds = o.optInt("video_duration"), likes = o.optInt("likes"), downloads = o.optInt("downloads"),
                    devices = devices, url = o.optString("url"),
                ),
            )
        }
        return out
    }

    fun url(sort: Sort, device: String?, search: String, page: Int = 1): String {
        val query = StringBuilder("sort=").append(sort.key)
        if (device != null) query.append("&device=").append(URLEncoder.encode(device, "UTF-8"))
        val term = search.trim()
        if (term.isNotEmpty()) query.append("&search=").append(URLEncoder.encode(term, "UTF-8").replace("+", "%20"))
        if (page > 1) query.append("&page=").append(page)
        return "$BASE/api/posts?$query"
    }

    private class Cached(val at: Long, val page: Page.Ok)
    private val cache = HashMap<String, Cached>()

    fun fetch(sort: Sort, device: String?, search: String, page: Int = 1, now: Long = System.currentTimeMillis()): Page {
        val url = url(sort, device, search, page)
        synchronized(cache) { cache[url]?.takeIf { now - it.at < CACHE_MS }?.let { return it.page } }
        val body = get(url) ?: return Page.Failed
        val posts = parse(body) ?: return Page.Failed
        val result = Page.Ok(posts.take(PAGE_SIZE))
        synchronized(cache) { cache[url] = Cached(now, result) }
        return result
    }

    /** Reads [input] to its end, or null as soon as it holds more than [limit] bytes, so a huge answer is never kept whole. */
    internal fun readLimited(input: java.io.InputStream, limit: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > limit) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun get(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode !in 200..299) null else connection.inputStream.use { readLimited(it, MAX_JSON_BYTES)?.toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            Log.w(TAG, "request failed: ${e.javaClass.simpleName}")
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /** Fetches a small picture such as a cover; null when it cannot be had. */
    fun bytes(url: String, limit: Int = 4 shl 20): ByteArray? {
        if (!url.startsWith("https://")) return null
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", USER_AGENT)
            }
            if (connection.responseCode !in 200..299 || connection.contentLengthLong > limit) null
            else connection.inputStream.use { input -> readLimited(input, limit)?.takeIf { it.isNotEmpty() } }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /**
     * Downloads a movie to [target] through the site's own download address, so the site counts it like a download from
     * its website, and falls back to the video's address. True when a file was saved.
     */
    fun download(post: Post, target: File, onProgress: (Int) -> Unit = {}): Boolean {
        for (url in listOf("$BASE/post/download/${post.id}", post.video)) {
            if (save(url, target, onProgress)) return true
        }
        return false
    }

    private fun save(url: String, target: File, onProgress: (Int) -> Unit): Boolean {
        var connection: HttpURLConnection? = null
        val staged = File(target.parentFile, target.name + ".part")
        return try {
            target.parentFile?.mkdirs()
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
            }
            if (connection.responseCode !in 200..299) return false
            val type = connection.contentType.orEmpty().lowercase()
            if (type.startsWith("text/") || type.contains("json")) return false
            val total = connection.contentLengthLong
            if (total > MAX_DOWNLOAD_BYTES) return false
            var done = 0L
            var lastPercent = -1
            connection.inputStream.use { input ->
                staged.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        done += n
                        if (done > MAX_DOWNLOAD_BYTES) return false
                        output.write(buffer, 0, n)
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != lastPercent) { lastPercent = percent; onProgress(percent) }
                        }
                    }
                }
            }
            done > 0 && (total <= 0 || done == total) && staged.renameTo(target)
        } catch (e: Exception) {
            Log.w(TAG, "download failed: ${e.javaClass.simpleName}")
            false
        } finally {
            runCatching { connection?.disconnect() }
            staged.delete()
        }
    }
}
