package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.core.DownloadSource
import com.droiddeck.launcher.core.GameEnvironment
import com.droiddeck.launcher.input.ControllerPrefs
import com.droiddeck.launcher.runtime.LinuxRuntime

object SettingsReset {
    private const val TAG = "SettingsReset"

    internal val SESSION_KEYS = setOf(
        "orientation", "launcherFullscreen", "animations", "theme", "runSteamAtStartup", "addedGamesArt",
        "gameStoresEnabled", "gameStoresSpeedTier", "gameStoresParallel", "storesOpenTab", "storesShowMature",
        "storesInstallLanguage",
        "hud", "hudDeck", "hudFps", "hudBattery", "hudGraph", "hudColor", "hudOpacity", "fusionHud", "fusionHudPos", "backActionsInverted", "backDoubleTapMs", "touch", "osc", "shape", "steamController",
        "gamepadLeft", "gamepadRight", "pipAutoEnter", "unfoldedControls", "unfoldedControlsWithController",
        "stretch16x9", "picturePosition",
        "gpuDriverMode", "androidDriver", "linuxDriver", "linuxDriver.steam", "linuxDriver.desktop",
        "tuSysmem", "glThread", "noGlError", "zinkLazy", "mangoapp", "gpuClockPin", "gamescopeRealtime", "ramCapMb", "vramCapMb", "zramClient",
        "directAudio", "mic", "wifiDiscovery", "clientCpusOverride", "clientCpus", "gameCpus",
        "noXalia", "fastSync", "fsyncFirst", "syncFallback", "prootNoSeccomp", "prootFastPath", "cursorComposite",
        "guestHostname", "fexPreset", "forceSsbs", "logs", "gameDebugLogsEnabled", "storageDiagnostics",
        "upscaler", "upscaleSharpness", "screenEffects", "textureAnisotropy", "textureLodBias",
        "steamDownloadsInBackground", "steam_offline", "bootMovie", "bootMovieSound", "bootMovieVolume", "bootMovieSeconds",
        "bootMovieApplyNext", "bootMovieSteamSeen",
        "questDefaultsV1",
    )

    internal val SESSION_PREFIXES = listOf(
        "resolutionCap.", "displayResolution.", "customRes.", "hdr.", "fpsLimit.", "performanceMode.", "suspendPolicy.",
    )

    private val STEAM_TOUCH_KEYS = listOf("feedback", "autoFade", "gyro", "shake")

    internal fun isSessionSetting(key: String) = key in SESSION_KEYS || SESSION_PREFIXES.any { key.startsWith(it) }

    fun resetAll(context: Context, gameProfiles: Boolean, gameScopes: Collection<String> = emptyList()): Boolean {
        if (!SessionPrefs.setGameDebugLogsEnabled(context, false)) return false
        var ok = true
        val session = context.getSharedPreferences("session", Context.MODE_PRIVATE)
        session.edit().apply { session.all.keys.filter { isSessionSetting(it) }.forEach { remove(it) } }.commit()
        ControllerPrefs.resetAll(context)
        context.getSharedPreferences("app-ui", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("frame_gen", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("steam_touch", Context.MODE_PRIVATE).edit()
            .apply { STEAM_TOUCH_KEYS.forEach { remove(it) } }.commit()
        DownloadSource.reset(context)
        com.droiddeck.launcher.agent.AgentAccess.setCommandsEnabled(context, false)

        if (gameProfiles) {
            val scopes = (gameScopes + runCatching { GameEnvironmentStore.read(context).games.keys }.getOrDefault(emptySet()) +
                SessionPrefs.gameTextureFiltering(context).keys)
                .filter { it.isNotEmpty() && GameEnvironment.validScope(it) }.toSet()
            scopes.forEach { scope ->
                runCatching { GameProfileManager.reset(context, scope) }
                    .onFailure { ok = false; Log.w(TAG, "Could not reset the profile of $scope", it) }
            }
            session.edit().remove("gameTextureFiltering").commit()
            runCatching { GameEnvironmentStore.save(context, GameEnvironmentStore.read(context).copy(shared = emptyMap())) }
                .onFailure { ok = false; Log.w(TAG, "Could not clear the Default profile's environment", it) }
        }

        QuestExperimental.initialize(context)
        runCatching { OfflineMode.apply(context, LinuxRuntime.rootDir(context)) }
            .onFailure { ok = false; Log.w(TAG, "Could not turn offline mode off", it) }
        runCatching { GameEnvironmentStore.publish(context) }
            .onFailure { ok = false; Log.w(TAG, "Could not update game environment", it) }
        Log.i(TAG, "settings reset (game profiles: $gameProfiles, ok: $ok)")
        return ok
    }
}
