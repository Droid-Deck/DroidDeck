package com.droiddeck.launcher.stores.epic

import org.json.JSONArray
import org.json.JSONObject

class EpicCdn(val base: String, val dir: String, val auth: String)

class EpicManifest internal constructor(
    internal val bytes: ByteArray,
    val files: List<FileEntry>,
    val buildVersion: String,
    val launchExe: String,
    val prereqName: String,
    val prereqPath: String,
    val prereqRead: Boolean,
) {
    class FileEntry(val path: String, private val size: Long, val tags: List<String>) {
        fun fileSize(): Long = size
    }
}

object EpicDownloadManager {
    private fun info(json: String): JSONObject? = runCatching { JSONObject(json) }.getOrNull()

    @JvmStatic
    fun parseCdnUrls(json: String): List<EpicCdn> {
        val info = info(json) ?: return emptyList()
        val list = EpicNative.call("build.cdns", JSONObject().put("info", info))?.optJSONArray("cdns") ?: return emptyList()
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }.map {
            EpicCdn(it.optString("base"), it.optString("dir"), it.optString("auth"))
        }
    }

    @JvmStatic
    fun downloadManifest(json: String, cdns: List<EpicCdn>): ByteArray? {
        val info = info(json) ?: return null
        if (cdns.isEmpty()) return null
        val list = JSONArray()
        cdns.forEach { list.put(JSONObject().put("base", it.base).put("dir", it.dir).put("auth", it.auth)) }
        return EpicNative.fetch("manifest.download", JSONObject().put("info", info).put("cdns", list))
    }

    @JvmStatic
    fun parseManifest(bytes: ByteArray): EpicManifest? {
        val summary = EpicNative.inspect(bytes, JSONObject().put("op", "summary")) ?: return null
        if (summary.has("error")) return null
        val list = summary.optJSONArray("files") ?: return null
        val files = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.map { f ->
            val tags = f.optJSONArray("tags")
            EpicManifest.FileEntry(f.optString("path"), f.optLong("size", 0L), List(tags?.length() ?: 0) { tags!!.optString(it) })
        }
        return EpicManifest(
            bytes, files,
            buildVersion = summary.optString("buildVersion"),
            launchExe = summary.optString("launchExe"),
            prereqName = summary.optString("prereqName"),
            prereqPath = summary.optString("prereqPath"),
            prereqRead = summary.optBoolean("prereqRead", false),
        )
    }

    @JvmStatic
    fun resolveInstallFiles(manifest: EpicManifest, tags: List<String>): List<EpicManifest.FileEntry> {
        val answer = EpicNative.inspect(manifest.bytes, JSONObject().put("op", "select").put("tags", JSONArray(tags))) ?: return emptyList()
        val indices = answer.optJSONArray("indices") ?: return emptyList()
        return (0 until indices.length()).mapNotNull { manifest.files.getOrNull(indices.optInt(it, -1)) }
    }

    @JvmStatic
    fun fetchPrereq(json: String): Array<String>? {
        val info = info(json) ?: return null
        val answer = EpicNative.call("build.prereq", JSONObject().put("info", info)) ?: return null
        if (answer.has("error") || !answer.optBoolean("read", false)) return null
        return arrayOf(answer.optString("name"), answer.optString("path"))
    }
}
