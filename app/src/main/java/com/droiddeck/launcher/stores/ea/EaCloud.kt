package com.droiddeck.launcher.stores.ea

import android.util.Base64
import android.util.Xml
import com.droiddeck.launcher.stores.CloudFile
import com.droiddeck.launcher.stores.CloudTransport
import java.io.File
import java.io.StringReader
import java.security.MessageDigest
import java.time.Instant
import org.xmlpull.v1.XmlPullParser

internal class EaCloud(
    private val token: String,
    private val uid: String,
    private val keys: List<String>,
    private val base: String,
    private val rules: EaSaveRules,
    val localDir: File? = null,
) : CloudTransport {
    class Entry(val size: Long, val href: String, val md5: String, val created: String, val modified: String, val localName: String)

    class Manifest(val lineage: String, val files: List<Entry>, val snapshots: List<String>)

    private class Signed(val url: String, val headers: Map<String, String>)

    private class Request(val verb: String, val resource: String, val md5: String? = null, val type: String? = null)

    private var key: String? = null
    private var found = false
    private var listed: Map<String, String?>? = null
    private val committed = HashMap<String, CloudFile>()

    override fun wants(name: String): Boolean = rules.matches(base + name)

    override fun committed(name: String): CloudFile? = committed[name]

    override fun list(): List<CloudFile> {
        val manifest = pick()
        val files = manifest?.files.orEmpty().mapNotNull { e ->
            val path = EaSaveRules.normalize(e.localName)
            if (!path.startsWith(base, ignoreCase = true) || !rules.matches(path)) return@mapNotNull null
            val md5 = hex(e.md5) ?: error("EA listed ${path.substring(base.length)} without a valid checksum")
            CloudFile(localName(path.substring(base.length)), parseTime(e.modified), md5, readLink = e.href)
        }
        val twins = files.groupBy { it.name.lowercase() }.values.firstOrNull { it.size > 1 }
        check(twins == null) { "EA lists more than one save named ${twins!!.first().name} in different cases" }
        listed = files.associate { it.name.lowercase() to it.md5 }
        return files
    }

    private fun localName(name: String): String {
        var dir = localDir ?: return name
        val parts = name.split('/')
        val out = ArrayList<String>()
        for (part in parts) {
            val match = dir.listFiles()?.filter { it.name.equals(part, ignoreCase = true) }.orEmpty()
            check(match.size <= 1) { "The save folder holds more than one file named $part in different cases" }
            val pick = match.singleOrNull()
            if (pick == null) return (out + parts.drop(out.size)).joinToString("/")
            out += pick.name
            dir = pick
        }
        return out.joinToString("/")
    }

    override fun get(file: CloudFile): Pair<ByteArray, Long> {
        val href = file.readLink ?: error("EA has no data for ${file.name}")
        return locked("read", key ?: keys.first()) { lock, _ ->
            val signed = authorize(lock, listOf(Request("GET", href))).single()
            val reply = EaHttp.request(signed.url, headers = signed.headers)
            check(reply.ok) { "EA cloud save download answered ${reply.status}" }
            val want = file.md5 ?: error("EA listed ${file.name} without a valid checksum")
            check(hex(b64(md5(reply.body))) == want) { "EA cloud save ${file.name} failed its checksum" }
            reply.body to file.modifiedMs
        }
    }

    override fun put(files: Map<String, ByteArray>, modifiedMs: Map<String, Long>): Map<String, Long> {
        if (key == null) pick()
        check(found || keys.size == 1) { "EA has no cloud save record for this game yet; play it once in the EA app" }
        val target = key ?: keys.first()
        val seen = listed
        return locked("write", target) { lock, body ->
            val locked = manifestUrl(body)?.let { fetchManifest(it) }
            check(locked != null || !found) { "EA's cloud save record disappeared" }
            val current = locked ?: Manifest("{00000000-0000-0000-0000-000000000000}", emptyList(), emptyList())
            if (seen != null) for (name in files.keys) {
                val entry = current.files.firstOrNull { EaSaveRules.normalize(it.localName).equals(base + name, ignoreCase = true) }
                val now = entry?.let { hex(it.md5) ?: error("EA lists $name without a valid checksum") }
                check(now == seen[name.lowercase()]) { "EA's cloud saves changed since they were read" }
            }
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
            for ((entry, _) in added) {
                val name = entry.localName.substring(base.length)
                committed[name] = CloudFile(name, parseTime(entry.modified), hex(entry.md5), readLink = entry.href)
            }
            listed = null
            files.mapValues { (name, _) -> modifiedMs[name]?.takeIf { it > 0 } ?: now }
        }
    }

    private fun pick(): Manifest? {
        for (candidate in keys) {
            val manifest = locked("read", candidate, missing = { null }) { _, body -> manifestUrl(body)?.let { fetchManifest(it) } }
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

    private fun <T> locked(kind: String, key: String, missing: (() -> T)? = null, body: (String, String) -> T): T {
        val reply = EaHttp.request("$HOST/lock/$kind/$uid/$key", "POST", mapOf(AUTH to token, "Content-Type" to "application/xml"), ByteArray(0))
        if (reply.status == 404 && missing != null) return missing()
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
        private val REQUEST = Regex("<request\\b[^>]*\\bid=\"(\\d+)\"[^>]*>(.*?)</request>", RegexOption.DOT_MATCHES_ALL)
        private val HEADER = Regex("<header\\b([^>]*)/?>")

        fun keys(masterTitleId: String, multiplayerId: String): List<String> =
            if (multiplayerId.isNotEmpty()) listOf("${masterTitleId}_$multiplayerId") else listOf(masterTitleId, "${masterTitleId}_$masterTitleId")

        fun manifestUrl(lockBody: String): String? = Regex("<manifest>([^<]+)</manifest>").find(lockBody)?.groupValues?.get(1)?.let(::unescape)

        fun parse(xml: String): Manifest {
            val p = Xml.newPullParser().apply { setInput(StringReader(xml)) }
            var root: String? = null
            var lineage: String? = null
            var depth = 0
            var closed = false
            val files = ArrayList<Entry>()
            val snapshots = ArrayList<String>()
            var file: Array<String>? = null
            var local: Entry? = null
            runCatching {
                while (p.next() != XmlPullParser.END_DOCUMENT) when (p.eventType) {
                    XmlPullParser.START_TAG -> {
                        depth++
                        val at = { name: String -> p.getAttributeValue(null, name).orEmpty() }
                        when {
                            root == null -> { root = p.name; lineage = p.getAttributeValue(null, "lineage") }
                            depth == 2 && p.name == "file" -> { file = arrayOf(at("size"), at("href"), at("md5")); local = null }
                            depth == 3 && p.name == "localName" && file != null -> {
                                val f = file!!
                                val created = at("created")
                                val modified = at("lastModified")
                                local = Entry(f[0].toLongOrNull() ?: 0, f[1], f[2], created, modified, p.nextText())
                                depth--
                            }
                            depth == 2 && p.name == "snapshot" -> { snapshots += p.nextText(); depth-- }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (depth == 2 && p.name == "file") { local?.let(files::add); file = null }
                        depth--
                        if (depth == 0) closed = true
                    }
                }
            }.onFailure { error("EA sent an incomplete cloud save manifest") }
            check(root == "manifest" && depth == 0 && closed) { "EA sent an incomplete cloud save manifest" }
            return Manifest(lineage ?: "{00000000-0000-0000-0000-000000000000}", files, snapshots)
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

        private fun unescape(s: String) = EaSaveRules.unescape(s)
    }
}
