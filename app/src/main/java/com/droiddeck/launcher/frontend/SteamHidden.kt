package com.droiddeck.launcher.frontend

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal object SteamHidden {
    private const val KEY = "user-collections.hidden"

    private fun file(root: File): File? = AddedGames.accountConfig(root)?.let { File(it, "cloudstorage/cloud-storage-namespace-1.json") }

    private fun ids(collection: JSONObject, key: String): List<Long> =
        collection.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optLong(it) } }.orEmpty()

    fun read(root: File): Set<Long> = runCatching {
        val entries = JSONArray(file(root)?.readText() ?: return emptySet())
        val entry = (0 until entries.length()).map { entries.getJSONArray(it) }
            .firstOrNull { it.optString(0) == KEY }?.optJSONObject(1)
        if (entry == null || entry.optBoolean("is_deleted")) emptySet() else {
            val collection = JSONObject(entry.optString("value"))
            ids(collection, "added").toSet() - ids(collection, "removed").toSet()
        }
    }.getOrDefault(emptySet())

    fun write(root: File, appId: Long, hidden: Boolean, now: Long = System.currentTimeMillis() / 1000): Boolean = runCatching {
        val target = file(root) ?: return false
        val entries = JSONArray(target.readText())
        val slot = (0 until entries.length()).firstOrNull { entries.getJSONArray(it).optString(0) == KEY }
        val entry = slot?.let { entries.getJSONArray(it).optJSONObject(1) } ?: JSONObject().put("key", KEY)
        val live = !entry.optBoolean("is_deleted")
        val collection = if (live && entry.has("value")) JSONObject(entry.optString("value")) else JSONObject().put("id", "hidden")
        val added = ids(collection, "added").filter { it != appId }
        val removed = ids(collection, "removed").filter { it != appId }
        collection.put("added", JSONArray(if (hidden) added + appId else added))
        collection.put("removed", JSONArray(if (hidden) removed else removed + appId))
        entry.put("value", collection.toString())
        entry.put("timestamp", now)
        entry.put("is_deleted", false)
        entry.opt("version")?.toString()?.toLongOrNull()?.let { entry.put("version", (it + 1).toString()) }
        if (slot == null) entries.put(JSONArray().put(KEY).put(entry))
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(entries.toString())
        if (!temp.renameTo(target)) {
            target.writeText(temp.readText())
            temp.delete()
        }
        markModified(File(target.parentFile, "cloud-storage-namespace-1.modified.json"))
        true
    }.getOrDefault(false)

    private fun markModified(file: File) {
        val pending = runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray())
        if ((0 until pending.length()).any { pending.optString(it) == KEY }) return
        file.writeText(pending.put(KEY).toString())
    }
}
