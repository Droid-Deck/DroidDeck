package com.droiddeck.launcher.companion

import org.json.JSONArray
import org.json.JSONObject

/** Vitals and quick slots read from SkyrimWebSocket (Skyrim) or Thor Pip-Boy (Fallout 4), which share a subscribe protocol. */
object RpgCompanion {
    const val SKYRIM_SE = 489830L
    const val FALLOUT_4 = 377160L
    const val SKYRIM_PORT = 8765
    const val PIPBOY_PORT = 8767

    data class Bar(val key: String, val value: Float, val max: Float)

    data class Slot(val key: String, val label: String, val detail: String, val active: Boolean)

    data class State(
        val bars: List<Bar> = emptyList(),
        val level: Int? = null,
        val facts: List<Pair<String, String>> = emptyList(),
        val slots: List<Slot> = emptyList(),
        val radio: Boolean? = null,
    )

    fun port(appId: Long): Int = if (appId == FALLOUT_4) PIPBOY_PORT else SKYRIM_PORT

    fun subscribe(appId: Long): String = if (appId == FALLOUT_4) {
        JSONObject().put("type", "subscribe").put("id", SUB).put("settings", JSONObject().put("frequency", 500))
            .put("fields", JSONArray(listOf("player.stats", "player.inventory", "radio"))).toString()
    } else {
        val fields = JSONObject()
        for ((alias, key) in SKYRIM_FIELDS) fields.put(alias, key)
        JSONObject().put("type", "subscribe").put("id", SUB)
            .put("settings", JSONObject().put("frequency", 500).put("sendOnChange", true)).put("fields", fields).toString()
    }

    fun reduce(appId: Long, state: State, text: String): State {
        val m = runCatching { JSONObject(text) }.getOrNull() ?: return state
        if (m.optString("type") != "data" || m.optString("id") != SUB) return state
        return if (appId == FALLOUT_4) pipBoy(state, m.optJSONObject("data") ?: return state)
        else skyrim(state, m.optJSONObject("fields") ?: return state)
    }

    fun trigger(appId: Long, slot: Slot, seq: Int): String = if (appId == FALLOUT_4) {
        JSONObject().put("type", "command").put("id", "dd-$seq").put("command", "toggle")
            .put("params", JSONObject().put("id", slot.key)).toString()
    } else {
        JSONObject().put("type", "command").put("id", "dd-$seq").put("command", "hotkey_trigger").put("slot", slot.key.toInt()).toString()
    }

    fun radio(on: Boolean, seq: Int): String =
        JSONObject().put("type", "command").put("id", "dd-$seq").put("command", "radio").put("params", JSONObject().put("enable", on)).toString()

    private fun skyrim(state: State, f: JSONObject): State {
        fun bar(key: String) = if (f.has(key) && f.has("$key.max")) Bar(key, f.optDouble(key).toFloat(), f.optDouble("$key.max").toFloat()) else null
        val bars = listOfNotNull(bar("health"), bar("magicka"), bar("stamina")).ifEmpty { state.bars }
        val slots = f.optJSONArray("hotkeys")?.let { arr ->
            List(arr.length()) { arr.optJSONObject(it) }.filterNotNull().filter { it.optBoolean("bound") }.map {
                Slot(it.optInt("slot").toString(), it.optString("name"), it.optString("school").ifEmpty { it.optString("categoryType") }, false)
            }
        } ?: state.slots
        val facts = buildList {
            if (f.has("weight") && f.has("carry")) add("weight" to "%.0f / %.0f".format(f.optDouble("weight"), f.optDouble("carry")))
        }.ifEmpty { state.facts }
        return state.copy(bars = bars, level = if (f.has("level")) f.optInt("level") else state.level, facts = facts, slots = slots)
    }

    private fun pipBoy(state: State, d: JSONObject): State {
        var next = state
        d.optJSONObject("player.stats")?.let { s ->
            next = next.copy(
                bars = listOf(Bar("hp", s.optDouble("hp").toFloat(), s.optDouble("hpMax").toFloat()),
                    Bar("ap", s.optDouble("ap").toFloat(), s.optDouble("apMax").toFloat())),
                level = s.optInt("level"),
                facts = listOf("caps" to s.optInt("caps").toString(), "rads" to s.optInt("rads").toString(),
                    "weight" to "%.0f / %.0f".format(s.optDouble("weight"), s.optDouble("weightMax"))),
            )
        }
        d.optJSONArray("player.inventory")?.let { inv ->
            next = next.copy(slots = List(inv.length()) { inv.optJSONObject(it) }.filterNotNull()
                .filter { it.has("fav") && it.optInt("fav", -1) >= 0 }
                .sortedBy { it.optInt("fav") }
                .map { Slot(it.optString("id"), it.optString("name"), countText(it), it.optBoolean("equipped")) })
        }
        d.optJSONObject("radio")?.let { next = next.copy(radio = it.optBoolean("enabled")) }
        return next
    }

    private fun countText(item: JSONObject): String = item.optInt("count", 1).let { if (it > 1) "×$it" else "" }

    private const val SUB = "droiddeck"
    private val SKYRIM_FIELDS = listOf(
        "health" to "ActorValue::kHealth", "health.max" to "ActorValue::kHealth::Permanent",
        "magicka" to "ActorValue::kMagicka", "magicka.max" to "ActorValue::kMagicka::Permanent",
        "stamina" to "ActorValue::kStamina", "stamina.max" to "ActorValue::kStamina::Permanent",
        "level" to "Player::Level", "weight" to "Player::InventoryWeight", "carry" to "Player::CarryWeight",
        "hotkeys" to "Hotkey::Items",
    )
}
