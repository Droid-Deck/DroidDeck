package com.droiddeck.launcher.input

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

private val ICP_NAMES: Map<Int, String> = mapOf(
    103 to "KEY_UP", 106 to "KEY_RIGHT", 108 to "KEY_DOWN", 105 to "KEY_LEFT",
    28 to "KEY_ENTER", 1 to "KEY_ESC", 14 to "KEY_BKSP", 111 to "KEY_DEL", 15 to "KEY_TAB",
    57 to "KEY_SPACE", 29 to "KEY_CTRL_L", 97 to "KEY_CTRL_R", 110 to "KEY_INSERT",
    42 to "KEY_SHIFT_L", 54 to "KEY_SHIFT_R", 56 to "KEY_ALT_L", 100 to "KEY_ALT_R",
    102 to "KEY_HOME", 107 to "KEY_END", 99 to "KEY_PRTSCN", 104 to "KEY_PG_UP", 109 to "KEY_PG_DOWN",
    58 to "KEY_CAPS_LOCK", 69 to "KEY_NUM_LOCK", 41 to "KEY_GRAVE", 12 to "KEY_MINUS",
    11 to "KEY_0", 2 to "KEY_1", 3 to "KEY_2", 4 to "KEY_3", 5 to "KEY_4", 6 to "KEY_5",
    7 to "KEY_6", 8 to "KEY_7", 9 to "KEY_8", 10 to "KEY_9",
    30 to "KEY_A", 48 to "KEY_B", 46 to "KEY_C", 32 to "KEY_D", 18 to "KEY_E", 33 to "KEY_F",
    34 to "KEY_G", 35 to "KEY_H", 23 to "KEY_I", 36 to "KEY_J", 37 to "KEY_K", 38 to "KEY_L",
    50 to "KEY_M", 49 to "KEY_N", 24 to "KEY_O", 25 to "KEY_P", 16 to "KEY_Q", 19 to "KEY_R",
    31 to "KEY_S", 20 to "KEY_T", 22 to "KEY_U", 47 to "KEY_V", 17 to "KEY_W", 45 to "KEY_X",
    21 to "KEY_Y", 44 to "KEY_Z",
    26 to "KEY_BRACKET_LEFT", 27 to "KEY_BRACKET_RIGHT", 43 to "KEY_BACKSLASH", 53 to "KEY_SLASH",
    39 to "KEY_SEMICOLON", 51 to "KEY_COMMA", 52 to "KEY_PERIOD", 40 to "KEY_APOSTROPHE",
    59 to "KEY_F1", 60 to "KEY_F2", 61 to "KEY_F3", 62 to "KEY_F4", 63 to "KEY_F5", 64 to "KEY_F6",
    65 to "KEY_F7", 66 to "KEY_F8", 67 to "KEY_F9", 68 to "KEY_F10", 87 to "KEY_F11", 88 to "KEY_F12",
    79 to "KEY_KP_1", 80 to "KEY_KP_2", 81 to "KEY_KP_3", 75 to "KEY_KP_4", 76 to "KEY_KP_5",
    77 to "KEY_KP_6", 71 to "KEY_KP_7", 72 to "KEY_KP_8", 73 to "KEY_KP_9", 82 to "KEY_KP_0",
)

private val ICP_CODES: Map<String, Int> = ICP_NAMES.entries.associate { (code, name) -> name to code }

private fun icpShape(shape: Int): String = when (shape) {
    SCREEN_KEY_CIRCLE -> "CIRCLE"
    SCREEN_KEY_RECT -> "RECT"
    SCREEN_KEY_ROUND -> "ROUND_RECT"
    SCREEN_KEY_DPAD -> "ROUND_RECT"
    SCREEN_KEY_STICK -> "ROUND_RECT"
    SCREEN_KEY_TRIGGER -> "ROUND_RECT"
    else -> "ROUND_RECT"
}

