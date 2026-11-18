package com.droiddeck.launcher.stores.ubisoft

import android.os.Build
import android.util.Base64
import com.droiddeck.launcher.stores.CloudFile
import com.droiddeck.launcher.stores.CloudTransport
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.Deflater
import java.util.zip.Inflater

internal class UbisoftCloud(private val session: UbisoftAuth.Session, val product: Long) {
    class Slot(val id: Int, val filename: String, val hash: String, val size: Long, val modifiedMs: Long)

    class Entity(val json: JSONObject) {
        val id: String get() = json.optString("entityId")
        val revision: Int get() = json.optInt("revision")
        val slots: List<Slot> get() {
            val list = json.optJSONObject("obj")?.optJSONArray("slots") ?: JSONArray()
            return (0 until list.length()).mapNotNull { list.optJSONObject(it) }.map {
                Slot(it.optInt("id"), it.optString("filename"), it.optString("hash"), it.optLong("size"), UbisoftAuth.parseTime(it.optString("lastModified")))
            }
        }
    }

    private val authorization get() = "ubi_v1 t=${session.ticket}"

    fun entity(): Entity? {
        val url = "https://public-ubiservices.ubi.com/v1/profiles/${session.profileId}/entities?name=${entityName(product)}"
        val reply = UbisoftAuth.request(url, authorization = authorization, sessionId = session.sessionId)
        check(reply.ok) { "Ubisoft cloud saves answered ${reply.status}" }
        val list = JSONObject(reply.body).optJSONArray("entities") ?: return null
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }.firstOrNull { it.optString("name") == entityName(product) }?.let(::Entity)
    }

    fun upload(slot: String, data: ByteArray) {
        val packed = deflate(data)
        val md5 = Base64.encodeToString(MessageDigest.getInstance("MD5").digest(packed), Base64.NO_WRAP)
        val signed = signed("put", JSONArray().put(JSONObject().put("name", slot).put("size", packed.size).put("md5", md5))).single()
        val c = URL(signed.getString("url")).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.requestMethod = "PUT"
        c.doOutput = true
        c.setFixedLengthStreamingMode(packed.size)
        c.setRequestProperty("Content-MD5", md5)
        try {
            c.outputStream.use { it.write(packed) }
            check(c.responseCode in 200..299) { "Ubisoft cloud save upload answered ${c.responseCode}" }
        } finally {
            c.disconnect()
        }
    }

    fun update(entity: Entity, slots: JSONArray) {
        val obj = JSONObject(entity.json.optJSONObject("obj")?.toString() ?: "{}")
            .put("slots", slots).put("timestamp", (System.currentTimeMillis() / 1000).toString()).put("pc_name", Build.MODEL ?: "Android")
        val body = JSONObject().put("revision", entity.revision).put("name", entity.json.optString("name")).put("type", "cloudSave")
            .put("spaceId", entity.json.optString("spaceId")).put("tags", entity.json.optJSONArray("tags") ?: JSONArray()).put("obj", obj)
        val reply = UbisoftAuth.request("https://public-ubiservices.ubi.com/v1/profiles/entities/${entity.id}", "PUT",
            authorization, session.sessionId, body.toString())
        check(reply.ok) { "Ubisoft cloud save record answered ${reply.status}" }
    }

    fun download(slot: String): ByteArray? {
        val signed = signed("get", JSONArray().put(JSONObject().put("name", slot))).single()
        val c = URL(signed.getString("url")).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        return try {
            when (c.responseCode) {
                404 -> null
                in 200..299 -> c.inputStream.use(::inflate)
                else -> error("Ubisoft cloud save download answered ${c.responseCode}")
            }
        } finally {
            c.disconnect()
        }
    }

    private fun signed(method: String, items: JSONArray): List<JSONObject> {
        val answer = UbisoftNative.call("cloud-urls", JSONObject().put("ticket", session.ticket).put("sessionId", session.sessionId)
            .put("productId", product).put("method", method).put("items", items)) ?: error("Ubisoft cloud saves are unavailable")
        answer.optString("error").takeIf { it.isNotEmpty() }?.let { error(it) }
        val requests = answer.getJSONArray("requests")
        return (0 until requests.length()).map { requests.getJSONObject(it) }
    }

    companion object {
        private const val MAX_SAVE = 256L * 1024 * 1024

        fun entityName(product: Long) = "upcCloudSaveUplayId_$product"

        private val MARK = Regex("[0-9a-f]{32}")

        internal fun mark(save: ByteArray): String? =
            if (save.size < 40) null else String(save, 8, 32, Charsets.ISO_8859_1).takeIf { MARK.matches(it) }

        internal fun deflate(data: ByteArray): ByteArray {
            val deflater = Deflater(Deflater.BEST_COMPRESSION)
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(data.size / 2 + 64)
            val buffer = ByteArray(64 * 1024)
            try {
                while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            } finally {
                deflater.end()
            }
            return out.toByteArray()
        }

        internal fun inflate(data: ByteArray): ByteArray = inflate(data.inputStream())

        internal fun inflate(input: java.io.InputStream): ByteArray {
            val inflater = Inflater()
            val out = ByteArrayOutputStream(64 * 1024)
            val buffer = ByteArray(64 * 1024)
            val chunk = ByteArray(64 * 1024)
            try {
                while (!inflater.finished()) {
                    if (inflater.needsInput()) {
                        val read = input.read(chunk)
                        if (read < 0) error("Ubisoft cloud save is truncated")
                        inflater.setInput(chunk, 0, read)
                        continue
                    }
                    if (inflater.needsDictionary()) error("Ubisoft cloud save is truncated")
                    val n = inflater.inflate(buffer)
                    check(out.size().toLong() + n <= MAX_SAVE) { "Ubisoft cloud save is too large" }
                    out.write(buffer, 0, n)
                }
            } finally {
                inflater.end()
            }
            return out.toByteArray()
        }

        internal fun version(slot: Slot): String = "${slot.size}:${slot.modifiedMs}:${slot.hash}"
    }
}

