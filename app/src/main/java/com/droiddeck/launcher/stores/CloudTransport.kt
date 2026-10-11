/*
 * The two stores' cloud-save services, after Bannerlator's GogCloudSaveManager and
 * EpicCloudSaveManager (device-proven there, August 2026): GOG's cloudstorage (one object per
 * file, Last-Modified and an MD5 ETag on a HEAD) and Epic's savesync data storage (a listing with
 * lastModified and signed read links; signed write links asked for per upload).
 */
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

/** One file in a game's cloud: its key (a path with '/'), when it was written (-1 unknown) and its MD5 when known. */
class CloudFile(val name: String, var modifiedMs: Long, var md5: String? = null, val readLink: String? = null)

/** A store's cloud for one game. Every call is blocking network. */
interface CloudTransport {
    fun list(): List<CloudFile>
    /** Fills in [CloudFile.modifiedMs] / [CloudFile.md5] where the listing left them out. */
    fun details(file: CloudFile) {}
    /** The file's bytes and its cloud time (-1 unknown). */
    fun get(file: CloudFile): Pair<ByteArray, Long>
    /** Writes [files] (key → bytes); the cloud times assigned, where the service says (-1 unknown). */
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

/** Thrown when the store says the game has no cloud saves for this client. */
class NoCloudSaves : Exception("no cloud saves")

/** GOG cloudstorage: `/v1/<userId>/<clientId>[/<key>]`. */
class GogCloud(private val userId: String, private val clientId: String, private val token: String) : CloudTransport {
    private val base = "https://cloudstorage.gog.com/v1/$userId/$clientId"
    private val ua = "GOG Galaxy"

    /** A path segment: '+' from a space is wrong in a path (it stores a second, '+'-named copy). */
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
        // A plain newline-separated list of keys (checked on device); a JSON array is read too.
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
        // The server stamps the object itself; that time, so the next sync sees the two as equal.
        CloudFile(name, -1L).also { details(it) }.modifiedMs
    }
}

/** Epic savesync: `/api/v1/access/egstore/savesync/<accountId>/<appName>/`. */
class EpicCloud(private val accountId: String, private val appName: String, private val token: String) : CloudTransport {
    private val base = "https://datastorage-public-service-liveegs.live.use1a.on.epicgames.com/api/v1/access/egstore/savesync/$accountId/$appName/"
    private val ua = "EpicGamesLauncher/15.17.1-22692490"

    override fun list(): List<CloudFile> {
        val c = open(base, "GET", token, ua)
        val code = c.responseCode
        if (code == 404) { c.disconnect(); return emptyList() }
        if (code !in 200..299) { c.disconnect(); throw java.io.IOException("Epic cloud list HTTP $code") }
        val root = JSONObject(c.body().decodeToString().ifEmpty { "{}" })
        c.disconnect()
        val files = root.optJSONObject("files") ?: return emptyList()
        return files.keys().asSequence().mapNotNull { k ->
            val e = files.optJSONObject(k) ?: return@mapNotNull null
            CloudFile(strip(k), isoDate(e.optString("lastModified", null)), e.optString("hash", "").ifEmpty { null }, e.optString("readLink", "").ifEmpty { null })
        }.toList()
    }

    /** The listing names a file `<...>/<appName>/<key>`; the key is what is under the save folder. */
    private fun strip(key: String): String {
        val marker = "/$appName/"
        val i = key.indexOf(marker)
        if (i >= 0) return key.substring(i + marker.length)
        return key.removePrefix("$appName/")
    }

    override fun get(file: CloudFile): Pair<ByteArray, Long> {
        val link = file.readLink ?: throw java.io.IOException("Epic cloud: no read link")
        val c = open(link, "GET", null, ua)
        if (c.responseCode !in 200..299) { val code = c.responseCode; c.disconnect(); throw java.io.IOException("Epic cloud get HTTP $code") }
        return c.body().also { c.disconnect() } to file.modifiedMs
    }

    override fun put(files: Map<String, ByteArray>): Map<String, Long> {
        if (files.isEmpty()) return emptyMap()
        val c = open(base, "POST", token, ua)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        val req = JSONObject().put("files", JSONArray(files.keys.toList())).toString().toByteArray()
        c.outputStream.use { it.write(req) }
        if (c.responseCode !in 200..299) { val code = c.responseCode; c.disconnect(); throw java.io.IOException("Epic cloud write links HTTP $code") }
        val links = JSONObject(c.body().decodeToString()).optJSONObject("files") ?: JSONObject()
        c.disconnect()
        val out = HashMap<String, Long>()
        for (k in links.keys()) {
            val link = links.optJSONObject(k)?.optString("writeLink", "")?.ifEmpty { null } ?: continue
            val name = strip(k)
            val data = files[name] ?: files[k] ?: continue
            val p = open(link, "PUT", null, ua)
            p.doOutput = true
            p.setRequestProperty("Content-Type", "application/octet-stream")
            p.setFixedLengthStreamingMode(data.size)
            p.outputStream.use { it.write(data) }
            val code = p.responseCode
            p.disconnect()
            if (code !in 200..299) throw java.io.IOException("Epic cloud put HTTP $code")
            out[name] = -1L
        }
        return out
    }
}
