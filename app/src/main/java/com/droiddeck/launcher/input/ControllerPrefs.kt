package com.droiddeck.launcher.input

import android.content.Context
import com.droiddeck.launcher.R

object ControllerPrefs {
    const val STEAM_BLUE = 0xFF1A9FFF.toInt()
    const val OFF = "off"

    val tints = listOf(
        STEAM_BLUE to R.string.ctrl_tint_steam_blue,
        0xFF66C0F4.toInt() to R.string.ctrl_tint_sky,
        0xFFE8EEF4.toInt() to R.string.ctrl_tint_white,
        0xFFC77DFF.toInt() to R.string.ctrl_tint_violet,
        0xFF3DDC84.toInt() to R.string.ctrl_tint_green,
        0xFFFFA726.toInt() to R.string.ctrl_tint_amber,
        0xFFFF5252.toInt() to R.string.ctrl_tint_red,
        0xFFFF6FB5.toInt() to R.string.ctrl_tint_pink,
    )

    val opacities = listOf(40, 60, 80, 100)

    val sizes = listOf(80, 90, 100, 110, 125)

    val mappable = listOf(
        "a" to R.string.ctrl_button_a, "b" to R.string.ctrl_button_b, "x" to R.string.ctrl_button_x, "y" to R.string.ctrl_button_y,
        "lb" to R.string.ctrl_button_lb, "rb" to R.string.ctrl_button_rb, "lt" to R.string.ctrl_button_lt, "rt" to R.string.ctrl_button_rt,
        "select" to R.string.ctrl_button_view, "start" to R.string.ctrl_button_menu,
    )

    val targets = listOf(
        "a" to R.string.ctrl_target_a, "b" to R.string.ctrl_target_b, "x" to R.string.ctrl_target_x, "y" to R.string.ctrl_target_y,
        "lb" to R.string.ctrl_target_lb, "rb" to R.string.ctrl_target_rb, "lt" to R.string.ctrl_target_lt, "rt" to R.string.ctrl_target_rt,
        "l3" to R.string.ctrl_target_l3, "r3" to R.string.ctrl_target_r3, "select" to R.string.ctrl_target_view, "start" to R.string.ctrl_target_menu, "guide" to R.string.ctrl_target_steam,
        "up" to R.string.ctrl_target_up, "down" to R.string.ctrl_target_down, "left" to R.string.ctrl_target_left, "right" to R.string.ctrl_target_right,
        OFF to R.string.ctrl_target_hidden,
    )

    class Settings(
        val tint: Int,
        val opacity: Int,
        val size: Int,
        val stickClick: Boolean,
        val adaptiveSticks: Boolean,
        val rumble: Boolean,
        val steamButton: Boolean,
        val qamButton: Boolean,
        val keyboardButton: Boolean,
        val selectSteam: Boolean,
        val joyConVr: Boolean,
        val snap: Boolean,
        val customLayout: Boolean,
        val mapping: Map<String, String>,
    )

    internal fun prefs(context: Context) = context.getSharedPreferences("controller", Context.MODE_PRIVATE)

    fun read(context: Context): Settings {
        val p = prefs(context)
        return Settings(
            tint = p.getInt("tint", STEAM_BLUE),
            opacity = p.getInt("opacity", 100).takeIf { it in opacities } ?: 100,
            size = p.getInt("size", 100).takeIf { it in sizes } ?: 100,
            stickClick = p.getBoolean("stickClick", true),
            adaptiveSticks = p.getBoolean("adaptiveSticks", true),
            rumble = rumbleEnabled(context),
            steamButton = p.getBoolean("steamButton", true),
            qamButton = p.getBoolean("qamButton", true),
            keyboardButton = p.getBoolean("keyboardButton", true),
            selectSteam = p.getBoolean("selectSteam", false),
            joyConVr = joyConVr(context),
            snap = p.getBoolean("snap", true),
            customLayout = p.all.keys.any { it.startsWith("layout.") },
            mapping = mappable.associate { (id, _) -> id to target(context, id) },
        )
    }

    fun target(context: Context, id: String): String =
        prefs(context).getString("map.$id", id)?.takeIf { t -> targets.any { it.first == t } } ?: id

    fun setTarget(context: Context, id: String, target: String) {
        prefs(context).edit().putString("map.$id", target).apply()
    }

    fun resetMapping(context: Context) {
        val p = prefs(context)
        p.edit().apply { p.all.keys.filter { it.startsWith("map.") }.forEach { remove(it) } }.apply()
    }

    fun setTint(context: Context, tint: Int) {
        prefs(context).edit().putInt("tint", tint).apply()
    }

    fun setOpacity(context: Context, percent: Int) {
        prefs(context).edit().putInt("opacity", percent).apply()
    }

    fun setSize(context: Context, percent: Int) {
        prefs(context).edit().putInt("size", percent).apply()
    }

    fun setStickClick(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("stickClick", on).apply()
    }