internal class UbisoftCloudTransport(private val cloud: UbisoftCloud, val user: String) : CloudTransport {
    private var listed: UbisoftCloud.Entity? = null
    private val committed = HashMap<String, CloudFile>()

    override fun committed(name: String): CloudFile? = committed[name]

    override fun wants(name: String): Boolean = name.endsWith(SUFFIX) && !name.contains('/')

    override fun list(): List<CloudFile> {
        val entity = cloud.entity()
        listed = entity
        return entity?.slots.orEmpty().map { CloudFile(it.filename + SUFFIX, it.modifiedMs, UbisoftCloud.version(it)) }
    }

    override fun get(file: CloudFile): Pair<ByteArray, Long> {
        val data = cloud.download(file.name.removeSuffix(SUFFIX)) ?: error("Ubisoft has no data for ${file.name}")
        return data to file.modifiedMs
    }

    override fun put(files: Map<String, ByteArray>, modifiedMs: Map<String, Long>): Map<String, Long> {
        val before = listed ?: cloud.entity() ?: error("Ubisoft has no cloud save record for this game yet; play it once in Ubisoft Connect")
        val current = cloud.entity() ?: error("Ubisoft's cloud save record disappeared")
        check(current.revision == before.revision) { "Ubisoft's cloud saves changed since they were read" }
        val marks = files.mapValues { (name, data) ->
            check(name.endsWith(SUFFIX) && !name.contains('/')) { "$name is not a Ubisoft save slot" }
            UbisoftCloud.mark(data) ?: error("$name has no save identifier for Ubisoft Connect")
        }
        val now = System.currentTimeMillis()
        val slots = JSONArray(current.json.optJSONObject("obj")?.optJSONArray("slots")?.toString() ?: "[]")
        for ((name, data) in files) {
            val slot = name.removeSuffix(SUFFIX)
            check(cloud.entity()?.revision == current.revision) { "Ubisoft's cloud saves changed since they were read" }
            cloud.upload(slot, data)
            val entry = (0 until slots.length()).map { slots.getJSONObject(it) }.firstOrNull { it.optString("filename") == slot }
                ?: JSONObject().put("filename", slot).also { if (slot.toIntOrNull() != null) it.put("id", slot.toInt()); slots.put(it) }
            entry.put("hash", marks.getValue(name)).put("size", data.size).put("lastModified", Instant.ofEpochMilli(now).toString())
        }
        cloud.update(current, slots)
        listed = null
        val stamp = UbisoftAuth.parseTime(Instant.ofEpochMilli(now).toString())
        for ((name, data) in files) {
            val slot = UbisoftCloud.Slot(0, name.removeSuffix(SUFFIX), marks.getValue(name), data.size.toLong(), stamp)
            committed[name] = CloudFile(name, stamp, UbisoftCloud.version(slot))
        }
        return files.mapValues { now }
    }

    companion object {
        const val SUFFIX = ".save"
    }
}
