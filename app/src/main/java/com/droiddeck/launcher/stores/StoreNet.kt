package com.droiddeck.launcher.stores

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * The small HTTP the storefront catalogs and account calls need: a GET or a JSON POST with the
 * right User-Agent, a bearer token when the call is signed in, and a body or null. Store URLs
 * carry their authorization in the query string or the path, so a URL is only ever logged
 * through [StoreLog.redactUrl]. Plain HttpURLConnection, like the rest of the app.
 */
internal object StoreNet {
    private const val TAG = "StoreNet"

    /** A desktop browser: GOG's catalog and Epic's store GraphQL answer it; Epic's edge refuses the bare Java UA. */
    const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    fun get(url: String, bearer: String? = null, userAgent: String = BROWSER_UA, timeoutMs: Int = 20_000, headers: Map<String, String> = emptyMap()): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json, text/plain, */*")
                if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = conn.responseCode
            if (code !in 200..299) { Log.w(TAG, "GET $code ${StoreLog.redactUrl(url)}"); null } else readAll(conn)
        } catch (e: Exception) {
            Log.w(TAG, "GET failed ${StoreLog.redactUrl(url)}: ${e.javaClass.simpleName}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    fun postJson(url: String, body: String, bearer: String? = null, userAgent: String = BROWSER_UA, timeoutMs: Int = 25_000): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
            }
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body) }
            val code = conn.responseCode
            if (code !in 200..299) { Log.w(TAG, "POST $code ${StoreLog.redactUrl(url)}"); null } else readAll(conn)
        } catch (e: Exception) {
            Log.w(TAG, "POST failed ${StoreLog.redactUrl(url)}: ${e.javaClass.simpleName}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * A 600x900 poster for [title] from SteamGridDB by name, or "" - the lookup the GOG library and
     * the Amazon poster backfill use, since those stores publish no tall art of their own.
     */
    fun sgdbPoster(title: String): String = try {
        val enc = java.net.URLEncoder.encode(title, "UTF-8")
        val search = get("https://www.steamgriddb.com/api/v2/search/autocomplete/$enc", bearer = SGDB_KEY)
        val data = search?.let { org.json.JSONObject(it).optJSONArray("data") }
        if (data == null || data.length() == 0) "" else {
            val gameId = data.getJSONObject(0).getInt("id")
            val grids = get("https://www.steamgriddb.com/api/v2/grids/game/$gameId?dimensions=600x900&mimes=image/jpeg,image/png&limit=1", bearer = SGDB_KEY)
                ?.let { org.json.JSONObject(it).optJSONArray("data") }
            if (grids == null || grids.length() == 0) "" else grids.getJSONObject(0).optString("url", "")
        }
    } catch (_: Exception) { "" }

    private const val SGDB_KEY = "cf89227f12c773bb1117b6b109ae1659"

    private fun readAll(conn: HttpURLConnection): String {
        val sb = StringBuilder()
        BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { br ->
            val buf = CharArray(8192)
            var n: Int
            while (br.read(buf).also { n = it } > 0) sb.append(buf, 0, n)
        }
        return sb.toString()
    }
}

/**
 * Keeps credentials out of the logs. A store's CDN, manifest and token URLs carry their
 * authorization in the query string (signed, time-limited tokens, `client_secret`,
 * `refresh_token`) or in the authority's userinfo; one such line in logcat or a shared log hands a
 * reader a live download. Every store URL that is written anywhere goes through [redactUrl] first.
 */
object StoreLog {
    /** `scheme://host/path` only: query, fragment and `user:pass@` dropped; "[url]" when it cannot be parsed. */
    @JvmStatic
    fun redactUrl(url: String?): String? {
        if (url == null) return null
        return try {
            var out = url
            out.indexOf('#').takeIf { it >= 0 }?.let { out = out.substring(0, it) }
            out.indexOf('?').takeIf { it >= 0 }?.let { out = out.substring(0, it) }
            val scheme = out.indexOf("://")
            if (scheme >= 0) {
                val authStart = scheme + 3
                val pathStart = out.indexOf('/', authStart)
                val authority = if (pathStart >= 0) out.substring(authStart, pathStart) else out.substring(authStart)
                val at = authority.lastIndexOf('@')
                if (at >= 0) out = out.substring(0, authStart) + authority.substring(at + 1) + (if (pathStart >= 0) out.substring(pathStart) else "")
            }
            out.ifEmpty { "[url]" }
        } catch (e: Exception) {
            "[url]"
        }
    }
}
