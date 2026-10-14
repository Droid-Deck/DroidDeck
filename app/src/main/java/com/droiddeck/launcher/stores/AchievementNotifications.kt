package com.droiddeck.launcher.stores

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

object AchievementNotifications {
    data class Unlock(val client: String, val id: String, val name: String, val description: String, val icon: String?, val game: String)

    private var session: String? = null
    private val seen = HashSet<String>()
    var pending by mutableStateOf<List<Unlock>>(emptyList())
        private set

    fun begin(owner: String?) = StoresState.post {
        session = owner
        seen.clear()
        pending = emptyList()
    }

    fun end(owner: String?) = StoresState.post {
        if (session == owner) {
            session = null
            seen.clear()
            pending = emptyList()
        }
    }

    fun unlock(owner: String, unlock: Unlock) = StoresState.post {
        if (session == owner && seen.add("${unlock.client}:${unlock.id}")) {
            pending = (pending + unlock).take(16)
        }
    }

    fun dismiss(unlock: Unlock) {
        if (pending.firstOrNull() === unlock) pending = pending.drop(1)
    }

    fun parse(event: JSONObject, game: String): Unlock? {
        val client = event.optString("client_id")
        val id = event.optString("id")
        val name = event.optString("name").trim()
        if (!client.matches(Regex("[0-9]{1,32}")) || id.isBlank() || id.length > 256 || name.isEmpty()) return null
        val icon = event.optString("icon").takeIf { it.length <= 2048 && it.startsWith("https://") }
        return Unlock(client, id, name.take(256), event.optString("description").take(1024), icon, game.take(256))
    }
}
