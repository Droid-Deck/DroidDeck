package com.droiddeck.launcher.stores.itch

import org.json.JSONArray
import org.json.JSONObject

internal object ItchUploads {
    private val GAME_TYPES = setOf("default", "flash", "unity", "java", "html", "")
    private val PLATFORM_TRAITS = setOf("p_windows", "p_linux", "p_osx", "p_android")

    fun windows(uploads: JSONArray?): JSONObject? {
        if (uploads == null) return null
        val all = (0 until uploads.length()).mapNotNull { uploads.optJSONObject(it) }
            .filter { it.optString("storage") != "external" && !it.optBoolean("demo") }
        if (all.isEmpty()) return null
        val games = all.filter { it.optString("type") in GAME_TYPES }
        val preferred = games.ifEmpty { all }
        val candidates = preferred.filter { windows(it) }.ifEmpty { preferred }
        return candidates.maxWithOrNull(compareBy({ it.optString("updated_at", it.optString("updatedAt")) }, { it.optLong("id") }))
    }

    fun windows(upload: JSONObject): Boolean {
        val platforms = upload.optJSONObject("platforms")
        if (platforms != null) {
            if (platforms.optBoolean("windows")) return true
            if (platforms.optBoolean("linux") || platforms.optBoolean("osx") || platforms.optBoolean("android")) return false
        }
        val traits = upload.optJSONArray("traits") ?: return true
        val names = (0 until traits.length()).map { traits.optString(it) }
        if (names.any { it == "p_windows" }) return true
        return names.none { it in PLATFORM_TRAITS }
    }

    fun size(upload: JSONObject): Long = upload.optLong("size", upload.optLong("size_bytes"))

    fun filename(upload: JSONObject): String = upload.optString("filename").substringAfterLast('/')
}
