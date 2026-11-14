package com.droiddeck.launcher.session

import org.json.JSONObject
import java.io.File

object SteamImportPrompt {
    data class Ask(val token: String, val games: List<String>)

    private fun folder(root: File) = File(root, "root/.cache/droiddeck")

    fun read(root: File): Ask? {
        val text = runCatching { File(folder(root), "steam-import.json").readText() }.getOrNull() ?: return null
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val token = json.optString("token").takeIf { it.isNotEmpty() } ?: return null
        val list = json.optJSONArray("games")
        val games = if (list == null) emptyList() else (0 until list.length()).mapNotNull { list.optString(it).takeIf(String::isNotBlank) }
        return Ask(token, games)
    }

    fun answer(root: File, token: String, now: Boolean): Boolean = runCatching {
        val dir = folder(root).apply { mkdirs() }
        val temp = File(dir, "steam-import.answer.tmp")
        temp.writeText(JSONObject().put("token", token).put("choice", if (now) "now" else "later").toString())
        temp.renameTo(File(dir, "steam-import.answer"))
    }.getOrDefault(false)
}
