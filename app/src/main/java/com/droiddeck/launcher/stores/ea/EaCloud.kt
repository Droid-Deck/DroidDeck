package com.droiddeck.launcher.stores.ea

import android.util.Base64
import com.droiddeck.launcher.stores.CloudFile
import com.droiddeck.launcher.stores.CloudTransport
import java.security.MessageDigest
import java.time.Instant

internal class EaCloud(
    private val token: String,
    private val uid: String,
    private val keys: List<String>,
    private val base: String,
    private val rules: EaSaveRules,
) : CloudTransport {
    class Entry(val size: Long, val href: String, val md5: String, val created: String, val modified: String, val localName: String)

    class Manifest(val lineage: String, val files: List<Entry>, val snapshots: List<String>)

    private class Signed(val url: String, val headers: Map<String, String>)

    private class Request(val verb: String, val resource: String, val md5: String? = null, val type: String? = null)

    private var key: String? = null
    private var found = false

    override fun wants(name: String): Boolean = rules.matches(base + name)

    override fun list(): List<CloudFile> {
        val manifest = pick()
        return manifest?.files.orEmpty().mapNotNull { e ->
            val path = EaSaveRules.normalize(e.localName)
            if (!path.startsWith(base, ignoreCase = true) || !rules.matches(path)) return@mapNotNull null
            CloudFile(path.substring(base.length), parseTime(e.modified), hex(e.md5), readLink = e.href)
        }
    }

    override fun get(file: CloudFile): Pair<ByteArray, Long> {
        val href = file.readLink ?: error("EA has no data for ${file.name}")
        return locked("read", key ?: keys.first()) { lock, _ ->
            val signed = authorize(lock, listOf(Request("GET", href))).single()
            val reply = EaHttp.request(signed.url, headers = signed.headers)
            check(reply.ok) { "EA cloud save download answered ${reply.status}" }
            file.md5?.let { want -> check(hex(b64(md5(reply.body))) == want) { "EA cloud save ${file.name} failed its checksum" } }
            reply.body to file.modifiedMs
        }
    }

    override fun put(files: Map<String, ByteArray>, modifiedMs: Map<String, Long>): Map<String, Long> {
        if (key == null) pick()
        check(found || keys.size == 1) { "EA has no cloud save record for this game yet; play it once in the EA app" }
        val target = key ?: keys.first()
        return locked("write", target) { lock, body ->
            val current = manifestUrl(body)?.let { fetchManifest(it) } ?: Manifest("{00000000-0000-0000-0000-000000000000}", emptyList(), emptyList())
            val now = System.currentTimeMillis()
            val added = files.map { (name, data) ->
                val sum = md5(data)
                val stamp = stamp(modifiedMs[name]?.takeIf { it > 0 } ?: now)
                val old = current.files.firstOrNull { EaSaveRules.normalize(it.localName).equals(base + name, ignoreCase = true) }
                Entry(data.size.toLong(), "content/${data.size}-${hexOf(sum)}", b64(sum), old?.created ?: stamp, stamp, base + name) to data
            }
            val names = added.map { it.first.localName.lowercase() }.toSet()
            val next = Manifest(current.lineage, current.files.filter { EaSaveRules.normalize(it.localName).lowercase() !in names } + added.map { it.first }, current.snapshots)
            val xml = write(next).toByteArray()
            val xmlMd5 = b64(md5(xml))
            val requests = added.map { Request("PUT", it.first.href, it.first.md5, "application/octet-stream") } + Request("PUT", "manifest.xml", xmlMd5, "text/xml")
            val signed = authorize(lock, requests)
            for ((i, pair) in added.withIndex()) upload(signed[i], pair.second)
            upload(signed.last(), xml)
            found = true
            files.mapValues { (name, _) -> modifiedMs[name]?.takeIf { it > 0 } ?: now }
        }
    }

    private fun pick(): Manifest? {
        for (candidate in keys) {
            val manifest = locked("read", candidate) { _, body -> manifestUrl(body)?.let { fetchManifest(it) } }
            if (manifest != null) {
                key = candidate
                found = true
                return manifest
            }
        }
        key = keys.first()
        found = false
        return null
    }

    private fun upload(signed: Signed, data: ByteArray) {
        val reply = EaHttp.request(signed.url, "PUT", signed.headers, data)
        check(reply.ok) { "EA cloud save upload answered ${reply.status}" }
    }

    private fun fetchManifest(url: String): Manifest? {
        val reply = EaHttp.request(url)
        if (reply.status == 404) return null
        check(reply.ok) { "EA cloud save manifest answered ${reply.status}" }
        return parse(reply.text)
    }

    private fun <T> locked(kind: String, key: String, body: (String, String) -> T): T {
        val reply = EaHttp.request("$HOST/lock/$kind/$uid/$key", "POST", mapOf(AUTH to token, "Content-Type" to "application/xml"), ByteArray(0))
        check(reply.ok) { "EA cloud saves answered ${reply.status}" }
        val lock = reply.header(LOCK) ?: error("EA did not grant a cloud save lock")
        try {
            return body(lock, reply.text)
        } finally {
            runCatching { EaHttp.request("$HOST/lock/delete/$uid", "DELETE", mapOf(AUTH to token, LOCK to lock)) }
        }
    }

    private fun authorize(lock: String, requests: List<Request>): List<Signed> {
        val body = buildString {
            append("<requests>")
            for ((i, r) in requests.withIndex()) {
                append("<request id=\"").append(i).append("\"><verb>").append(r.verb).append("</verb><resource>").append(escape(r.resource)).append("</resource>")
                r.md5?.let { append("<md5>").append(it).append("</md5>") }
                r.type?.let { append("<content-type>").append(it).append("</content-type>") }
                append("</request>")
            }
            append("</requests>")
        }
        val reply = EaHttp.request("$HOST/lock/authorize/$uid", "PUT", mapOf(AUTH to token, LOCK to lock, "Content-Type" to "application/xml"), body.toByteArray())
        check(reply.ok) { "EA cloud saves refused the transfer (${reply.status})" }
        return signedRequests(reply.text, requests.size)
    }

    companion object {
        private const val HOST = "https://cloudsync.juno.ea.com"
        private const val AUTH = "X-Origin-AuthToken"
        private const val LOCK = "X-Origin-Sync-Lock"
        private val FILE = Regex("<file\\b([^>]*)>(.*?)</file>", RegexOption.DOT_MATCHES_ALL)
        private val LOCAL = Regex("<localName\\b([^>]*)>([^<]*)</localName>")
        private val SNAPSHOT = Regex("<snapshot>([^<]*)</snapshot>")
        private val REQUEST = Regex("<request\\b[^>]*\\bid=\"(\\d+)\"[^>]*>(.*?)</request>", RegexOption.DOT_MATCHES_ALL)
        private val HEADER = Regex("<header\\b([^>]*)/?>")

        fun keys(masterTitleId: String, multiplayerId: String): List<String> =
            if (multiplayerId.isNotEmpty()) listOf("${masterTitleId}_$multiplayerId") else listOf(masterTitleId, "${masterTitleId}_$masterTitleId")

        fun manifestUrl(lockBody: String): String? = Regex("<manifest>([^<]+)</manifest>").find(lockBody)?.groupValues?.get(1)?.let(::unescape)

        fun parse(xml: String): Manifest {
            val lineage = attr(Regex("<manifest\\b([^>]*)>").find(xml)?.groupValues?.get(1).orEmpty(), "lineage")
                ?: "{00000000-0000-0000-0000-000000000000}"
            val files = FILE.findAll(xml).mapNotNull { m ->
                val a = m.groupValues[1]
                val local = LOCAL.find(m.groupValues[2]) ?: return@mapNotNull null
                Entry(attr(a, "size")?.toLongOrNull() ?: 0, attr(a, "href").orEmpty(), attr(a, "md5").orEmpty(),
                    attr(local.groupValues[1], "created").orEmpty(), attr(local.groupValues[1], "lastModified").orEmpty(), unescape(local.groupValues[2]))
            }.toList()
            return Manifest(lineage, files, SNAPSHOT.findAll(xml).map { it.groupValues[1] }.toList())
        }

        fun write(m: Manifest): String = buildString {
            append("<manifest xmlns=\"http://origin.com/cloudsaves/manifest\" lineage=\"").append(escape(m.lineage)).append("\">\n")
            for (f in m.files) {
                append(" <file size=\"").append(f.size).append("\" href=\"").append(escape(f.href)).append("\" md5=\"").append(escape(f.md5)).append("\">\n")
                append("  <localName created=\"").append(escape(f.created)).append("\" lastModified=\"").append(escape(f.modified)).append("\">")
                append(escape(f.localName)).append("</localName>\n </file>\n")
            }
            for (s in m.snapshots) append(" <snapshot>").append(escape(s)).append("</snapshot>\n")
            append("</manifest>\n")
        }

        private fun signedRequests(xml: String, count: Int): List<Signed> {
            val byId = REQUEST.findAll(xml).associate { m ->
                val inner = m.groupValues[2]
                val url = Regex("<url>([^<]+)</url>").find(inner)?.groupValues?.get(1)?.let(::unescape) ?: error("EA sent no transfer URL")
                val headers = HEADER.findAll(inner).mapNotNull { h ->
                    val k = attr(h.groupValues[1], "key") ?: return@mapNotNull null
                    k to (attr(h.groupValues[1], "value") ?: "")
                }.toMap()
                m.groupValues[1].toInt() to Signed(url, headers)
            }
            return (0 until count).map { byId[it] ?: error("EA signed ${byId.size} of $count cloud save transfers") }
        }

        fun stamp(ms: Long): String = Instant.ofEpochMilli(ms / 1000 * 1000).toString() + "Z"

        fun parseTime(s: String): Long = runCatching { Instant.parse(s.trimEnd('Z') + "Z").toEpochMilli() }.getOrDefault(-1L)

        private fun attr(attrs: String, name: String): String? = Regex("\\b$name=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)?.let(::unescape)

        private fun md5(data: ByteArray) = MessageDigest.getInstance("MD5").digest(data)

        private fun b64(sum: ByteArray) = Base64.encodeToString(sum, Base64.NO_WRAP)

        private fun hexOf(sum: ByteArray) = sum.joinToString("") { "%02x".format(it) }

        fun hex(b64: String): String? = runCatching { hexOf(Base64.decode(b64, Base64.DEFAULT)) }.getOrNull()?.takeIf { it.length == 32 }

        private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    }
}
