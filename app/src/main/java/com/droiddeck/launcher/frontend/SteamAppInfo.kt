package com.droiddeck.launcher.frontend

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.droiddeck.launcher.runtime.LinuxRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Steam's own data for an app, from the client in the runtime: its app-info cache
 * (Steam/appcache/appinfo.vdf, the PICS data the client keeps for every app it has seen). What is
 * read is which Steamworks Shared depots (the VC++, DirectX... redistributables of app 228980) the
 * app's depots point at, and its name. Kept per appid in the app's files, so it works offline; an
 * app the client has not seen is looked for again after a week.
 */
object SteamAppInfo {
    private const val TAG = "SteamAppInfo"
    private const val CACHE = "steam-appinfo.json"
    private const val RETRY_AFTER_MS = 7L * 24 * 3600 * 1000
    /** The Steamworks Shared app the redistributable depots belong to. */
    const val SHARED_APP = "228980"

    class Info(val name: String, val sharedDepots: List<String>)

    fun appinfoFile(context: Context) = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/appcache/appinfo.vdf")

    /** The shared depots for [appId]: from the cache, else the client's app-info file; null when unknown. */
    @Synchronized
    fun info(context: Context, appId: Int, appinfo: File = appinfoFile(context)): Info? {
        val cacheFile = AtomicFile(File(context.filesDir, CACHE))
        val cache = runCatching { JSONObject(cacheFile.readFully().toString(Charsets.UTF_8)) }.getOrDefault(JSONObject())
        val now = System.currentTimeMillis()
        val mtime = appinfo.lastModified()
        val known = cache.optJSONObject(appId.toString())
        known?.let { o ->
            if (!o.optBoolean("miss")) return Info(o.optString("name"), o.optJSONArray("depots").strings())
            // A miss is read again when the client has written its cache since (the user may have
            // looked at the app in Steam), else a week later.
            if (now - o.optLong("at") < RETRY_AFTER_MS && o.optLong("mtime") == mtime) return null
        }
        val read = runCatching { read(appinfo, appId) }.onFailure { Log.w(TAG, "appinfo: ${it.javaClass.simpleName}") }.getOrNull()
        val entry = JSONObject().put("at", now).put("mtime", mtime)
        if (read == null) entry.put("miss", true) else entry.put("name", read.name).put("depots", JSONArray(read.sharedDepots))
        cache.put(appId.toString(), entry)
        val out = cacheFile.startWrite()
        try { out.write(cache.toString().toByteArray()); cacheFile.finishWrite(out) } catch (e: Exception) { cacheFile.failWrite(out) }
        return read
    }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }

    /**
     * [appId]'s entry in an appinfo.vdf (versions 27, 28 and 29, the last with its keys in a string
     * table), or null when the file does not have it.
     */
    internal fun read(file: File, appId: Int): Info? {
        if (!file.isFile) return null
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            raf.readFully(head.array(), 0, 8)
            val magic = head.getInt(0)
            val version = magic and 0xFF
            if (magic ushr 8 != 0x075644 || version !in 0x27..0x29) return null
            var strings: List<String>? = null
            var pos = 8L
            if (version >= 0x29) {
                raf.readFully(head.array(), 8, 8)
                val tableAt = head.getLong(8)
                strings = readStrings(raf, tableAt)
                pos = 16L
            }
            val fixed = if (version >= 0x28) 4 + 4 + 8 + 20 + 4 + 20 else 4 + 4 + 8 + 20 + 4
            val entry = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            while (pos + 8 <= raf.length()) {
                raf.seek(pos)
                raf.readFully(entry.array(), 0, 8)
                val id = entry.getInt(0)
                val size = entry.getInt(4).toLong() and 0xFFFFFFFFL
                if (id == 0) return null
                if (id == appId) {
                    if (size > 64L * 1024 * 1024) return null
                    val body = ByteArray(size.toInt())
                    raf.readFully(body)
                    val kv = ByteBuffer.wrap(body, fixed, body.size - fixed).order(ByteOrder.LITTLE_ENDIAN)
                    val tree = readMap(kv, strings)
                    @Suppress("UNCHECKED_CAST")
                    val app = (tree["appinfo"] as? Map<String, Any>) ?: tree
                    return infoOf(app)
                }
                pos += 8 + size
            }
            return null
        }
    }

    @Suppress("UNCHECKED_CAST")
    internal fun infoOf(app: Map<String, Any>): Info {
        val name = (app["common"] as? Map<String, Any>)?.get("name") as? String ?: ""
        val depots = (app["depots"] as? Map<String, Any>).orEmpty()
        val shared = depots.mapNotNull { (key, value) ->
            val depot = value as? Map<String, Any> ?: return@mapNotNull null
            key.takeIf { (depot["depotfromapp"] as? String) == SHARED_APP || (key in SteamRedists.DEPOTS && depot["sharedinstall"] != null) }
        }
        return Info(name, shared)
    }

    private fun readStrings(raf: RandomAccessFile, at: Long): List<String> {
        raf.seek(at)
        val count = Integer.reverseBytes(raf.readInt())
        val rest = ByteArray((raf.length() - at - 4).toInt().coerceAtLeast(0))
        raf.readFully(rest)
        val out = ArrayList<String>(count)
        var start = 0
        for (i in rest.indices) {
            if (out.size == count) break
            if (rest[i] == 0.toByte()) { out.add(String(rest, start, i - start, Charsets.UTF_8)); start = i + 1 }
        }
        return out
    }

    private fun cstring(b: ByteBuffer): String {
        val start = b.position()
        while (b.get() != 0.toByte()) { }
        val bytes = ByteArray(b.position() - start - 1)
        b.position(start); b.get(bytes); b.get()
        return String(bytes, Charsets.UTF_8)
    }

    /** One binary key-value map, to its end marker; keys by name, or by index into [strings]. */
    private fun readMap(b: ByteBuffer, strings: List<String>?): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        while (b.hasRemaining()) {
            val type = b.get().toInt() and 0xFF
            if (type == 0x08) return out
            val key = if (strings != null) strings.getOrElse(b.int) { "" } else cstring(b)
            when (type) {
                0x00 -> out[key] = readMap(b, strings)
                0x01 -> out[key] = cstring(b)
                0x02 -> out[key] = b.int.toString()
                0x03 -> out[key] = b.float.toString()
                0x07, 0x0A -> out[key] = b.long.toString()
                else -> return out
            }
        }
        return out
    }
}
