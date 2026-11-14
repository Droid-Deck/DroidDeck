package com.droiddeck.launcher.frontend

import android.content.Context
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionState

object SteamInputOverride {
    private const val CONFIG_KEY = "UseSteamControllerConfig"
    private const val DEFAULT_VALUE = "1"

    enum class Mode(val value: String?) {
        DEFAULT(null), ENABLED("2"), DISABLED("0");

        companion object {
            fun from(value: String): Mode? = when (value) {
                DEFAULT_VALUE -> DEFAULT
                "2" -> ENABLED
                "0" -> DISABLED
                else -> null
            }
        }
    }

    enum class Saved { YES, NO, RUNNING }

    fun current(context: Context, appId: Int): Mode? =
        SteamLaunchOptions.steamAppSetting(
            SessionPrefs.activeSteamRoot(context), appId, CONFIG_KEY, DEFAULT_VALUE,
        )?.let(Mode::from)

    fun set(context: Context, appId: Int, mode: Mode, shown: Mode): Saved {
        if (SessionState.running) return Saved.RUNNING
        val saved = SteamLaunchOptions.writeSteamAppSetting(
            SessionPrefs.activeSteamRoot(context), appId, CONFIG_KEY,
            shown.value ?: DEFAULT_VALUE, mode.value, DEFAULT_VALUE,
        )
        return if (saved) Saved.YES else Saved.NO
    }
}
