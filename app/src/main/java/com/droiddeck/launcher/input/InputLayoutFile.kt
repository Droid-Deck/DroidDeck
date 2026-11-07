package com.droiddeck.launcher.input

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

internal object InputLayoutFile {
    private val NAMES: Map<Int, String> = mapOf(
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
    private val CODES: Map<String, Int> = NAMES.entries.associate { (code, name) -> name to code }

    fun fileName(layout: SavedInputLayout) = layout.name.replace(Regex("[^A-Za-z0-9 _.-]"), "_").trim().ifEmpty { "droiddeck-layout" } + ".icp"

    fun export(layout: SavedInputLayout, bindings: SavedInputBindings?, width: Int, height: Int): String? {
        val snap = width.coerceAtLeast(1) / 100f
        val elements = JSONArray()
        for (e in layout.elements) {
            val action = bindings?.action(e) ?: InputAction.Disabled
            val directional = e.kind == InputElementKind.DPAD || e.kind == InputElementKind.STICK
            val codes = when {
                directional && action is InputAction.Directions -> listOf(action.up, action.right, action.down, action.left)
                !directional && action is InputAction.Keys -> action.codes
                else -> continue
            }
            val binds = JSONArray()
            for (code in codes) {
                val name = NAMES[code]
                if (name != null) binds.put(name) else if (directional) binds.put("")
            }
            if ((0 until binds.length()).all { binds.getString(it).isEmpty() }) continue
            val shape = when {
                directional -> "ROUND_RECT"
                e.buttonShape == InputButtonShape.CIRCLE -> "CIRCLE"
                e.buttonShape == InputButtonShape.RECTANGLE -> "RECT"
                else -> "ROUND_RECT"
            }
            val sizePx = e.height * height
            val scale = when {
                directional -> sizePx * 2.3f / (14f * snap)
                shape == "CIRCLE" -> sizePx / (6f * snap)
                else -> sizePx / (4f * snap)
            }
            elements.put(JSONObject()
                .put("type", when (e.kind) { InputElementKind.DPAD -> "D_PAD"; InputElementKind.STICK -> "STICK"; else -> "BUTTON" })
                .put("shape", shape).put("customColor", 0).put("bindings", binds).put("scale", scale.toDouble())
                .put("x", (e.x + e.width / 2).toDouble()).put("y", (e.y + e.height / 2).toDouble())
                .put("toggleSwitch", e.toggle).put("swipeable", false)
                .put("text", if (e.icon != 0 || e.image.isNotEmpty()) "" else e.name).put("iconId", 0)
                .apply { if (e.opacity < 1f) put("opacity", e.opacity.toDouble()) })
        }
        if (elements.length() == 0) return null
        return JSONObject().put("id", 0).put("name", layout.name).put("cursorSpeed", 1.5).put("elements", elements).toString()
    }

    fun parse(raw: String, width: Int, density: Float): List<LegacyScreenKey>? {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val elements = root.optJSONArray("elements") ?: return null
        val snap = width.coerceAtLeast(1) / 100f
        val keys = mutableListOf<LegacyScreenKey>()
        for (i in 0 until elements.length()) {
            val element = elements.optJSONObject(i) ?: continue
            val type = element.optString("type", "BUTTON")
            if (type != "BUTTON" && type != "D_PAD" && type != "STICK") continue
            val binds = element.optJSONArray("bindings") ?: continue
            val raw = (0 until binds.length()).map { CODES[binds.optString(it)] ?: 0 }
            val directional = type != "BUTTON"
            val codes = when {
                !directional -> raw.filter { it != 0 }.distinct().take(8)
                type == "STICK" && raw.size <= 4 -> listOf(raw.getOrElse(0) { 0 }, 0, raw.getOrElse(1) { 0 }, 0, raw.getOrElse(2) { 0 }, 0, raw.getOrElse(3) { 0 }, 0)
                else -> raw.take(if (type == "STICK") 8 else 4)
            }
            if (codes.none { it != 0 }) continue
            val shape = when {
                type == "D_PAD" -> 5
                type == "STICK" -> 6
                else -> when (element.optString("shape")) { "CIRCLE" -> 2; "RECT" -> 3; "SQUARE" -> 1; else -> 0 }
            }
            val scale = element.optDouble("scale", 1.0).toFloat().takeIf { it.isFinite() && it > 0f } ?: 1f
            val sizePx = when {
                directional -> 14f * snap * scale / 2.3f
                shape == 2 -> 6f * snap * scale
                else -> 4f * snap * scale
            }
            fun coordinate(name: String) = element.optDouble(name, .5).toFloat().takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: .5f
            keys += LegacyScreenKey(codes, coordinate("x"), coordinate("y"), (sizePx / density).roundToInt().coerceIn(24, 140).toFloat(),
                text = element.optString("text").trim().take(24),
                opacity = element.optDouble("opacity", 1.0).toFloat().takeIf { it.isFinite() }?.coerceIn(.2f, 1f) ?: 1f,
                shape = shape, toggle = element.optBoolean("toggleSwitch"))
            if (keys.size == 64) break
        }
        return keys
    }

    fun import(context: Context, raw: String, fallbackName: String, screen: InputLayoutScreen, style: InputSurfaceStyle,
        width: Int, height: Int, density: Float, id: String = newInputId()): Pair<SavedInputLayout, SavedInputBindings>? {
        val keys = parse(raw, width, density)?.takeIf { it.isNotEmpty() } ?: return null
        val converted = runCatching { LegacyScreenKeyMigration.convert(context, keys, width, height, density) }.getOrNull() ?: return null
        val name = runCatching { JSONObject(raw).optString("name") }.getOrNull().orEmpty().trim().take(80).ifEmpty { fallbackName.trim().take(80) }
        val layout = SavedInputLayout(id, name, converted.map { it.first }, style = style, screen = screen)
        return layout to SavedInputBindings(newInputId(), name, false, id, elements = converted.associate { it.first.id to it.second })
    }
}
