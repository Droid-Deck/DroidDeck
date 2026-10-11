package com.droiddeck.launcher.stores

import java.io.File
import org.json.JSONObject

/**
 * Game sizes as found, kept per game AND the version they were found for, in
 * `filesDir/stores/<store>/sizes.json`: a size is never fetched twice for one version, and a
 * new version (a library refresh brings it) simply misses and is looked up again. The file carries
 * its format; one in another format is discarded, not read.
 */
class SizeCache(private val file: File) {
    private val sizes = HashMap<String, Pair<String, Long>>()

    init {
        runCatching {
            val o = JSONObject(file.readText())
            if (o.optInt("format") == FORMAT) {
                val s = o.getJSONObject("sizes")
                for (k in s.keys()) s.optJSONObject(k)?.let { e -> sizes[k] = e.optString("v") to e.optLong("b") }
            }
        }
    }

    @Synchronized
    fun get(id: String, version: String): Long? = sizes[id]?.takeIf { it.first == version && it.second > 0 }?.second

    @Synchronized
    fun put(id: String, version: String, bytes: Long) {
        if (bytes <= 0) return
        sizes[id] = version to bytes
        runCatching {
            file.parentFile?.mkdirs()
            val s = JSONObject()
            for ((k, v) in sizes) s.put(k, JSONObject().put("v", v.first).put("b", v.second))
            val tmp = File(file.path + ".tmp")
            tmp.writeText(JSONObject().put("format", FORMAT).put("sizes", s).toString())
            tmp.renameTo(file)
        }
    }

    companion object {
        const val FORMAT = 1
        fun forStore(filesDir: File, store: Store) = SizeCache(File(filesDir, "stores/${store.id}/sizes.json"))
    }
}
