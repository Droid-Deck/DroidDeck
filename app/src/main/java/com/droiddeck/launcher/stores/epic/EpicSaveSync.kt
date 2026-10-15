package com.droiddeck.launcher.stores.epic

import com.droiddeck.launcher.stores.CloudFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

object EpicSaveSync {
    private fun base(token: String, accountId: String, appName: String) =
        JSONObject().put("accessToken", token).put("accountId", accountId).put("appName", appName)

    fun list(token: String, accountId: String, appName: String): List<CloudFile> {
        val answer = EpicNative.call("cloud.list", base(token, accountId, appName)) ?: throw IOException("Epic cloud is unavailable")
        if (answer.has("error")) throw IOException(answer.optString("error"))
        val files = answer.optJSONArray("files") ?: return emptyList()
        return (0 until files.length()).mapNotNull { files.optJSONObject(it) }.map { f ->
            CloudFile(
                f.optString("name"),
                f.optLong("modified", -1L),
                md5 = f.optString("hash").ifEmpty { null },
                readLink = f.optString("readLink").ifEmpty { null },
            )
        }
    }

    fun read(file: CloudFile): Pair<ByteArray, Long> {
        val link = file.readLink ?: throw IOException("no download link for ${file.name}")
        val bytes = EpicNative.fetch("cloud.read", JSONObject().put("url", link)) ?: throw IOException("Epic cloud download failed")
        return bytes to file.modifiedMs
    }

    fun write(token: String, accountId: String, appName: String, files: Map<String, ByteArray>): Map<String, Long> {
        if (files.isEmpty()) return emptyMap()
        val answer = EpicNative.call("cloud.writeLinks", base(token, accountId, appName).put("names", JSONArray(files.keys.toList())))
            ?: throw IOException("Epic cloud is unavailable")
        if (answer.has("error")) throw IOException(answer.optString("error"))
        val links = answer.optJSONObject("links") ?: JSONObject()
        return files.mapValues { (name, data) ->
            val link = links.optString(name, "").ifEmpty { throw IOException("no upload link for $name") }
            val sent = EpicNative.send("cloud.write", JSONObject().put("url", link), data) ?: throw IOException("Epic cloud upload failed")
            if (sent.has("error")) throw IOException(sent.optString("error"))
            -1L
        }
    }
}
