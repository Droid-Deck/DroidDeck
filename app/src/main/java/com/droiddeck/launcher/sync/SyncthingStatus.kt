package com.droiddeck.launcher.sync

import org.json.JSONArray
import org.json.JSONObject

data class SyncFolder(
    val id: String, val label: String, val path: String, val paused: Boolean, val blocked: Boolean,
    val state: String, val globalBytes: Long, val needBytes: Long, val errors: Int, val sharedWith: List<String>,
) {
    val syncing get() = !paused && !blocked && state in ACTIVE && needBytes > 0
    val outOfSync get() = !paused && !blocked && (errors > 0 || state == "error" || (state == "idle" && needBytes > 0))

    companion object { private val ACTIVE = setOf("syncing", "sync-preparing", "sync-waiting") }
}

data class SyncDevice(val id: String, val name: String, val connected: Boolean, val address: String?, val lastSeen: Long?)

data class SyncPendingFolder(val id: String, val label: String, val offeredBy: String, val deviceId: String)

data class SyncSnapshot(
    val myId: String,
    val folders: List<SyncFolder>,
    val devices: List<SyncDevice>,
    val pendingFolders: List<SyncPendingFolder>,
    val pendingDevices: List<String>,
    val inBytesPerSecond: Long,
    val outBytesPerSecond: Long,
) {
    val blocked get() = folders.filter { it.blocked }
    val needsAttention get() = blocked.isNotEmpty() || pendingFolders.isNotEmpty() || pendingDevices.isNotEmpty() || folders.any { it.outOfSync }
    val syncing get() = folders.any { it.syncing }
    val needBytes get() = folders.filter { it.syncing }.sumOf { it.needBytes }

    /** Share of the syncing folders' data already in place, 0..100. */
    val percent: Int get() {
        val active = folders.filter { it.syncing }
        val global = active.sumOf { it.globalBytes }
        if (global <= 0) return 0
        return (((global - active.sumOf { it.needBytes }).coerceAtLeast(0) * 100) / global).toInt().coerceIn(0, 99)
    }
}

internal object SyncthingParse {
    fun deviceNames(devices: JSONArray, myId: String): Map<String, String> = (0 until devices.length())
        .map { devices.getJSONObject(it) }
        .filter { it.getString("deviceID") != myId }
        .associate { it.getString("deviceID") to it.optString("name").ifEmpty { it.getString("deviceID").take(7) } }

    fun pendingDevices(json: JSONObject): List<String> =
        json.keys().asSequence().map { id -> json.optJSONObject(id)?.optString("name").orEmpty().ifEmpty { id.take(7) } }.toList()

    fun pendingFolders(json: JSONObject, names: Map<String, String>): List<SyncPendingFolder> =
        json.keys().asSequence().flatMap { folderId ->
            val offeredBy = json.getJSONObject(folderId).optJSONObject("offeredBy") ?: JSONObject()
            offeredBy.keys().asSequence().map { device ->
                val label = offeredBy.getJSONObject(device).optString("label").ifEmpty { folderId }
                SyncPendingFolder(folderId, label, names[device] ?: device.take(7), device)
            }
        }.toList()
}
