package com.droiddeck.launcher.stores

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.DigestOutputStream
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.TimeZone
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import org.json.JSONArray
import org.json.JSONObject

class CloudFile(val name: String, var modifiedMs: Long, var md5: String? = null, val readLink: String? = null)

class CloudPut(val modifiedMs: Long, val md5: String?)

interface CloudTransport {
    fun list(): List<CloudFile>
    fun details(file: CloudFile) {}
    fun get(file: CloudFile): Pair<ByteArray, Long>
    fun put(files: Map<String, ByteArray>, modifiedMs: Map<String, Long> = emptyMap()): Map<String, Long>

    /** Uploads from files so a transport can stream instead of holding every save in memory. */
    fun putFiles(files: Map<String, File>, modifiedMs: Map<String, Long> = emptyMap()): Map<String, CloudPut> {
        val data = files.mapValues { it.value.readBytes() }
        val digests = data.mapValues { md5Hex(it.value) }
        return put(data, modifiedMs).mapValues { (name, modified) -> CloudPut(modified, digests.getValue(name)) }
    }
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

private val SECRET_PARAM = Regex("(?i)(token|secret|code|password|authorization)([\"']?\\s*[:=]\\s*[\"']?)[^\\s&\"',;}]+")
private val BEARER = Regex("(?i)bearer\\s+\\S+")
private val URL_TEXT = Regex("(?i)https?://(?:[^/\\s?#@]*@)?([^/\\s?#@]+)[^\\s]*")
private val LONG_TOKEN = Regex("[A-Za-z0-9._~+/=-]{32,}")
private val SPACES = Regex("\\s+")
private const val DETAIL_MAX = 400

internal fun scrubCloudText(text: String, longTokens: Boolean = true): String = text
    .replace(BEARER, "Bearer <redacted>")
    .replace(URL_TEXT) { it.groupValues[1] }
    .replace(SECRET_PARAM) { it.groupValues[1] + it.groupValues[2] + "<redacted>" }
    .let { if (longTokens) it.replace(LONG_TOKEN, "<redacted>") else it }
    .replace(SPACES, " ")
    .trim()

internal fun cloudFailureDetail(e: Throwable): String {
    val parts = ArrayList<String>()
    var t: Throwable? = e
    while (t != null && parts.size < 3) {
        val message = t.message?.let { scrubCloudText(it, longTokens = false) }.orEmpty()
        parts += if (message.isEmpty()) t.javaClass.simpleName else "${t.javaClass.simpleName}: $message"
        t = t.cause?.takeIf { it !== t }
    }
    return parts.joinToString(" <- ").take(DETAIL_MAX)
}

private const val SNIPPET_READ_MAX = 4096
private const val SNIPPET_MAX = 120

internal fun cloudErrorSnippet(input: InputStream?): String {
    if (input == null) return ""
    val prefix = input.use { stream ->
        val buffer = ByteArray(SNIPPET_READ_MAX + 1)
        var read = 0
        while (read < buffer.size) {
            val n = stream.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        val text = buffer.decodeToString(0, minOf(read, SNIPPET_READ_MAX))
        if (read > SNIPPET_READ_MAX) text.substring(0, text.indexOfLast { it.isWhitespace() }.coerceAtLeast(0)) else text
    }
    return scrubCloudText(prefix).take(SNIPPET_MAX)
}

private fun HttpURLConnection.errorSnippet(): String =
    runCatching { cloudErrorSnippet(errorStream) }.getOrDefault("")

private fun httpFailure(step: String, name: String?, code: Int, error: String): java.io.IOException =
    java.io.IOException(buildString {
        append("GOG cloud ").append(step).append(" HTTP ").append(code)
        if (name != null) append(" file=").append(name)
        if (error.isNotEmpty()) append(" body=").append(error)
    })

internal fun isGzip(data: ByteArray) =
    data.size >= 10 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte() && data[2] == 0x08.toByte()

internal fun gunzip(data: ByteArray): ByteArray? = try {
    java.util.zip.GZIPInputStream(data.inputStream()).use { it.readBytes() }
} catch (e: Exception) { null }

/** Same gzip GOG uses on PUT: deflate level 6, mtime 0, OS Unix. */
private val GOG_GZIP_HEADER = byteArrayOf(0x1f, 0x8b.toByte(), 8, 0, 0, 0, 0, 0, 0, 3)

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
        write(GOG_GZIP_HEADER)
        write(raw.toByteArray())
        writeIntLe(crc.value.toInt())
        writeIntLe(data.size)
    }.toByteArray()
}

