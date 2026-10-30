package com.droiddeck.launcher.stores

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.CRC32
import java.util.zip.Deflater
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

internal fun isGzip(data: ByteArray) =
    data.size >= 10 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte() && data[2] == 0x08.toByte()

internal fun gunzip(data: ByteArray): ByteArray? = try {
    java.util.zip.GZIPInputStream(data.inputStream()).use { it.readBytes() }
} catch (e: Exception) { null }

/** Same gzip GOG uses on PUT: deflate level 6, mtime 0, OS Unix. */
internal fun gzipGogPut(data: ByteArray): ByteArray {
    val deflater = Deflater(6, true)
    val raw = ByteArrayOutputStream()
    try {
        deflater.setInput(data)
        deflater.finish()
        val buf = ByteArray(512)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            if (n > 0) raw.write(buf, 0, n)
        }
    } finally {
        deflater.end()
    }
    val crc = CRC32().also { it.update(data) }
    return ByteArrayOutputStream(18 + raw.size()).apply {
        write(byteArrayOf(0x1f, 0x8b.toByte(), 8, 0, 0, 0, 0, 0, 0, 3))
        write(raw.toByteArray())
        writeIntLe(crc.value.toInt())
        writeIntLe(data.size)
    }.toByteArray()
}

private fun ByteArrayOutputStream.writeIntLe(v: Int) {
    write(v and 0xff)
    write(v ushr 8 and 0xff)
    write(v ushr 16 and 0xff)
    write(v ushr 24 and 0xff)
}

internal fun gogDecodeCloudBody(data: ByteArray, contentEncoding: String?): ByteArray {
    val encodings = contentEncoding.orEmpty().split(',').map { it.trim().lowercase() }
    if ("gzip" in encodings || "x-gzip" in encodings) {
        if (!isGzip(data)) return data
        return gunzip(data) ?: throw java.io.IOException("Invalid GOG cloud gzip body")
    }
    if (!isGzip(data)) return data
    val inflated = gunzip(data) ?: return data
    return if (gzipGogPut(inflated).contentEquals(data)) inflated else data
}

internal fun isoDate(s: String?): Long {
    if (s == null) return -1L
    return runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        .recoverCatching { java.time.LocalDateTime.parse(s).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }
        .getOrDefault(-1L)
}

class NoCloudSaves : Exception("no cloud saves")

/** GOG cloud names are `<location name>/<path under that location>`; the name is not part of the folder. */
internal fun splitGogName(name: String): Pair<String?, String> {
    val slash = name.indexOf('/')
    return if (slash <= 0 || slash == name.length - 1) null to name
    else name.substring(0, slash) to name.substring(slash + 1)
}

/** Maps cloud names to paths under the selected location's folder; the chosen location name is returned. */
internal fun gogRelativeNames(files: List<CloudFile>, location: String?): Pair<String?, List<CloudFile>> {
    val locations = files.mapNotNull { splitGogName(it.name).first }.distinct()
    // Only infer a location when every name is prefixed; a mixed listing keeps its old shape.
    val allPrefixed = files.isNotEmpty() && files.all { splitGogName(it.name).first != null }
    val chosen = when {
        location != null && (location in locations || allPrefixed) -> location
        location == null && allPrefixed -> locations.singleOrNull()
        else -> null
    } ?: return null to files
    return chosen to files.mapNotNull { file ->
        val (dir, rel) = splitGogName(file.name)
        if (dir == chosen) CloudFile(rel, file.modifiedMs, file.md5, file.readLink) else null
    }
}

class GogCloud(
    private val userId: String,
    private val clientId: String,
    private val token: String,
    private val location: String? = null,
) : CloudTransport {
    private val base = "https://cloudstorage.gog.com/v1/$userId/$clientId"
    private val ua = "GOG Galaxy"
    private var prefix: String? = location?.let { "$it/" }

    private fun key(name: String) = URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    private fun cloudKey(name: String) = key((prefix ?: "") + name)

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
        val raw = if (text.startsWith("[")) {
            val a = JSONArray(text)
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { o ->
                val n = o.optString("name").ifEmpty { return@mapNotNull null }
                val lm = o.optLong("last_modified", 0L)
                val iso = isoDate(o.optString("last_modified").takeIf { it.isNotEmpty() })
                CloudFile(n, when {
                    lm > 1_000_000_000_000L -> lm
                    lm > 0 -> lm * 1000
                    iso > 0 -> iso
                    else -> -1L
                })
            }
        } else {
            text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { CloudFile(it, -1L) }
        }
        return relative(raw)
    }

    /**
     * The API names every file `<location>/<path>`. The location's folder is the sync root, so the
     * first segment is dropped. Older builds kept it, writing saves one folder too deep.
     */
    private fun relative(raw: List<CloudFile>): List<CloudFile> {
        val (chosen, files) = gogRelativeNames(raw, location)
        prefix = chosen?.let { "$it/" }
        return files
    }

    override fun details(file: CloudFile) {
        if (file.modifiedMs > 0 && file.md5 != null) return
        val c = open("$base/${cloudKey(file.name)}", "HEAD", token, ua)
        if (c.responseCode in 200..299) {
            httpDate(c.getHeaderField("Last-Modified")).takeIf { it > 0 }?.let { file.modifiedMs = it }
            c.getHeaderField("ETag")?.replace("\"", "")?.trim()?.ifEmpty { null }?.let { file.md5 = it }
        }
        c.disconnect()
    }

    override fun get(file: CloudFile): Pair<ByteArray, Long> {
        val c = open("$base/${cloudKey(file.name)}", "GET", token, ua)
        c.setRequestProperty("Accept-Encoding", "identity")
        if (c.responseCode !in 200..299) { val code = c.responseCode; c.disconnect(); throw java.io.IOException("GOG cloud get HTTP $code") }
        val modified = httpDate(c.getHeaderField("Last-Modified"))
        val encoding = c.getHeaderField("Content-Encoding")
        val data = c.body()
        c.disconnect()
        return gogDecodeCloudBody(data, encoding) to modified
    }

    override fun put(files: Map<String, ByteArray>): Map<String, Long> = files.mapValues { (name, data) ->
        val c = open("$base/${cloudKey(name)}", "PUT", token, ua)
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
