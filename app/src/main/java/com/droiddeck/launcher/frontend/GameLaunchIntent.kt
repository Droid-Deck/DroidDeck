package com.droiddeck.launcher.frontend

import android.content.Context
import android.content.Intent

object GameLaunchIntent {
    const val ACTION = "com.droiddeck.launcher.LAUNCH_GAME"
    const val EXTRA_APP_ID = "app_id"
    const val EXTRA_GAME_SOURCE = "game_source"
    private const val GAMENATIVE_ACTION = "app.gamenative.LAUNCH_GAME"

    fun accepts(action: String?): Boolean = action == Intent.ACTION_VIEW || action == ACTION || action == GAMENATIVE_ACTION

    fun read(context: Context, intent: Intent): String? = when (intent.action) {
        Intent.ACTION_VIEW -> GameFiles.readIntent(context, intent)
        ACTION, GAMENATIVE_ACTION -> readId(intent)
        else -> null
    }

    @Suppress("DEPRECATION")
    internal fun readId(intent: Intent): String? = runCatching {
        if (intent.action != ACTION && intent.action != GAMENATIVE_ACTION) return null
        val source = intent.extras?.get(EXTRA_GAME_SOURCE)
        if (source != null && (source !is String || !source.equals("STEAM", ignoreCase = true))) return null
        val id = when (val value = intent.extras?.get(EXTRA_APP_ID)) {
            is Int -> value.takeIf { it > 0 }?.toString()
            is String -> value.takeIf(GameLaunchLink::validId)
            else -> null
        }
        id
    }.getOrNull()
}
