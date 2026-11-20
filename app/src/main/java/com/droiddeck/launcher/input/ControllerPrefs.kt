package com.droiddeck.launcher.input

import android.content.Context
import com.droiddeck.launcher.R

object ControllerPrefs {
    const val STEAM_BLUE = 0xFF1A9FFF.toInt()
    const val OFF = "off"

    val deadZones = listOf(0, 5, 8, 12, 15, 20, 25)
    const val DEFAULT_DEAD_ZONE_PERCENT = 12

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

    /** Settings still read: the rest only seed the one-time library and layout migrations. */
    class Settings(
        val tint: Int,
        val opacity: Int,
        val stickClick: Boolean,
        val rumble: Boolean,
        val selectSteam: Boolean,
        val joyConVr: Boolean,
        val snap: Boolean,
        val mapping: Map<String, String>,
        val deadZone: Int,
    )

    internal fun prefs(context: Context) = context.getSharedPreferences("controller", Context.MODE_PRIVATE)

    fun read(context: Context): Settings {
        val p = prefs(context)
        return Settings(
            tint = p.getInt("tint", STEAM_BLUE),
            opacity = p.getInt("opacity", 100).coerceIn(1, 100),
            stickClick = p.getBoolean("stickClick", true),
            rumble = rumbleEnabled(context),
            selectSteam = p.getBoolean("selectSteam", false),
            joyConVr = joyConVr(context),
            snap = snap(context),
            mapping = mappable.associate { (id, _) -> id to (p.getString("map.$id", id)?.takeIf { t -> targets.any { it.first == t } } ?: id) },
            deadZone = deadZone(context),
        )
    }

    fun rumbleEnabled(context: Context): Boolean = prefs(context).getBoolean("rumble", true)

    fun deadZone(context: Context): Int =
        prefs(context).getInt("deadZone", DEFAULT_DEAD_ZONE_PERCENT).takeIf { it in deadZones } ?: DEFAULT_DEAD_ZONE_PERCENT

    fun setDeadZone(context: Context, percent: Int) {
        prefs(context).edit().putInt("deadZone", percent).apply()
    }

    fun setRumble(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("rumble", on).apply()
    }

    fun joyConVr(context: Context): Boolean = prefs(context).getBoolean("joyConVr", true)

    fun setJoyConVr(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("joyConVr", on).apply()
    }

    fun snap(context: Context): Boolean = prefs(context).getBoolean("snap", true)

    fun setSnap(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("snap", on).apply()
    }

    fun resetAll(context: Context) {
        prefs(context).edit().clear().putBoolean("rumble", true).apply()
    }
}
