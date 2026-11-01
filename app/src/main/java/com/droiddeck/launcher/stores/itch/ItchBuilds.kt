package com.droiddeck.launcher.stores.itch

import com.droiddeck.launcher.stores.StoreNet
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

internal object ItchBuilds {
    private const val BASE = "https://api.itch.io"
    private const val MAX_STEPS = 4

    class Step(val buildId: Long, val patchFile: Long, val patchSize: Long, val signatureFile: Long)

    fun buildId(upload: JSONObject): String {
        val value = upload.opt("build_id") ?: upload.opt("buildId")
        return when (value) {
            is Number -> value.toLong().takeIf { it > 0 }?.toString().orEmpty()
            is String -> value.takeIf { it.all(Char::isDigit) }?.toLongOrNull()?.takeIf { it > 0 }?.toString().orEmpty()
            else -> ""
        }
    }

    fun path(key: String, from: String, to: String, downloadKeyId: String): List<Step>? {
        val fromId = from.toLongOrNull() ?: return null
        val toId = to.toLongOrNull() ?: return null
        val reply = ItchApi.get("/builds/$fromId/upgrade-paths/$toId" + keyQuery(downloadKeyId), key) ?: return null
        return steps(reply, fromId, toId) { filesOf(key, it, downloadKeyId) }
    }

    fun steps(reply: JSONObject, fromId: Long, toId: Long, filesFor: (JSONObject) -> JSONArray?): List<Step>? {
        val builds = (reply.optJSONObject("upgrade_path") ?: reply.optJSONObject("upgradePath"))?.optJSONArray("builds") ?: return null
        val ids = (0 until builds.length()).mapNotNull { builds.optJSONObject(it) }
        val after = ids.filter { it.optLong("id") != fromId }
        if (after.isEmpty() || after.size > MAX_STEPS || after.last().optLong("id") != toId) return null
        val steps = after.map { build ->
            val files = filesFor(build) ?: return null
            val patch = pick(files, "patch") ?: return null
            val signature = pick(files, "signature")
            Step(build.optLong("id"), patch.optLong("id"), patch.optLong("size"), signature?.optLong("id") ?: 0L)
        }
        return steps.takeIf { it.last().signatureFile > 0 }
    }

    fun fileUrl(buildId: Long, fileId: Long, downloadKeyId: String): String =
        "$BASE/wharf/builds/$buildId/files/$fileId/download" + keyQuery(downloadKeyId)

    fun download(url: String, key: String, target: File, cancelled: AtomicBoolean, progress: (Long) -> Unit): Boolean {
        val first = open(url, key)
        try {
            val code = first.responseCode
            val location = first.getHeaderField("Location")
            if (code in 300..399 && location != null) {
                first.disconnect()
                val next = URL(URL(url), location).toString()
                if (!next.startsWith("https://")) return false
                return save(open(next, null), target, cancelled, progress)
            }
            return save(first, target, cancelled, progress)
        } finally {
            runCatching { first.disconnect() }
        }
    }

    private fun save(conn: HttpURLConnection, target: File, cancelled: AtomicBoolean, progress: (Long) -> Unit): Boolean {
        try {
            if (conn.responseCode !in 200..299) return false
            var done = 0L
            val buffer = ByteArray(128 * 1024)
            conn.inputStream.use { input ->
                FileOutputStream(target).use { out ->
                    while (true) {
                        if (cancelled.get()) return false
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        done += read
                        progress(done)
                    }
                }
            }
            return true
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun open(url: String, key: String?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", StoreNet.BROWSER_UA)
            setRequestProperty("Accept", "*/*")
            if (key != null) setRequestProperty("Authorization", "Bearer $key")
        }

    private fun filesOf(key: String, build: JSONObject, downloadKeyId: String): JSONArray? =
        build.optJSONArray("files")
            ?: ItchApi.get("/builds/${build.optLong("id")}" + keyQuery(downloadKeyId), key)?.optJSONObject("build")?.optJSONArray("files")

    private fun pick(files: JSONArray, type: String): JSONObject? {
        val all = (0 until files.length()).mapNotNull { files.optJSONObject(it) }
            .filter { it.optString("type") == type && it.optString("state", "uploaded") == "uploaded" }
        return all.firstOrNull { subType(it) == "optimized" } ?: all.firstOrNull { subType(it) == "default" }
    }

    private fun subType(file: JSONObject): String = file.optString("sub_type", file.optString("subType", "default"))

    private fun keyQuery(downloadKeyId: String): String = if (downloadKeyId.isBlank()) "" else "?download_key_id=$downloadKeyId"
}
