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

    fun newestWindows(uploads: JSONArray?): JSONObject? {
        if (uploads == null) return null
        val windows = (0 until uploads.length()).mapNotNull { uploads.optJSONObject(it) }
            .filter { it.optString("storage") != "external" && !it.optBoolean("demo") && windows(it) }
        val games = windows.filter { it.optString("type") in GAME_TYPES }
        return games.ifEmpty { windows }.maxWithOrNull(compareBy({ updatedAt(it) }, { it.optLong("id") }))
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

    fun platforms(upload: JSONObject): Set<String> {
        val found = LinkedHashSet<String>()
        upload.optJSONObject("platforms")?.let { flags ->
            for (name in listOf("windows", "linux", "osx", "android")) if (flags.optBoolean(name)) found.add(name)
        }
        upload.optJSONArray("traits")?.let { traits ->
            for (i in 0 until traits.length()) when (traits.optString(i)) {
                "p_windows" -> found.add("windows")
                "p_linux" -> found.add("linux")
                "p_osx" -> found.add("osx")
                "p_android" -> found.add("android")
            }
        }
        if (upload.optString("type") == "html") found.add("web")
        return if (found.isEmpty()) setOf("windows") else found
    }

    fun byId(uploads: JSONArray?, id: String): JSONObject? {
        if (uploads == null || id.isBlank()) return null
        return (0 until uploads.length()).mapNotNull { uploads.optJSONObject(it) }.firstOrNull { it.optString("id") == id }
    }

    fun updatedAt(upload: JSONObject): String = upload.optString("updated_at", upload.optString("updatedAt"))

    fun size(upload: JSONObject): Long = upload.optLong("size", upload.optLong("size_bytes"))

    fun filename(upload: JSONObject): String = upload.optString("filename").substringAfterLast('/')
}
