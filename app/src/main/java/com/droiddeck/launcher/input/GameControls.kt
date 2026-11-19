package com.droiddeck.launcher.input

import android.content.Context
import com.droiddeck.launcher.session.SessionPrefs

/** Which on-screen layout each game uses. Games without a choice use the default profile's main-screen layout. */
object GameControls {
    const val STEAM = "steam"

    private fun prefs(context: Context) = context.getSharedPreferences("game-controls", Context.MODE_PRIVATE)

    /** The main-screen bindings preset saved for [game], whose layout is the one drawn. */
    fun bindings(context: Context, game: Long?): String? = game?.let { prefs(context).getString("$it.bindings", null) }

    fun steamLayout(context: Context, game: Long?): Boolean {
        val p = prefs(context)
        return if (game != null && p.contains("$game.steam")) p.getBoolean("$game.steam", false) else SessionPrefs.steamTouchLayout(context)
    }

    fun choose(context: Context, game: Long, bindings: String?, steam: Boolean, name: String? = null) {
        prefs(context).edit().apply { if (bindings == null) remove("$game.bindings") else putString("$game.bindings", bindings) }
            .apply { if (!name.isNullOrBlank()) putString("$game.name", name) }
            .putBoolean("$game.steam", steam).apply()
    }

    /** Games with their own choice, in the order of their ids. */
    fun games(context: Context): List<Long> = prefs(context).all.keys
        .filter { it.endsWith(".steam") || it.endsWith(".bindings") }
        .mapNotNull { it.substringBeforeLast('.').toLongOrNull() }.distinct().sorted()

    fun name(context: Context, game: Long): String? = prefs(context).getString("$game.name", null)

    fun clear(context: Context, game: Long) {
        prefs(context).edit().remove("$game.bindings").remove("$game.steam").apply()
    }
}
