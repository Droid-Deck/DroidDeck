package com.droiddeck.launcher.stores

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

class CloudFile(val name: String, var modifiedMs: Long, var md5: String? = null, val readLink: String? = null)

interface CloudTransport {
    fun list(): List<CloudFile>
    fun details(file: CloudFile) {}
    fun get(file: CloudFile): Pair<ByteArray, Long>
    fun put(files: Map<String, ByteArray>): Map<String, Long>
}

private const val TIMEOUT = 30_000

private fun open(url: String, method: String, token: String?, ua: String): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = TIMEOUT
        readTimeout = TIMEOUT
        setRequestProperty("User-Agent", ua)
        if (token != null) setRequestProperty("Authorization", "Bearer $token")
    }

private fun HttpURLConnection.body(): ByteArray = inputStream.use { input ->
    ByteArrayOutputStream().also { input.copyTo(it) }.toByteArray()
}

private fun httpDate(s: String?): Long = try {
    SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.parse(s!!.trim())?.time ?: -1L
} catch (e: Exception) { -1L }

internal fun isoDate(s: String?): Long {
    if (s == null || s.length < 19) return -1L
    return try {
        java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(s.substring(0, 4).toInt(), s.substring(5, 7).toInt() - 1, s.substring(8, 10).toInt(),
                s.substring(11, 13).toInt(), s.substring(14, 16).toInt(), s.substring(17, 19).toInt())
        }.timeInMillis
    } catch (e: Exception) { -1L }
}

class NoCloudSaves : Exception("no cloud saves")

class GogCloud(private val userId: String, private val clientId: String, private val token: String) : CloudTransport {
    private val base = "https://cloudstorage.gog.com/v1/$userId/$clientId"
    private val ua = "GOG Galaxy"

    private fun key(name: String) = URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    override fun list(): List<CloudFile> {
        val c = open(base, "GET", token, ua)
        val code = c.responseCode
        if (code == 404) { c.disconnect(); return emptyList() }
        if (code !in 200..299) {
            val err = runCatching { c.errorStream?.readBytes()?.decodeToString().orEmpty() }.getOrDefault("")
            c.disconnect()
            if ("not_enabled_for_client" in err || "disabled" in err) throw NoCloudSaves()
            throw java.io.IOException("GOG cloud list HTTP $code")
        }
        val text = c.body().decodeToString().trim()
        c.disconnect()
        if (text.isEmpty() || text == "[]") return emptyList()
        if (text.startsWith("[")) {
            val a = JSONArray(text)
            return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { o ->
                val n = o.optString("name").ifEmpty { return@mapNotNull null }
                val lm = o.optLong("last_modified", 0L)
                CloudFile(n, if (lm > 1_000_000_000_000L) lm else if (lm > 0) lm * 1000 else -1L)
            }
        }
        return text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { CloudFile(it, -1L) }
    }

    override fun details(file: CloudFile) {
        if (file.modifiedMs > 0 && file.md5 != null) return
        val c = open("$base/${key(file.name)}", "HEAD", token, ua)
        if (c.responseCode in 200..299) {
            httpDate(c.getHeaderField("Last-Modified")).takeIf { it > 0 }?.let { file.modifiedMs = it }
            c.getHeaderField("ETag")?.replace("\"", "")?.trim()?.ifEmpty { null }?.let { file.md5 = it }
        }
        c.disconnect()
    }

    override fun get(file: CloudFile): Pair<ByteArray, Long> {
        val c = open("$base/${key(file.name)}", "GET", token, ua)
        if (c.responseCode !in 200..299) { val code = c.responseCode; c.disconnect(); throw java.io.IOException("GOG cloud get HTTP $code") }
        val modified = httpDate(c.getHeaderField("Last-Modified"))
        return c.body().also { c.disconnect() } to modified
    }

    override fun put(files: Map<String, ByteArray>): Map<String, Long> = files.mapValues { (name, data) ->
        val c = open("$base/${key(name)}", "PUT", token, ua)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.setFixedLengthStreamingMode(data.size)
        c.outputStream.use { it.write(data) }
        val code = c.responseCode
        c.disconnect()
        if (code !in 200..299) throw java.io.IOException("GOG cloud put HTTP $code")
        CloudFile(name, -1L).also { details(it) }.modifiedMs
    }
}

class EpicCloud(private val accountId: String, private val appName: String, private val token: String) : CloudTransport {
    override fun list(): List<CloudFile> = com.droiddeck.launcher.stores.epic.EpicSaveSync.list(token, accountId, appName)

    override fun get(file: CloudFile): Pair<ByteArray, Long> = com.droiddeck.launcher.stores.epic.EpicSaveSync.read(file)

    override fun put(files: Map<String, ByteArray>): Map<String, Long> =
        com.droiddeck.launcher.stores.epic.EpicSaveSync.write(token, accountId, appName, files)
}