    fun setAdaptiveSticks(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("adaptiveSticks", on).apply()
    }

    fun rumbleEnabled(context: Context): Boolean = prefs(context).getBoolean("rumble", true)

    fun setRumble(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("rumble", on).apply()
    }

    fun setSteamButton(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("steamButton", on).apply()
    }

    fun setQamButton(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("qamButton", on).apply()
    }

    fun setKeyboardButton(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("keyboardButton", on).apply()
    }

    fun setSelectSteam(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("selectSteam", on).apply()
    }

    fun joyConVr(context: Context): Boolean = prefs(context).getBoolean("joyConVr", true)

    fun setJoyConVr(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("joyConVr", on).apply()
    }

    fun setSnap(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("snap", on).apply()
    }

    fun layout(context: Context, width: Int, height: Int): Map<String, Pair<Float, Float>> {
        val raw = prefs(context).getString("layout.${width}x$height", null) ?: return emptyMap()
        return raw.split(';').mapNotNull { entry ->
            val (group, pos) = entry.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            val (x, y) = pos.split(',').mapNotNull { it.toFloatOrNull() }.takeIf { it.size == 2 } ?: return@mapNotNull null
            group to (x to y)
        }.toMap()
    }

    fun setLayout(context: Context, width: Int, height: Int, positions: Map<String, Pair<Float, Float>>) {
        val raw = positions.entries.joinToString(";") { (group, pos) -> "$group:${pos.first},${pos.second}" }
        prefs(context).edit().putString("layout.${width}x$height", raw).apply()
    }

    fun resetLayout(context: Context, width: Int, height: Int) {
        prefs(context).edit().remove("layout.${width}x$height").apply()
    }

    fun screenKeysShown(context: Context): Boolean = prefs(context).getBoolean("screenKeysShown", true)

    fun setScreenKeysShown(context: Context, shown: Boolean) {
        prefs(context).edit().putBoolean("screenKeysShown", shown).apply()
    }

    fun screenKeys(context: Context): List<ScreenKey> = decodeScreenKeys(prefs(context).getString("screenKeys", null).orEmpty())

    fun setScreenKeys(context: Context, keys: List<ScreenKey>) {
        prefs(context).edit().putString("screenKeys", encodeScreenKeys(keys)).apply()
    }

    fun encodeScreenKeys(keys: List<ScreenKey>): String = keys.joinToString(";") {
        "${it.codes.joinToString("+")}:${it.x},${it.y},${it.size}:${escape(it.glyph)}:${escape(it.text)}:${escape(it.image)}:${it.opacity}:${it.shape}:${if (it.toggle) 1 else 0}:${if (it.labels) 1 else 0}"
    }

    fun decodeScreenKeys(raw: String): List<ScreenKey> = raw.split(';').mapNotNull { entry ->
        val parts = entry.split(':')
        if (parts.size < 2) return@mapNotNull null
        val keys = parts[0].split('+').mapNotNull { it.toIntOrNull() }.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val nums = parts[1].split(',').mapNotNull { it.toFloatOrNull() }.takeIf { it.size == 3 } ?: return@mapNotNull null
        ScreenKey(
            keys, nums[0], nums[1], nums[2].toInt(),
            parts.getOrNull(2)?.let(::unescape).orEmpty(),
            parts.getOrNull(3)?.let(::unescape).orEmpty(),
            parts.getOrNull(4)?.let(::unescape).orEmpty(),
            parts.getOrNull(5)?.toFloatOrNull()?.coerceIn(0.2f, 1f) ?: 1f,
            parts.getOrNull(6)?.toIntOrNull()?.coerceIn(SCREEN_KEY_PILL, SCREEN_KEY_STICK) ?: SCREEN_KEY_PILL,
            parts.getOrNull(7) == "1",
            parts.getOrNull(8) != "0",
        )
    }

    fun screenLayouts(context: Context): Map<String, String> =
        prefs(context).all.mapNotNull { (key, value) ->
            if (!key.startsWith("screenLayout.") || value !is String) null else key.removePrefix("screenLayout.") to value
        }.toMap()

    fun saveScreenLayout(context: Context, name: String, raw: String) {
        prefs(context).edit().putString("screenLayout.$name", raw).apply()
    }

    fun deleteScreenLayout(context: Context, name: String) {
        prefs(context).edit().remove("screenLayout.$name").apply()
    }

    private fun escape(value: String) = value.replace("%", "%25").replace(";", "%3B").replace(":", "%3A").replace(",", "%2C")

    private fun unescape(value: String) = value.replace("%2C", ",").replace("%3A", ":").replace("%3B", ";").replace("%25", "%")

    fun resetAll(context: Context) {
        prefs(context).edit().clear().putBoolean("rumble", true).apply()
    }

    fun resetAllLayouts(context: Context) {
        val p = prefs(context)
        p.edit().apply { p.all.keys.filter { it.startsWith("layout.") }.forEach { remove(it) } }.apply()
    }
}
