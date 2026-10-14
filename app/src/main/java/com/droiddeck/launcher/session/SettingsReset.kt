package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.core.GameEnvironment
import com.droiddeck.launcher.input.ControllerPrefs
import com.droiddeck.launcher.runtime.LinuxRuntime

/**
 * Setup's "Reset all settings": every choice goes back to the default its getter falls back to.
 *
 * Only settings are removed. The "session" file also holds the library (added games' names, exes,
 * appids and art picks), migration records, the drivers Auto installed, the storage and ROMs
 * folders and the one-time permission prompts; those stay, as do store sign-ins, imported drivers
 * and Lossless Scaling. Game profiles (each game's and the Default profile's environment
 * overrides) are cleared only when asked for.
 */
object SettingsReset {
    private const val TAG = "SettingsReset"

    internal val SESSION_KEYS = setOf(
        "orientation", "launcherFullscreen", "animations", "theme", "runSteamAtStartup", "addedGamesArt",
        "gameStoresEnabled", "gameStoresSpeedTier", "gameStoresParallel", "storesOpenTab", "storesShowMature",
        "hud", "hudDeck", "backActionsInverted", "touch", "osc", "shape", "steamController",
        "gamepadLeft", "gamepadRight", "pipAutoEnter", "unfoldedControls", "stretch16x9",
        "gpuDriverMode", "androidDriver", "linuxDriver", "linuxDriver.steam", "linuxDriver.desktop",
        "tuSysmem", "glThread", "noGlError", "zinkLazy", "mangoapp", "gpuClockPin", "gamescopeRealtime",
        "directAudio", "mic", "wifiDiscovery", "clientCpusOverride", "clientCpus", "gameCpus",
        "noXalia", "fastSync", "fsyncFirst", "syncFallback", "prootNoSeccomp", "prootFastPath",
        "guestHostname", "fexPreset", "forceSsbs", "logs", "storageDiagnostics",
        "upscaler", "upscaleSharpness", "screenEffects", "textureAnisotropy", "textureLodBias",
        "steamDownloadsInBackground", "steam_offline",
        // Cleared so the Quest's one-time compatibility defaults are written again.
        "questDefaultsV1",
    )

    /** Per-mode settings, stored as "<prefix><mode>". */
    internal val SESSION_PREFIXES = listOf(
        "resolutionCap.", "displayResolution.", "customRes.", "hdr.", "fpsLimit.", "suspendPolicy.",
    )

    private val STEAM_TOUCH_KEYS = listOf("feedback", "autoFade", "gyro", "shake")

    internal fun isSessionSetting(key: String) = key in SESSION_KEYS || SESSION_PREFIXES.any { key.startsWith(it) }

    /**
     * Resets the settings, then per-game profiles when [gameProfiles] is set ([gameScopes] are the
     * library's profile keys; scopes with saved overrides are found on their own). Blocking: call
     * off the main thread. Returns false if a part could not be reset; the rest is still done.
     * The language is not reset here: AppLanguage.setChosen changes the process locale, so the
     * caller does that on the main thread.
     */
    fun resetAll(context: Context, gameProfiles: Boolean, gameScopes: Collection<String> = emptyList()): Boolean {
        var ok = true
        val session = context.getSharedPreferences("session", Context.MODE_PRIVATE)
        session.edit().apply { session.all.keys.filter { isSessionSetting(it) }.forEach { remove(it) } }.commit()
        ControllerPrefs.resetAll(context)
        context.getSharedPreferences("app-ui", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("frame_gen", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("steam_touch", Context.MODE_PRIVATE).edit()
            .apply { STEAM_TOUCH_KEYS.forEach { remove(it) } }.commit()
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
