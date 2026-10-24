package com.droiddeck.launcher.sync

import android.net.LocalSocket
import android.net.LocalSocketAddress
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

class SyncthingApi(val socket: File, private val apiKey: String) {
    fun ping(): Boolean = runCatching { get("/rest/system/ping").optString("ping") == "pong" }.getOrDefault(false)
    fun myId(): String = get("/rest/system/status").getString("myID")
    fun folders(): JSONArray = getArray("/rest/config/folders")
    fun devices(): JSONArray = getArray("/rest/config/devices")
    fun folderStatus(id: String): JSONObject = get("/rest/db/status?folder=${enc(id)}")
    fun connections(): JSONObject = get("/rest/system/connections")
    fun deviceStats(): JSONObject = get("/rest/stats/device")
    fun pendingDevices(): JSONObject = get("/rest/cluster/pending/devices")
    fun pendingFolders(): JSONObject = get("/rest/cluster/pending/folders")

    fun defaultFolder(): JSONObject = get("/rest/config/defaults/folder")
    fun addFolder(folder: JSONObject) = send("POST", "/rest/config/folders", folder)
    fun pauseFolder(id: String) = merge("/rest/config/folders/${enc(id)}") { put("paused", true) }
    fun removeFolder(id: String) { send("DELETE", "/rest/config/folders/${enc(id)}", null) }
    fun pauseAll() { send("POST", "/rest/system/pause", null) }
    fun resumeAll() { send("POST", "/rest/system/resume", null) }
    fun setApiKey(key: String) = merge("/rest/config/gui") { put("apiKey", key) }
    fun shutdown() { send("POST", "/rest/system/shutdown", null) }

    private fun merge(path: String, change: JSONObject.() -> Unit) = send("PUT", path, get(path).apply(change))

    /** Long-polls for config saves; returns the last event id seen, or [since] on timeout. */
    fun waitForConfigSaved(since: Long, timeoutSeconds: Int): Pair<Long, Boolean> {
        val events = JSONArray(request("GET", "/rest/events?events=ConfigSaved&since=$since&timeout=$timeoutSeconds", null, (timeoutSeconds + 10) * 1000))
        if (events.length() == 0) return since to false
        return events.getJSONObject(events.length() - 1).getLong("id") to true
    }

    private fun get(path: String) = JSONObject(request("GET", path, null, TIMEOUT_MS))
    private fun getArray(path: String) = JSONArray(request("GET", path, null, TIMEOUT_MS))
    private fun send(method: String, path: String, body: JSONObject?) { request(method, path, body, TIMEOUT_MS) }

    private fun request(method: String, path: String, body: JSONObject?, timeoutMs: Int): String = LocalSocket().use { s ->
        s.connect(LocalSocketAddress(socket.path, LocalSocketAddress.Namespace.FILESYSTEM))
        s.soTimeout = timeoutMs
        val headers = listOf("X-API-Key" to apiKey) + if (body != null) listOf("Content-Type" to "application/json") else emptyList()
        SyncthingHttp.writeRequest(s.outputStream, method, path, headers, body?.toString()?.toByteArray())
        val (code, text) = SyncthingHttp.readResponse(s.inputStream.buffered())
        if (code !in 200..299) throw SyncthingApiException(code, text)
        text
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object { private const val TIMEOUT_MS = 5_000 }
}

class SyncthingApiException(val code: Int, message: String) : Exception("HTTP $code: $message")
