package com.droiddeck.launcher.frontend

import org.json.JSONObject

/** Where Steam pins a game's logo on its hero image and how big it draws it; the same JSON Steam keeps in `<appid>.json`. */
object LogoPosition {
    enum class Pin(val steam: String) {
        UPPER_LEFT("UpperLeft"), UPPER_CENTER("UpperCenter"), CENTER("CenterCenter"), BOTTOM_LEFT("BottomLeft"), BOTTOM_CENTER("BottomCenter");

        companion object {
            fun of(steam: String): Pin? = values().firstOrNull { it.steam == steam }
        }
    }

    data class Value(val pin: Pin, val widthPct: Int, val heightPct: Int)

    const val MIN_PCT = 10
    const val MAX_PCT = 100

    /** What Steam draws when a game has no custom position. */
    val DEFAULT = Value(Pin.BOTTOM_LEFT, 50, 50)

    private const val VERSION = 1
    private const val KEY = "logoPosition"

    fun sized(pin: Pin, size: Int): Value = size.coerceIn(MIN_PCT, MAX_PCT).let { Value(pin, it, it) }

    /** `{"nVersion":1,"logoPosition":{...}}`, with whatever else [into] already held kept as it was. */
    fun toJson(value: Value, into: JSONObject = JSONObject()): JSONObject = into
        .put("nVersion", VERSION)
        .put(KEY, JSONObject().put("pinnedPosition", value.pin.steam).put("nWidthPct", value.widthPct).put("nHeightPct", value.heightPct))

    fun parse(text: String): Value? = runCatching { parse(JSONObject(text)) }.getOrNull()

    fun parse(json: JSONObject): Value? {
        val inner = json.optJSONObject(KEY) ?: return null
        val pin = Pin.of(inner.optString("pinnedPosition")) ?: return null
        if (!inner.has("nWidthPct") || !inner.has("nHeightPct")) return null
        return Value(pin, inner.optInt("nWidthPct").coerceIn(MIN_PCT, MAX_PCT), inner.optInt("nHeightPct").coerceIn(MIN_PCT, MAX_PCT))
    }

    /** [existing] without the logo position; null when nothing but the version number would be left. */
    fun without(existing: JSONObject): JSONObject? {
        existing.remove(KEY)
        val rest = existing.keys().asSequence().filter { it != "nVersion" }.toList()
        return if (rest.isEmpty()) null else existing
    }
}