private fun screenKeyShape(shape: String): Int = when (shape) {
    "CIRCLE" -> SCREEN_KEY_CIRCLE
    "RECT" -> SCREEN_KEY_RECT
    "SQUARE" -> SCREEN_KEY_ROUND
    else -> SCREEN_KEY_PILL
}

fun exportScreenLayout(keys: List<ScreenKey>, widthPx: Int, density: Float): String {
    val snap = widthPx / 100f
    val elements = JSONArray()
    keys.forEach { key ->
        val binds = JSONArray()
        val directional = key.shape == SCREEN_KEY_DPAD || key.shape == SCREEN_KEY_STICK
        key.codes.forEach { code ->
            val name = ICP_NAMES[code]
            if (name != null) binds.put(name) else if (directional) binds.put("")
        }
        if (binds.length() == 0) return@forEach
        val shape = icpShape(key.shape)
        val sizePx = key.size * density
        val scale = when {
            key.shape == SCREEN_KEY_DPAD -> sizePx * 2.3f / (14f * snap)
            key.shape == SCREEN_KEY_STICK -> sizePx * 2.3f / (14f * snap)
            shape == "CIRCLE" -> sizePx / (6f * snap)
            else -> sizePx / (4f * snap)
        }
        elements.put(
            JSONObject()
                .put(
                    "type",
                    when (key.shape) {
                        SCREEN_KEY_DPAD -> "D_PAD"
                        SCREEN_KEY_STICK -> "STICK"
                        else -> "BUTTON"
                    },
                )
                .put("shape", shape)
                .put("customColor", 0)
                .put("bindings", binds)
                .put("scale", scale)
                .put("x", key.x)
                .put("y", key.y)
                .put("toggleSwitch", key.toggle)
                .put("swipeable", false)
                .put("text", key.text)
                .put("iconId", 0)
                .apply { if (key.opacity < 1f) put("opacity", key.opacity) },
        )
    }
    return JSONObject()
        .put("id", 0)
        .put("name", "DroidDeck")
        .put("cursorSpeed", 1.5f)
        .put("elements", elements)
        .toString()
}

fun importScreenLayout(raw: String, widthPx: Int, density: Float): List<ScreenKey>? {
    val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    val elements = root.optJSONArray("elements") ?: return null
    val snap = widthPx / 100f
    val keys = mutableListOf<ScreenKey>()
    for (i in 0 until elements.length()) {
        val element = elements.optJSONObject(i) ?: continue
        val type = element.optString("type", "BUTTON")
        if (type != "BUTTON" && type != "D_PAD" && type != "STICK") continue
        val binds = element.optJSONArray("bindings") ?: continue
        val raw = (0 until binds.length()).map { ICP_CODES[binds.optString(it)] ?: 0 }
        val codes = if (type == "D_PAD" || type == "STICK") {
            raw.dropLastWhile { it == 0 }.ifEmpty { listOf(0) }
        } else {
            raw.filter { it != 0 }
        }
        if (codes.isEmpty()) continue
        val dpad = type == "D_PAD"
        val stick = type == "STICK"
        val shape = when {
            dpad -> SCREEN_KEY_DPAD
            stick -> SCREEN_KEY_STICK
            else -> screenKeyShape(element.optString("shape"))
        }
        val scale = element.optDouble("scale", 1.0).toFloat().takeIf { it > 0f } ?: 1f
        val sizePx = when {
            dpad || stick -> 14f * snap * scale / 2.3f
            shape == SCREEN_KEY_CIRCLE -> 6f * snap * scale
            else -> 4f * snap * scale
        }
        keys += ScreenKey(
            codes = codes,
            x = element.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f),
            y = element.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f),
            size = (sizePx / density).roundToInt().coerceIn(ScreenKeys.MIN_SIZE, ScreenKeys.MAX_SIZE),
            text = element.optString("text").take(24),
            opacity = element.optDouble("opacity", 1.0).toFloat().coerceIn(0.2f, 1f),
            shape = shape,
            toggle = element.optBoolean("toggleSwitch"),
        )
    }
    return keys
}
