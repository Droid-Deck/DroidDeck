package com.droiddeck.launcher.frontend

import android.content.Context
import android.content.Intent

object GameLaunchIntent {
    const val ACTION = "com.droiddeck.launcher.LAUNCH_GAME"
    const val EXTRA_APP_ID = "app_id"
    const val EXTRA_GAME_SOURCE = "game_source"
    private const val GAMENATIVE_ACTION = "app.gamenative.LAUNCH_GAME"

    fun accepts(action: String?): Boolean = action == Intent.ACTION_VIEW || action == ACTION || action == GAMENATIVE_ACTION

    fun read(context: Context, intent: Intent): GameRequest? = when (intent.action) {
        Intent.ACTION_VIEW -> GameFiles.readIntent(context, intent)
        ACTION, GAMENATIVE_ACTION -> readExtras(intent)
        else -> null
    }

    @Suppress("DEPRECATION")
    internal fun readExtras(intent: Intent): GameRequest? = runCatching {
        if (intent.action != ACTION && intent.action != GAMENATIVE_ACTION) return null
        val source = when (val value = intent.extras?.get(EXTRA_GAME_SOURCE)) {
            null -> Library.SOURCE_STEAM
            is String -> GameRequest.sourceNamed(value) ?: return null
            else -> return null
        }
        val id = when (val value = intent.extras?.get(EXTRA_APP_ID)) {
            is Int -> value.takeIf { it > 0 }?.toString()
            is Long -> value.takeIf { it != 0L }?.let(java.lang.Long::toUnsignedString)
            is String -> value.takeIf(GameLaunchLink::validId)
            else -> null
        } ?: return null
        GameRequest(id, source)
    }.getOrNull()
}
