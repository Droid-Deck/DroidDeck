package com.droiddeck.launcher.sync

import android.content.Context
import java.security.SecureRandom

object SyncPrefs {
    const val RUN_WHILE_OPEN = "open"
    const val RUN_BACKGROUND = "background"

    private fun prefs(context: Context) = context.getSharedPreferences("syncthing", Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean("enabled", false)
    fun setEnabled(context: Context, on: Boolean) { prefs(context).edit().putBoolean("enabled", on).apply() }

    fun runMode(context: Context): String =
        prefs(context).getString("run", RUN_WHILE_OPEN).takeIf { it == RUN_BACKGROUND } ?: RUN_WHILE_OPEN
    fun setRunMode(context: Context, mode: String) { prefs(context).edit().putString("run", mode).apply() }

    fun wifiOnly(context: Context): Boolean = prefs(context).getBoolean("wifi-only", false)
    fun setWifiOnly(context: Context, on: Boolean) { prefs(context).edit().putBoolean("wifi-only", on).apply() }

    fun pauseInGames(context: Context): Boolean = prefs(context).getBoolean("pause-in-games", false)
    fun setPauseInGames(context: Context, on: Boolean) { prefs(context).edit().putBoolean("pause-in-games", on).apply() }

    fun userPaused(context: Context): Boolean = prefs(context).getBoolean("paused", false)
    fun setUserPaused(context: Context, on: Boolean) { prefs(context).edit().putBoolean("paused", on).apply() }

    fun pauseApplied(context: Context): Boolean = prefs(context).getBoolean("pause-applied", false)
    fun setPauseApplied(context: Context, on: Boolean) { prefs(context).edit().putBoolean("pause-applied", on).apply() }

    fun guiPassword(context: Context): String = prefs(context).getString("gui-password", null) ?: randomToken().also {
        prefs(context).edit().putString("gui-password", it).apply()
    }

    fun clearIdentity(context: Context) { prefs(context).edit().remove("gui-password").apply() }

    internal fun randomToken(length: Int = 32): String {
        val alphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        return String(CharArray(length) { alphabet[random.nextInt(alphabet.length)] })
    }
}
