package com.droiddeck.launcher.stores.gamejolt

import com.droiddeck.launcher.stores.StoreNet
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal class GameJoltAnswer(val code: Int, val json: JSONObject?) {
    val ok: Boolean get() = code in 200..299
    val payload: JSONObject? get() = json?.optJSONObject("payload")
    val user: JSONObject? get() = json?.optJSONObject("user")
}

internal object GameJoltApi {
    const val SITE = "https://gamejolt.com"
    const val LOGIN = "$SITE/login"

    private const val BASE = "$SITE/site-api"
    private const val MAX_BYTES = 6_000_000

    fun get(path: String, cookies: String = ""): GameJoltAnswer = send("GET", path, cookies, null)

    fun post(path: String, cookies: String = "", body: String = ""): GameJoltAnswer = send("POST", path, cookies, body)

    fun overview(gameId: String, cookies: String): GameJoltAnswer = get("/web/discover/games/overview/$gameId", cookies)

    fun search(query: String, page: Int, cookies: String): GameJoltAnswer =
        get("/web/search/games?q=${URLEncoder.encode(query, "UTF-8")}&page=$page", cookies)

    fun browse(queryString: String, cookies: String): GameJoltAnswer = get("/web/discover/games?$queryString", cookies)

    fun downloadUrl(buildId: String, cookies: String): String? =
        post("/web/discover/games/builds/get-download-url/$buildId", cookies).payload?.optString("url")?.takeIf { it.startsWith("https://") }

    fun pageUrl(slug: String, id: String): String = "$SITE/games/$slug/$id"

    private fun readBounded(input: java.io.InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < MAX_BYTES) {
            val read = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun send(method: String, path: String, cookies: String, body: String?): GameJoltAnswer {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
                connectTimeout = 25_000
                readTimeout = 25_000
                requestMethod = method
                setRequestProperty("User-Agent", StoreNet.BROWSER_UA)
                setRequestProperty("Accept", "application/json")
                if (cookies.isNotBlank()) setRequestProperty("Cookie", cookies)
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }
            }
            if (body != null) conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val text = stream?.use { readBounded(it) }.orEmpty()
            GameJoltAnswer(code, runCatching { JSONObject(text) }.getOrNull())
        } catch (e: Exception) {
            GameJoltAnswer(0, null)
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