private fun OutputStream.writeIntLe(v: Int) {
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

internal fun splitGogName(name: String): Pair<String?, String> {
    val slash = name.indexOf('/')
    return if (slash <= 0 || slash == name.length - 1) null to name
    else name.substring(0, slash) to name.substring(slash + 1)
}

internal fun gogRelativeNames(files: List<CloudFile>, location: String?): Pair<String?, List<CloudFile>> {
    val locations = files.mapNotNull { splitGogName(it.name).first }.distinct()
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

private fun md5Hex(data: ByteArray): String =
    java.security.MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private class GzipFile(val length: Long, val rawMd5: String, val etag: String)

/** Streams the same gzip framing as [gzipGogPut] into [dst] without reading the save into memory. */
private fun gzipFile(src: File, dst: File): GzipFile {
    val raw = java.security.MessageDigest.getInstance("MD5")
    val compressed = java.security.MessageDigest.getInstance("MD5")
    val crc = CRC32()
    var size = 0L
    val file = FileOutputStream(dst)
    val out = DigestOutputStream(file, compressed)
    try {
        out.write(GOG_GZIP_HEADER)
        val deflater = Deflater(6, true)
        try {
            val deflate = DeflaterOutputStream(out, deflater, 64 * 1024)
            src.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    raw.update(buf, 0, n)
                    crc.update(buf, 0, n)
                    size += n
                    deflate.write(buf, 0, n)
                }
            }
            deflate.finish()
        } finally {
            deflater.end()
        }
        out.writeIntLe(crc.value.toInt())
        out.writeIntLe(size.toInt())
        out.flush()
        return GzipFile(dst.length(), raw.digest().toHex(), compressed.digest().toHex())
    } finally {
        out.close()
    }
}

internal const val GOG_CLOUD_UA = "GOGGalaxyCommunicationService/2.0.13.27 (Windows_32bit) dont_sync_marker/true installation_source/gog"

/** GOG Galaxy parses `X-Object-Meta-LocalLastModified`; objects without it fail as "invalid data". */
private val GOG_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx", Locale.US)

private fun gogStamp(ms: Long): String =
    Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).format(GOG_STAMP)

class GogCloud(
    private val userId: String,
    private val clientId: String,
    private val token: String,
    private val location: String? = null,
    baseUrl: String? = null,
    private val tempDir: File? = null,
) : CloudTransport {
    private val base = baseUrl ?: "https://cloudstorage.gog.com/v1/$userId/$clientId"
    private val ua = GOG_CLOUD_UA
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
            throw httpFailure("list", null, code, scrubCloudText(err).take(120))
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
        if (c.responseCode !in 200..299) {
            val code = c.responseCode
            val err = c.errorSnippet()
            c.disconnect()
            throw httpFailure("get", file.name, code, err)
        }
        val modified = httpDate(c.getHeaderField("Last-Modified"))
        val encoding = c.getHeaderField("Content-Encoding")
        val data = try { c.body() } finally { c.disconnect() }
        return try {
            gogDecodeCloudBody(data, encoding) to modified
        } catch (e: java.io.IOException) {
            throw java.io.IOException("GOG cloud get file=${file.name} bytes=${data.size} encoding=$encoding", e)
        }
    }

    override fun put(files: Map<String, ByteArray>, modifiedMs: Map<String, Long>): Map<String, Long> = files.mapValues { (name, data) ->
        val body = gzipGogPut(data)
        putBody(name, body.inputStream(), body.size.toLong(), md5Hex(body), modifiedMs[name] ?: System.currentTimeMillis())
    }

    override fun putFiles(files: Map<String, File>, modifiedMs: Map<String, Long>): Map<String, CloudPut> = files.mapValues { (name, file) ->
        val compressed = try {
            File.createTempFile("gog-cloud-", ".gz", tempDir?.apply { mkdirs() })
        } catch (e: java.io.IOException) {
            throw java.io.IOException("GOG cloud put file=$name temp file", e)
        }
        try {
            val gz = gzipFile(file, compressed)
            val modified = putBody(name, compressed.inputStream(), gz.length, gz.etag, modifiedMs[name] ?: file.lastModified())
            CloudPut(modified, gz.rawMd5)
        } finally {
            compressed.delete()
        }
    }

    private fun putBody(name: String, source: InputStream, length: Long, etag: String, modified: Long): Long {
        val c = open("$base/${cloudKey(name)}", "PUT", token, ua)
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.setRequestProperty("Content-Encoding", "gzip")
            c.setRequestProperty("Etag", etag)
            c.setRequestProperty("X-Object-Meta-LocalLastModified", gogStamp(modified))
            c.setRequestProperty("X-Object-Meta-User-Agent", ua)
            c.setFixedLengthStreamingMode(length)
            try {
                c.outputStream.use { out -> source.use { it.copyTo(out, 64 * 1024) } }
            } catch (e: java.io.IOException) {
                val code = runCatching { c.responseCode }.getOrNull()
                throw java.io.IOException("GOG cloud put file=$name bytes=$length" + (code?.let { " HTTP $it" } ?: ""), e)
            }
            val code = c.responseCode
            if (code !in 200..299) throw httpFailure("put", name, code, c.errorSnippet())
            return CloudFile(name, -1L).also { details(it) }.modifiedMs
        } finally {
            c.disconnect()
        }
    }
}

class EpicCloud(private val accountId: String, private val appName: String, private val token: String) : CloudTransport {
    override fun list(): List<CloudFile> = com.droiddeck.launcher.stores.epic.EpicSaveSync.list(token, accountId, appName)

    override fun get(file: CloudFile): Pair<ByteArray, Long> = com.droiddeck.launcher.stores.epic.EpicSaveSync.read(file)

    override fun put(files: Map<String, ByteArray>, modifiedMs: Map<String, Long>): Map<String, Long> =
        com.droiddeck.launcher.stores.epic.EpicSaveSync.write(token, accountId, appName, files)
}
