package com.droiddeck.launcher.store

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Flathub's public API (flathub.org/api/v2): the collections its front page shows, search, and an
 * app's AppStream data. Only what this device can install is kept - apps built for aarch64.
 */
object FlathubApi {
    private const val TAG = "FlathubApi"
    private const val BASE = "https://flathub.org/api/v2"
    private const val ARCH = "aarch64"
    private const val TIMEOUT_MS = 20_000

    class AppSummary(
        val id: String, val name: String, val summary: String, val icon: String?,
        val developer: String?, val verified: Boolean, val installs: Int,
    )

    class Screenshot(val thumb: String, val full: String)

    class AppDetails(
        val id: String, val name: String, val summary: String, val description: String,
        val developer: String?, val icon: String?, val version: String?, val license: String?,
        val homepage: String?, val screenshots: List<Screenshot>, val categories: List<String>,
        val runtime: String?, val downloadSize: Long, val installedSize: Long, val arches: List<String>,
    )

    /** A category as Flathub's search filters it, with the name shown for it. */
    class Category(val id: String, val label: String)

    val categories = listOf(
        Category("Game", "Games"), Category("AudioVideo", "Audio & Video"), Category("Graphics", "Graphics"),
        Category("Network", "Internet"), Category("Office", "Office"), Category("Development", "Developer"),
        Category("Education", "Education"), Category("Science", "Science"), Category("System", "System"),
        Category("Utility", "Utilities"),
    )

    /** Front-page collections: popular, trending, recently-updated, recently-added. */
    fun collection(name: String, count: Int = 30): List<AppSummary>? {
        // The collections are not filtered by architecture; ask for more and keep what fits.
        val body = get("$BASE/collection/$name?page=1&per_page=${count * 2}") ?: return null
        return hits(body)?.take(count)
    }

    /** Search, optionally within one category (Flathub's main_categories facet). */
    fun search(query: String, category: String? = null, count: Int = 48): List<AppSummary>? {
        val filters = JSONArray().put(JSONObject().put("filterType", "arches").put("value", ARCH))
        if (category != null) filters.put(JSONObject().put("filterType", "main_categories").put("value", category))
        val payload = JSONObject().put("query", query).put("filters", filters)
            .put("hits_per_page", count).put("page", 1)
        val body = post("$BASE/search", payload.toString()) ?: return null
        return hits(body)
    }

    /** Flathub's own copy of an app's icon: for apps that export only an SVG, which is not drawn here. */
    fun iconUrl(id: String) = "https://dl.flathub.org/media/icons/128x128/$id.png"

    fun details(id: String): AppDetails? {
        val app = get("$BASE/appstream/${enc(id)}")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val summary = get("$BASE/summary/${enc(id)}?arch=$ARCH")?.let { runCatching { JSONObject(it) }.getOrNull() }
        val shots = app.optJSONArray("screenshots")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val sizes = arr.optJSONObject(i)?.optJSONArray("sizes") ?: return@mapNotNull null
                val all = (0 until sizes.length()).mapNotNull { sizes.optJSONObject(it) }
                    .map { (it.optString("width").toIntOrNull() ?: 0) to it.optString("src") }
                    .filter { it.second.startsWith("http") }.sortedBy { it.first }
                if (all.isEmpty()) null
                else Screenshot(all.firstOrNull { it.first >= 600 }?.second ?: all.last().second,
                                all.firstOrNull { it.first >= 1200 }?.second ?: all.last().second)
            }
        } ?: emptyList()
        val release = app.optJSONArray("releases")?.optJSONObject(0)
        val cats = app.optJSONArray("categories")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        val arches = summary?.optJSONArray("arches")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        return AppDetails(
            id = app.optString("id", id),
            name = app.optString("name", id),
            summary = app.optString("summary"),
            description = plainText(app.optString("description")),
            developer = app.optString("developer_name").takeIf { it.isNotEmpty() && it != "null" },
            icon = app.optString("icon").takeIf { it.startsWith("http") },
            version = release?.optString("version")?.takeIf { it.isNotEmpty() && it != "null" },
            license = app.optString("project_license").takeIf { it.isNotEmpty() && it != "null" },
            homepage = app.optJSONObject("urls")?.optString("homepage")?.takeIf { it.startsWith("http") },
            screenshots = shots,
            categories = cats,
            runtime = summary?.optJSONObject("metadata")?.optString("runtime")?.takeIf { it.isNotEmpty() },
            downloadSize = summary?.optLong("download_size") ?: 0L,
            installedSize = summary?.optLong("installed_size") ?: 0L,
            arches = arches,
        )
    }

    private fun hits(body: String): List<AppSummary>? = try {
        val arr = JSONObject(body).getJSONArray("hits")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val arches = o.optJSONArray("arches")
            if (arches != null && (0 until arches.length()).none { arches.optString(it) == ARCH }) return@mapNotNull null
            if (o.optString("type", "desktop-application") != "desktop-application") return@mapNotNull null
            AppSummary(
                id = o.optString("app_id"),
                name = o.optString("name"),
                summary = o.optString("summary"),
                icon = o.optString("icon").takeIf { it.startsWith("http") },
                developer = o.optString("developer_name").takeIf { it.isNotEmpty() && it != "null" },
                verified = o.optBoolean("verification_verified"),
                installs = o.optInt("installs_last_month"),
            )
        }.filter { it.id.isNotEmpty() }
    } catch (e: Exception) {
        Log.w(TAG, "hits: $e"); null
    }

    /** AppStream descriptions are a small HTML subset: paragraphs and lists become lines. */
    internal fun plainText(html: String): String = html
        .replace(Regex("(?i)<li>\\s*"), "• ")
        .replace(Regex("(?i)</(p|li|ul|ol)>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .lines().map { it.trim().replace(Regex("\\s+"), " ") }
        .joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String? = request(url, null)

    private fun post(url: String, json: String): String? = request(url, json)

    private fun request(url: String, json: String?): String? {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                if (json != null) {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(json.toByteArray()) }
                }
            }
            if (c.responseCode / 100 != 2) { Log.w(TAG, "$url -> HTTP ${c.responseCode}"); null }
            else c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "$url: $e"); null
        } finally {
            c?.disconnect()
        }
    }
}
