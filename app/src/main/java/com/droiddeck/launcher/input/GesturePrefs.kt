package com.droiddeck.launcher.input

import android.content.Context

/** Global defaults with optional overrides for the currently focused game. */
object GesturePrefs {
    private fun prefs(context: Context) = context.getSharedPreferences("touch_gestures", Context.MODE_PRIVATE)

    fun hasOverride(context: Context, game: String): Boolean = prefs(context).contains("game.$game")

    fun read(context: Context, game: String? = null): TouchGestureConfig {
        val p = prefs(context)
        val json = game?.let { p.getString("game.$it", null) } ?: p.getString("default", null)
        return if (json == null) TouchGestureConfig() else TouchGestureConfig.fromJson(json)
    }

    fun save(context: Context, game: String?, config: TouchGestureConfig) {
        prefs(context).edit().putString(game?.let { "game.$it" } ?: "default", config.toJson()).apply()
    }

    fun clear(context: Context, game: String?) {
        prefs(context).edit().remove(game?.let { "game.$it" } ?: "default").apply()
    }
}
