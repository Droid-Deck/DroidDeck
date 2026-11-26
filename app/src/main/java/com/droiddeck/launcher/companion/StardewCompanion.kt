package com.droiddeck.launcher.companion

import org.json.JSONArray
import org.json.JSONObject

object StardewCompanion {
    const val APP_ID = 413150L
    const val PORT = 7786
    const val HOTBAR = 12

    data class Day(
        val season: String, val day: Int, val year: Int, val weekday: String, val time: Int,
        val weather: String, val weatherTomorrow: String, val gold: Long,
        val energy: Int, val maxEnergy: Int, val health: Int, val maxHealth: Int, val location: String,
    )

    data class Item(val slot: Int, val id: String, val name: String, val count: Int, val quality: Int)

    data class Quest(val title: String, val objective: String, val daysLeft: Int, val complete: Boolean)

    data class Birthday(val name: String, val npc: String, val days: Int)

    data class State(
        val save: String? = null,
        val day: Day? = null,
        val selected: Int = 0,
        val items: List<Item> = emptyList(),
        val quests: List<Quest> = emptyList(),
        val birthdays: List<Birthday> = emptyList(),
        val sprites: Map<String, String> = emptyMap(),
    ) {
        val inGame: Boolean get() = !save.isNullOrEmpty()
        val hotbar: List<Item?> get() = List(HOTBAR) { slot -> items.firstOrNull { it.slot == slot && it.id.isNotEmpty() } }
    }

    fun reduce(state: State, text: String): State {
        val m = runCatching { JSONObject(text) }.getOrNull() ?: return state
        return when (m.optString("type")) {
            "game_info" -> state.copy(save = m.optString("saveName"))
            "sdv_day" -> state.copy(day = Day(
                m.optString("season"), m.optInt("day"), m.optInt("year"), m.optString("weekday"), m.optInt("timeOfDay"),
                m.optString("weatherToday"), m.optString("weatherTomorrow"), m.optLong("gold"),
                m.optInt("energy"), m.optInt("maxEnergy"), m.optInt("health"), m.optInt("maxHealth"), m.optString("location"),
            ))
            "sdv_inventory" -> state.copy(selected = m.optInt("selected"), items = objects(m.optJSONArray("items")).map {
                Item(it.optInt("slot"), it.optString("id"), it.optString("name"), it.optInt("count", 1), it.optInt("quality"))
            })
            "sdv_quests" -> state.copy(quests = objects(m.optJSONArray("quests")).map {
                Quest(it.optString("title"), it.optString("objective"), it.optInt("daysLeft", -1), flag(it, "complete"))
            })
            "sdv_villagers" -> state.copy(birthdays = objects(m.optJSONArray("villagers"))
                .filter { it.has("birthdayIn") && it.optInt("birthdayIn", -1) in 0..7 }
                .map { Birthday(it.optString("name"), it.optString("npc"), it.optInt("birthdayIn")) }
                .sortedBy { it.days })
            "sdv_sprite" -> {
                val id = m.optString("id")
                val png = m.optString("png")
                if (id.isEmpty() || png.isEmpty()) state else state.copy(sprites = state.sprites + (id to png))
            }
            else -> state
        }
    }

    fun selectSlot(slot: Int): String = JSONObject().put("type", "select_slot").put("slot", slot).toString()

    fun refresh(): String = JSONObject().put("type", "refresh").toString()

    /** 610 is 6:10 am; the game's clock runs past midnight up to 2600. */
    fun clock(time: Int, twelveHour: Boolean): String {
        val hours = time / 100 % 24
        val minutes = time % 100
        if (!twelveHour) return "%d:%02d".format(hours, minutes)
        val h = if (hours % 12 == 0) 12 else hours % 12
        return "%d:%02d %s".format(h, minutes, if (hours < 12) "am" else "pm")
    }

    private fun flag(o: JSONObject, name: String): Boolean = when (val v = o.opt(name)) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> v == "1" || v.equals("true", true)
        else -> false
    }

    private fun objects(array: JSONArray?): List<JSONObject> =
        if (array == null) emptyList() else List(array.length()) { array.optJSONObject(it) }.filterNotNull()
}
