package com.droiddeck.launcher.session

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.annotation.StringRes
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.CpuAllocationDefaults
import com.droiddeck.launcher.core.GameEnvironment
import com.droiddeck.launcher.core.TextureFiltering
import com.droiddeck.launcher.core.ZramSupport
import com.droiddeck.launcher.gpu.Cardboard
import com.droiddeck.launcher.gpu.ScreenEffects
import org.json.JSONObject

object SessionPrefs {
    data class GameTextureFiltering(val anisotropy: Int? = null, val lodBias: String? = null)

    const val SUSPEND_AUTO = "auto"
    const val SUSPEND_NATIVE = "native"
    const val SUSPEND_MANUAL = "manual"
    const val SUSPEND_NEVER = "never"
    private const val LEGACY_SUSPEND_DOWNLOADS = "downloads"
    private const val STEAM_DOWNLOADS_IN_BACKGROUND = "steamDownloadsInBackground"

    const val CONTROLLER_DECK = "deck"
    const val CONTROLLER_XBOX360 = "xbox360"
    const val CONTROLLER_STEAM = "steamcontroller"
    const val CONTROLLER_DS4 = "dualshock4"
    const val CONTROLLER_DUALSENSE = "dualsense"
    const val OSC_AUTO = "auto"
    const val OSC_ALWAYS = "always"
    const val OSC_STEAM_QAM = "steam-qam"
    const val OSC_NEVER = "never"
    const val OSC_STEAM_TOUCH = "steam-touch"

    val BACK_MENU_THEN_QAM = R.string.back_menu_then_qam
    val BACK_QAM_THEN_MENU = R.string.back_qam_then_menu

    @StringRes
    fun backActionsOrder(inverted: Boolean): Int =
        if (inverted) BACK_QAM_THEN_MENU else BACK_MENU_THEN_QAM

    private fun prefs(context: Context) = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    val orientationOptions = listOf(
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE to R.string.setup_orientation_auto,
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to R.string.setup_orientation_landscape,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE to R.string.setup_orientation_reverse,
    )

    fun orientation(context: Context): Int =
        prefs(context).getInt("orientation", ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
            .takeIf { value -> orientationOptions.any { it.first == value } }
            ?: ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

    fun setOrientation(context: Context, orientation: Int) {
        require(orientationOptions.any { it.first == orientation })
        prefs(context).edit().putInt("orientation", orientation).apply()
    }

    fun launcherFullscreen(context: Context): Boolean = prefs(context).getBoolean("launcherFullscreen", true)

    fun setLauncherFullscreen(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("launcherFullscreen", on).apply()
    }

    fun animationsEnabled(context: Context): Boolean = prefs(context).getBoolean("animations", true)

    fun setAnimationsEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("animations", on).apply()
    }

    fun gameStoresEnabled(context: Context): Boolean = prefs(context).getBoolean("gameStoresEnabled", true)

    fun setGameStoresEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("gameStoresEnabled", on).apply()
    }

    fun gameStoresSpeedTier(context: Context): String = prefs(context).getString("gameStoresSpeedTier", "fast") ?: "fast"

    fun setGameStoresSpeedTier(context: Context, tier: String) {
        prefs(context).edit().putString("gameStoresSpeedTier", tier).apply()
    }

    fun storesOpenTab(context: Context): String =
        prefs(context).getString("storesOpenTab", STORES_OPEN_LIBRARY)?.takeIf { it == STORES_OPEN_STORE } ?: STORES_OPEN_LIBRARY

    fun setStoresOpenTab(context: Context, tab: String) {
        prefs(context).edit().putString("storesOpenTab", if (tab == STORES_OPEN_STORE) STORES_OPEN_STORE else STORES_OPEN_LIBRARY).apply()
    }

    fun storesShowMature(context: Context): Boolean = prefs(context).getBoolean("storesShowMature", false)

    fun setStoresShowMature(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean("storesShowMature", show).apply()
    }

    const val STORES_OPEN_LIBRARY = "library"
    const val STORES_OPEN_STORE = "store"

    fun storesInstallTarget(context: Context): String = prefs(context).getString("storesInstallTarget", "") ?: ""

    fun setStoresInstallTarget(context: Context, path: String) {
        prefs(context).edit().putString("storesInstallTarget", path).apply()
    }

    fun gameStoresParallel(context: Context): Int = prefs(context).getInt("gameStoresParallel", 1).coerceIn(1, 3)

    fun setGameStoresParallel(context: Context, count: Int) {
        prefs(context).edit().putInt("gameStoresParallel", count.coerceIn(1, 3)).apply()
    }

    fun hudEnabled(context: Context): Boolean = prefs(context).getBoolean(hudKey(context), !mangoappSession(context))

    fun setHudEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(hudKey(context), on).apply()
    }

    private fun hudKey(context: Context) = if (mangoappSession(context)) "hudDeck" else "hud"

    private fun mangoappSession(context: Context) =
        SessionState.mode == SessionService.MODE_STEAM && mangoapp(context)

    fun backActionsInverted(context: Context): Boolean = prefs(context).getBoolean("backActionsInverted", false)

    fun setBackActionsInverted(context: Context, inverted: Boolean) {
        prefs(context).edit().putBoolean("backActionsInverted", inverted).apply()
    }

    const val TOUCH_AUTO = "auto"
    const val TOUCH_PAD = "touchpad"
    const val TOUCH_DIRECT = "direct"
    const val TOUCH_OFF = "off"

    fun touchMode(context: Context): String = prefs(context).getString("touch", TOUCH_AUTO) ?: TOUCH_AUTO

    fun setTouchMode(context: Context, mode: String) {
        prefs(context).edit().putString("touch", mode).apply()
    }

    const val SHAPE_AUTO = "auto"
    const val SHAPE_WIDE = "16:9"
    const val SHAPE_EXACT = "exact"

    private fun shapeMode(context: Context): String =
        prefs(context).getString("shape", null)
            ?: if (context.packageManager.hasSystemFeature("android.hardware.sensor.hinge_angle")) SHAPE_WIDE else SHAPE_AUTO

    fun oscMode(context: Context): String = prefs(context).getString("osc", OSC_AUTO) ?: OSC_AUTO

    fun setOscMode(context: Context, mode: String) {
        prefs(context).edit().putString("osc", mode).apply()
    }

    fun linuxDriver(context: Context): String =
        prefs(context).getString("linuxDriver", null)
            ?: prefs(context).getString("linuxDriver.steam", null)
            ?: prefs(context).getString("linuxDriver.desktop", "") ?: ""

    fun setLinuxDriver(context: Context, id: String) {
        prefs(context).edit().putString("linuxDriver", id).apply()
    }

    const val GPU_DRIVERS_AUTO = "auto"
    const val GPU_DRIVERS_MANUAL = "manual"

    fun gpuDriverMode(context: Context): String =
        prefs(context).getString("gpuDriverMode", GPU_DRIVERS_AUTO) ?: GPU_DRIVERS_AUTO

    fun setGpuDriverMode(context: Context, mode: String) {
        prefs(context).edit().putString("gpuDriverMode", mode).apply()
    }

    fun settleGpuDriverMode(context: Context) {
        val p = prefs(context)
        if (p.contains("gpuDriverMode")) return
        val chosen = androidDriver(context).isNotEmpty() || linuxDriver(context).isNotEmpty()
        p.edit().putString("gpuDriverMode", if (chosen) GPU_DRIVERS_MANUAL else GPU_DRIVERS_AUTO).apply()
    }

    fun gpuAutoInstalled(context: Context): Set<String> =
        prefs(context).getStringSet("gpuAutoInstalled", emptySet()).orEmpty()

    fun setGpuAutoInstalled(context: Context, ids: Set<String>) {
        prefs(context).edit().putStringSet("gpuAutoInstalled", ids.toSet()).apply()
    }


    fun stretch16x9(context: Context): Boolean = prefs(context).getBoolean("stretch16x9", false)

    fun setStretch16x9(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("stretch16x9", on).apply()
    }


    fun directAudio(context: Context): Boolean = prefs(context).getBoolean("directAudio", true)

    fun setDirectAudio(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("directAudio", on).apply()
    }

    fun micEnabled(context: Context): Boolean = prefs(context).getBoolean("mic", false)

    fun micAsked(context: Context): Boolean = prefs(context).getBoolean("micAsked", false)

    fun setMicAsked(context: Context) {
        prefs(context).edit().putBoolean("micAsked", true).apply()
    }

    fun setMicEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("mic", on).apply()
    }

    fun wifiDiscoveryEnabled(context: Context): Boolean = prefs(context).getBoolean("wifiDiscovery", false)

    fun setWifiDiscoveryEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("wifiDiscovery", on).apply()
    }

    fun wifiDiscoveryAsked(context: Context): Boolean = prefs(context).getBoolean("wifiDiscoveryAsked", false)

    fun setWifiDiscoveryAsked(context: Context) {
        prefs(context).edit().putBoolean("wifiDiscoveryAsked", true).apply()
    }

    internal data class CoreAllocation(val clientOverride: Boolean, val clientCpus: String, val gameCpus: String)

    internal fun coreAllocation(context: Context, defaults: CpuAllocationDefaults.Allocation? = CpuAllocationDefaults.device): CoreAllocation {
        val saved = prefs(context)
        return CoreAllocation(
            saved.getBoolean("clientCpusOverride", defaults != null && !saved.contains("clientCpus")),
            saved.getString("clientCpus", defaults?.client.orEmpty()).orEmpty(),
            saved.getString("gameCpus", defaults?.game.orEmpty()).orEmpty(),
        )
    }

    fun clientCpusOverride(context: Context): Boolean = coreAllocation(context).clientOverride

    fun setClientCpusOverride(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("clientCpusOverride", on).apply()
    }

    fun clientCpus(context: Context): String = coreAllocation(context).clientCpus

    fun setClientCpus(context: Context, list: String) {
        prefs(context).edit().putString("clientCpus", list).apply()
    }

    fun gameCpus(context: Context): String = coreAllocation(context).gameCpus

    fun setGameCpus(context: Context, list: String) {
        prefs(context).edit().putString("gameCpus", list).apply()
    }

    fun noXalia(context: Context): Boolean = prefs(context).getBoolean("noXalia", true)

    fun setNoXalia(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("noXalia", on).apply()
    }

    fun fastSync(context: Context): Boolean = prefs(context).getBoolean("fastSync", false)

    fun setFastSync(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("fastSync", on).apply()
    }

    fun fsyncFirst(context: Context): Boolean = prefs(context).getBoolean("fsyncFirst", false)

    fun setFsyncFirst(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("fsyncFirst", on).apply()
    }

    const val SYNC_ESYNC = "esync"
    const val SYNC_NTSYNC = "ntsync"
    const val SYNC_FSYNC = "fsync"
    const val SYNC_WINESERVER = "wineserver"

    fun syncBackend(context: Context): String = syncBackendOf(fastSync(context), fsyncFirst(context), syncFallback(context))

    fun syncBackendOf(fastSync: Boolean, fsyncFirst: Boolean, syncFallback: Boolean): String = when {
        fastSync -> SYNC_NTSYNC
        fsyncFirst -> SYNC_FSYNC
        syncFallback -> SYNC_ESYNC
        else -> SYNC_WINESERVER
    }

    fun setSyncBackend(context: Context, backend: String) {
        require(backend == SYNC_ESYNC || backend == SYNC_NTSYNC || backend == SYNC_FSYNC || backend == SYNC_WINESERVER) { "unknown sync $backend" }
        prefs(context).edit()
            .putBoolean("fastSync", backend == SYNC_NTSYNC)
            .putBoolean("fsyncFirst", backend == SYNC_FSYNC)
            .putBoolean("syncFallback", backend != SYNC_WINESERVER)
            .apply()
    }

    fun syncFallback(context: Context): Boolean = prefs(context).getBoolean("syncFallback", true)

    fun setSyncFallback(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("syncFallback", on).apply()
    }

    fun gamescopeRealtime(context: Context): Boolean = prefs(context).getBoolean("gamescopeRealtime", false)

    fun setGamescopeRealtime(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("gamescopeRealtime", on).apply()
    }

    fun gpuClockPin(context: Context): Boolean = prefs(context).getBoolean("gpuClockPin", false)

    fun setGpuClockPin(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("gpuClockPin", on).apply()
    }

    fun prootNoSeccomp(context: Context): Boolean = prefs(context).getBoolean("prootNoSeccomp", false)

    fun setProotNoSeccomp(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("prootNoSeccomp", on).apply()
    }

    fun prootFastPath(context: Context): Boolean = prefs(context).getBoolean("prootFastPath", true)

    fun setProotFastPath(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("prootFastPath", on).apply()
    }

    const val DEFAULT_GUEST_HOSTNAME = "DroidDeck"

    @JvmStatic
    fun guestHostname(context: Context): String =
        validGuestHostname(prefs(context).getString("guestHostname", null)) ?: DEFAULT_GUEST_HOSTNAME

    fun setGuestHostname(context: Context, name: String) {
        val valid = validGuestHostname(name)
        prefs(context).edit().apply { if (valid == null) remove("guestHostname") else putString("guestHostname", valid) }.apply()
    }

    fun validGuestHostname(name: String?): String? {
        val trimmed = name?.trim() ?: return null
        return trimmed.takeIf { Regex("[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?").matches(it) }
    }

    fun tuSysmem(context: Context): Boolean = prefs(context).getBoolean("tuSysmem", true)

    fun setTuSysmem(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("tuSysmem", on).apply()
    }

    fun glThread(context: Context): Boolean = prefs(context).getBoolean("glThread", true)
    fun setGlThread(context: Context, on: Boolean) { prefs(context).edit().putBoolean("glThread", on).apply() }

    fun noGlError(context: Context): Boolean = prefs(context).getBoolean("noGlError", true)
    fun setNoGlError(context: Context, on: Boolean) { prefs(context).edit().putBoolean("noGlError", on).apply() }

    fun steamController(context: Context): String =
        prefs(context).getString("steamController", CONTROLLER_DECK) ?: CONTROLLER_DECK
    fun setSteamController(context: Context, id: String) { prefs(context).edit().putString("steamController", id).apply() }
    fun padType(id: String): Int = when (id) {
        CONTROLLER_STEAM -> com.droiddeck.launcher.input.FakeInputWriter.PAD_TYPE_STEAM
        CONTROLLER_XBOX360 -> com.droiddeck.launcher.input.FakeInputWriter.PAD_TYPE_XBOX
        CONTROLLER_DS4 -> com.droiddeck.launcher.input.FakeInputWriter.PAD_TYPE_DS4
        CONTROLLER_DUALSENSE -> com.droiddeck.launcher.input.FakeInputWriter.PAD_TYPE_DUALSENSE
        else -> com.droiddeck.launcher.input.FakeInputWriter.PAD_TYPE_DECK
    }

    fun mangoapp(context: Context): Boolean = prefs(context).getBoolean("mangoapp", true)
    fun setMangoapp(context: Context, on: Boolean) { prefs(context).edit().putBoolean("mangoapp", on).apply() }

    fun zinkLazy(context: Context): Boolean = prefs(context).getBoolean("zinkLazy", true)

    fun setZinkLazy(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("zinkLazy", on).apply()
    }

    @JvmStatic
    fun androidDriver(context: Context): String = prefs(context).getString("androidDriver", "") ?: ""

    @JvmStatic
    fun setAndroidDriver(context: Context, id: String) {
        prefs(context).edit().putString("androidDriver", id).apply()
    }


    fun romsDir(context: Context): String =
        prefs(context).getString("romsDir", "") ?: ""

    fun setRomsDir(context: Context, path: String) {
        prefs(context).edit().putString("romsDir", path).apply()
    }

    fun logsEnabled(context: Context): Boolean =
        prefs(context).getBoolean("logs", true)

    fun setLogsEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("logs", on).apply()
    }

    fun storageDiagnosticsEnabled(context: Context): Boolean =
        prefs(context).getBoolean("storageDiagnostics", false)

    fun setStorageDiagnosticsEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("storageDiagnostics", on).apply()
    }


    private fun resolutionCap(context: Context, mode: String): Int = prefs(context).getInt("resolutionCap.$mode", 720)

    fun resolutionChosen(context: Context, mode: String): Boolean =
        prefs(context).contains("displayResolution.$mode") ||
            prefs(context).contains("resolutionCap.$mode") || customResolution(context, mode) != null

    fun resolutionChoice(context: Context, mode: String, panel: Pair<Int, Int>): String {
        val saved = prefs(context)
        saved.getString("displayResolution.$mode", null)?.let { value ->
            if (value == SessionDisplay.MATCH_SCREEN || value == SessionDisplay.FOLLOW_SCREEN ||
                SessionDisplay.presetHeight(value) != null) return value
            parseResolution(value)?.let { return "${it.first}x${it.second}" }
        }
        if (!resolutionChosen(context, mode) && !saved.contains("shape")) return SessionDisplay.DEFAULT_RESOLUTION
        val legacy = SessionDisplay.resolve(panel, resolutionCap(context, mode), shapeMode(context), customResolution(context, mode))
        return if (legacy == SessionDisplay.screenSize(panel)) SessionDisplay.MATCH_SCREEN
        else "${legacy.first}x${legacy.second}"
    }

    fun setResolutionChoice(context: Context, mode: String, choice: String) {
        val value = if (choice == SessionDisplay.MATCH_SCREEN || choice == SessionDisplay.FOLLOW_SCREEN ||
                SessionDisplay.presetHeight(choice) != null) choice else {
            val size = requireNotNull(parseResolution(choice)) { "Invalid resolution" }
            "${size.first}x${size.second}"
        }
        prefs(context).edit().putString("displayResolution.$mode", value).apply()
    }

    fun fexPreset(context: Context): String = prefs(context).getString("fexPreset", DEFAULT_FEX_PRESET) ?: DEFAULT_FEX_PRESET

    private const val DEFAULT_FEX_PRESET = "PERFORMANCE_TSO"

    fun forceSsbs(context: Context): Boolean = prefs(context).getBoolean("forceSsbs", false)

    fun setForceSsbs(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("forceSsbs", on).apply()
        runCatching { GameEnvironmentStore.publish(context) }
            .onFailure { android.util.Log.e("GameEnvironment", "Could not update game environment", it) }
    }

    fun setFexPreset(context: Context, id: String) {
        prefs(context).edit().putString("fexPreset", id).apply()
        runCatching { GameEnvironmentStore.publish(context) }
            .onFailure { android.util.Log.e("GameEnvironment", "Could not update game environment", it) }
    }

    fun runSteamAtStartup(context: Context): Boolean = prefs(context).getBoolean("runSteamAtStartup", false)

    fun setRunSteamAtStartup(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("runSteamAtStartup", on).apply()
    }

    fun addedGamesDirs(context: Context): List<String> {
        val p = prefs(context)
        val list = p.getString("addedGamesDirs", null)
        if (list != null) return list.split('\n').filter { it.isNotEmpty() }
        val old = p.getString("addedGamesDir", "") ?: ""
        return if (old.isEmpty()) emptyList() else listOf(old)
    }

    fun setAddedGamesDirs(context: Context, dirs: List<String>) {
        prefs(context).edit().putString("addedGamesDirs", dirs.distinct().joinToString("\n")).remove("addedGamesDir").apply()
    }

    fun gameFolderBinds(context: Context): List<Pair<String, String>> =
        (prefs(context).getString("gameFolderBinds", "") ?: "").split('\n').mapNotNull { line ->
            line.split('\t').takeIf { it.size == 2 && it[0].isNotEmpty() && it[1].isNotEmpty() }?.let { it[0] to it[1] }
        }

    fun setGameFolderBinds(context: Context, binds: List<Pair<String, String>>) {
        prefs(context).edit().putString("gameFolderBinds", binds.distinctBy { it.first }.joinToString("\n") { "${it.first}\t${it.second}" }).apply()
    }

    fun gamesFoldersMigrated(context: Context): Boolean = prefs(context).getBoolean("gamesFoldersMigrated", false)

    fun setGamesFoldersMigrated(context: Context) {
        prefs(context).edit().putBoolean("gamesFoldersMigrated", true).apply()
    }

    fun removedAddedGames(context: Context): List<String> =
        (prefs(context).getString("removedAddedGames", "") ?: "").split('\n').filter { it.isNotEmpty() }

    fun setAddedGameRemoved(context: Context, folderPath: String, removed: Boolean) {
        synchronized(addedGameLock) {
            val now = removedAddedGames(context).filter { it != folderPath } + listOfNotNull(folderPath.takeIf { removed })
            prefs(context).edit().putString("removedAddedGames", now.joinToString("\n")).apply()
        }
    }

    fun addedGameName(context: Context, folderPath: String): String = prefs(context).getString("addedName:$folderPath", "") ?: ""

    fun setAddedGameName(context: Context, folderPath: String, name: String) {
        prefs(context).edit().putString("addedName:$folderPath", name.trim()).apply()
    }

    fun addedGameStartIn(context: Context, folderPath: String): String = prefs(context).getString("addedStartIn:$folderPath", "") ?: ""

    fun setAddedGameStartIn(context: Context, folderPath: String, guestDir: String) {
        prefs(context).edit().putString("addedStartIn:$folderPath", guestDir.trim()).apply()
    }

    fun addedGameLaunch(context: Context, folderPath: String): String = prefs(context).getString("addedLaunch:$folderPath", "") ?: ""

    fun setAddedGameLaunch(context: Context, folderPath: String, options: String) {
        prefs(context).edit().putString("addedLaunch:$folderPath", options.trim()).apply()
    }

    fun addedGameAdopted(context: Context, folderPath: String, field: String): String? =
        prefs(context).getString("addedAdopted:$field:$folderPath", null)

    fun setAddedGameAdopted(context: Context, folderPath: String, field: String, value: String) {
        prefs(context).edit().putString("addedAdopted:$field:$folderPath", value).apply()
    }

    fun addedGameArtSource(context: Context, folderPath: String, slot: String): String =
        prefs(context).getString("addedArt:$slot:$folderPath", "") ?: ""

    fun setAddedGameArtSource(context: Context, folderPath: String, slot: String, source: String) {
        prefs(context).edit().putString("addedArt:$slot:$folderPath", source).apply()
    }

    fun addedGamesArt(context: Context): Boolean = prefs(context).getBoolean("addedGamesArt", true)

    fun setAddedGamesArt(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("addedGamesArt", on).apply()
    }

    fun addedGameExe(context: Context, folderPath: String): String = prefs(context).getString("addedExe:$folderPath", "") ?: ""

    private val addedGameLock = Any()

    fun setAddedGameExe(context: Context, folderPath: String, path: String) {
        synchronized(addedGameLock) { prefs(context).edit().putString("addedExe:$folderPath", path).apply() }
    }

    fun addedGameExeSeen(context: Context, folderPath: String): Int = prefs(context).getInt("addedExeSeen:$folderPath", 0)

    fun adoptAddedGameExe(context: Context, folderPath: String, expected: String, path: String, seen: Int): Boolean =
        synchronized(addedGameLock) {
            val unchanged = addedGameExe(context, folderPath) == expected
            if (unchanged) prefs(context).edit().putString("addedExe:$folderPath", path).putInt("addedExeSeen:$folderPath", seen).apply()
            unchanged
        }

    fun addedGameStoredAppId(context: Context, folderPath: String): Long? =
        prefs(context).getLong("addedAppId:$folderPath", 0L).takeIf { it != 0L }

    fun addedGameAppId(context: Context, folderPath: String, first: Long): Long = synchronized(addedGameLock) {
        val p = prefs(context)
        p.getLong("addedAppId:$folderPath", 0L).takeIf { it != 0L }
            ?: first.also { p.edit().putLong("addedAppId:$folderPath", it).apply() }
    }

    fun theme(context: Context): String = prefs(context).getString("theme", "graphite") ?: "graphite"

    fun setTheme(context: Context, id: String) {
        prefs(context).edit().putString("theme", id).apply()
    }

    private fun customResolution(context: Context, mode: String): Pair<Int, Int>? =
        parseResolution(prefs(context).getString("customRes.$mode", null))

    fun parseResolution(text: String?): Pair<Int, Int>? {
        val parts = text?.trim()?.split('x', 'X', '×', '*')?.map { it.trim() } ?: return null
        if (parts.size != 2) return null
        val w = parts[0].toIntOrNull() ?: return null
        val h = parts[1].toIntOrNull() ?: return null
        if (w !in 320..3840 || h !in 240..2160) return null
        return Pair(w and 1.inv(), h and 1.inv())
    }

    fun hdr(context: Context, mode: String): Boolean = prefs(context).getBoolean("hdr.$mode", false)

    fun setHdr(context: Context, mode: String, on: Boolean) {
        prefs(context).edit().putBoolean("hdr.$mode", on).apply()
    }

    fun fpsLimit(context: Context, mode: String): Int = prefs(context).getInt("fpsLimit.$mode", 0)

    fun setFpsLimit(context: Context, mode: String, fps: Int) {
        prefs(context).edit().putInt("fpsLimit.$mode", fps.coerceAtLeast(0)).apply()
    }

    val fpsLimitChoices = listOf(0 to "Off", 30 to "30", 40 to "40", 45 to "45", 60 to "60", 90 to "90", 120 to "120")

    fun fpsLimitChoices(context: Context): List<Pair<Int, String>> =
        fpsLimitChoices.map { (fps, label) -> fps to if (fps == 0) context.getString(R.string.frame_gen_off) else label }

    val upscalerChoices = listOf(
        0 to "Linear", 2 to "Nearest", 4 to "AMD FSR 1", 3 to "Snapdragon GSR",
        8 to "Snapdragon GSR (quality)", 7 to "NVIDIA NIS", 6 to "Sharpen only",
    )

    private val upscalerLabels = mapOf(
        0 to R.string.sprefs_upscaler_linear, 2 to R.string.sprefs_upscaler_nearest,
        8 to R.string.sprefs_upscaler_gsr_quality, 6 to R.string.sprefs_upscaler_sharpen,
    )

    fun upscalerChoices(context: Context): List<Pair<Int, String>> =
        upscalerChoices.map { (mode, label) -> mode to (upscalerLabels[mode]?.let(context::getString) ?: label) }

    fun canonicalUpscaler(mode: Int): Int = when (mode) {
        1 -> 0
        5 -> 4
        else -> mode.takeIf { m -> upscalerChoices.any { it.first == m } } ?: 0
    }

    fun upscalerHasSharpness(mode: Int): Boolean = canonicalUpscaler(mode) in 3..8

    fun upscaler(context: Context): Int = canonicalUpscaler(prefs(context).getInt("upscaler", 0))

    fun setUpscaler(context: Context, mode: Int) {
        prefs(context).edit().putInt("upscaler", canonicalUpscaler(mode)).apply()
    }

    fun upscaleSharpness(context: Context): Int = prefs(context).getInt("upscaleSharpness", 75).coerceIn(0, 100)

    fun setUpscaleSharpness(context: Context, pct: Int) {
        prefs(context).edit().putInt("upscaleSharpness", pct.coerceIn(0, 100)).apply()
    }


    fun screenEffects(context: Context): ScreenEffects {
        val text = prefs(context).getString("screenEffects", null) ?: return ScreenEffects.OFF
        return runCatching { ScreenEffects.decode(JSONObject(text)) }.getOrDefault(ScreenEffects.OFF)
    }

    fun setScreenEffects(context: Context, effects: ScreenEffects) {
        prefs(context).edit().putString("screenEffects", effects.encode().toString()).apply()
    }

    const val CARDBOARD_KEY = "cardboard"

    fun cardboard(context: Context): Cardboard {
        val text = prefs(context).getString(CARDBOARD_KEY, null) ?: return Cardboard.OFF
        return runCatching { Cardboard.decode(JSONObject(text)) }.getOrDefault(Cardboard.OFF)
    }

    fun setCardboard(context: Context, cardboard: Cardboard) {
        prefs(context).edit().putString(CARDBOARD_KEY, cardboard.encode().toString()).apply()
    }

    fun cardboardSourceBeforeSteamVr(context: Context): Int = prefs(context).getInt("cardboardSourceBeforeSteamVr", -1)

    fun setCardboardSourceBeforeSteamVr(context: Context, source: Int) {
        prefs(context).edit().putInt("cardboardSourceBeforeSteamVr", source).apply()
    }

    fun sessionPrefs(context: Context): android.content.SharedPreferences = prefs(context)

    val textureAnisotropyChoices = TextureFiltering.ANISOTROPY.map { it to if (it == 0) "Off" else "${it}x" }

    fun textureAnisotropyChoices(context: Context): List<Pair<Int, String>> =
        textureAnisotropyChoices.map { (value, label) -> value to if (value == 0) context.getString(R.string.frame_gen_off) else label }

    val textureLodBiasChoices = TextureFiltering.LOD_BIAS.map {
        it to when (it) {
            TextureFiltering.LOD_BIAS_OFF -> "Off"
            TextureFiltering.LOD_BIAS_AUTO -> "Auto (match scaling)"
            else -> it
        }
    }

    fun textureLodBiasChoices(context: Context): List<Pair<String, String>> = textureLodBiasChoices.map { (value, label) ->
        value to when (value) {
            TextureFiltering.LOD_BIAS_OFF -> context.getString(R.string.frame_gen_off)
            TextureFiltering.LOD_BIAS_AUTO -> context.getString(R.string.sprefs_lod_bias_auto)
            else -> label
        }
    }

    fun textureAnisotropy(context: Context): Int =
        prefs(context).getInt("textureAnisotropy", 0).takeIf { it in TextureFiltering.ANISOTROPY } ?: 0

    fun setTextureAnisotropy(context: Context, value: Int) {
        prefs(context).edit().putInt("textureAnisotropy", value).apply()
        publishGameEnvironment(context)
    }

    fun textureLodBias(context: Context): String =
        prefs(context).getString("textureLodBias", null)?.takeIf { it in TextureFiltering.LOD_BIAS } ?: TextureFiltering.LOD_BIAS_OFF

    fun setTextureLodBias(context: Context, choice: String) {
        prefs(context).edit().putString("textureLodBias", choice).apply()
        publishGameEnvironment(context)
    }

    fun gameTextureFiltering(context: Context, scope: String): GameTextureFiltering {
        require(GameEnvironment.validScope(scope))
        return gameTextureFiltering(context)[scope] ?: GameTextureFiltering()
    }

    fun gameTextureFiltering(context: Context): Map<String, GameTextureFiltering> {
        val json = runCatching {
            JSONObject(prefs(context).getString("gameTextureFiltering", null) ?: "{}")
        }.getOrElse { JSONObject() }
        return buildMap {
            json.keys().forEach { scope ->
                if (!GameEnvironment.validScope(scope)) return@forEach
                val entry = json.optJSONObject(scope) ?: return@forEach
                val anisotropy = entry.optInt("anisotropy").takeIf {
                    entry.has("anisotropy") && it in TextureFiltering.ANISOTROPY
                }
                val lodBias = entry.optString("lodBias").takeIf {
                    entry.has("lodBias") && it in TextureFiltering.LOD_BIAS
                }
                if (anisotropy != null || lodBias != null) {
                    put(scope, GameTextureFiltering(anisotropy, lodBias))
                }
            }
        }
    }

    fun setGameTextureAnisotropy(context: Context, scope: String, value: Int?) {
        require(value == null || value in TextureFiltering.ANISOTROPY)
        updateGameTextureFiltering(context, scope) { it.copy(anisotropy = value) }
    }

    fun setGameTextureLodBias(context: Context, scope: String, choice: String?) {
        require(choice == null || choice in TextureFiltering.LOD_BIAS)
        updateGameTextureFiltering(context, scope) { it.copy(lodBias = choice) }
    }

    fun clearGameTextureFiltering(context: Context, scope: String) {
        require(GameEnvironment.validScope(scope))
        val choices = gameTextureFiltering(context).toMutableMap()
        if (choices.remove(scope) == null) return
        saveGameTextureFiltering(context, choices)
        publishGameEnvironment(context)
    }

    private fun updateGameTextureFiltering(
        context: Context,
        scope: String,
        change: (GameTextureFiltering) -> GameTextureFiltering,
    ) {
        require(GameEnvironment.validScope(scope))
        val choices = gameTextureFiltering(context).toMutableMap()
        val next = change(choices[scope] ?: GameTextureFiltering())
        if (next.anisotropy == null && next.lodBias == null) choices.remove(scope) else choices[scope] = next
        saveGameTextureFiltering(context, choices)
        publishGameEnvironment(context)
    }

    private fun saveGameTextureFiltering(context: Context, choices: Map<String, GameTextureFiltering>) {
        val json = JSONObject()
        choices.forEach { (id, choice) ->
            json.put(id, JSONObject().apply {
                choice.anisotropy?.let { put("anisotropy", it) }
                choice.lodBias?.let { put("lodBias", it) }
            })
        }
        prefs(context).edit().putString("gameTextureFiltering", json.toString()).apply()
    }

    private fun publishGameEnvironment(context: Context) {
        runCatching { GameEnvironmentStore.publish(context) }
            .onFailure { android.util.Log.e("GameEnvironment", "Could not update game environment", it) }
    }

    fun prefMode(mode: String): String = if (mode == SessionService.MODE_RUN) SessionService.MODE_STEAM else mode

    fun pipAutoEnter(context: Context): Boolean = prefs(context).getBoolean("pipAutoEnter", false)

    fun setPipAutoEnter(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("pipAutoEnter", enabled).apply()
    }

    fun unfoldedControls(context: Context): Boolean = prefs(context).getBoolean("unfoldedControls", false)

    fun setUnfoldedControls(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("unfoldedControls", enabled).apply()
    }

    const val SURFACE_STICK = "stick"
    const val SURFACE_TRACKPAD = "trackpad"

    fun gamepadSurface(context: Context, right: Boolean): String =
        prefs(context).getString(if (right) "gamepadRight" else "gamepadLeft", null)
            ?: if (right) SURFACE_TRACKPAD else SURFACE_STICK

    fun setGamepadSurface(context: Context, right: Boolean, surface: String) {
        prefs(context).edit().putString(if (right) "gamepadRight" else "gamepadLeft", surface).apply()
    }

    fun suspendPolicy(context: Context, mode: String): String =
        prefs(context).getString("suspendPolicy.${prefMode(mode)}", SUSPEND_MANUAL)
            ?.let { if (it == LEGACY_SUSPEND_DOWNLOADS) SUSPEND_AUTO else it }
            ?.takeIf { it in setOf(SUSPEND_AUTO, SUSPEND_NATIVE, SUSPEND_MANUAL, SUSPEND_NEVER) }
            ?.let { if (mode != SessionService.MODE_STEAM && it == SUSPEND_NATIVE) SUSPEND_AUTO else it }
            ?: SUSPEND_MANUAL

    fun setSuspendPolicy(context: Context, mode: String, policy: String) {
        val normalized = policy.takeIf { it in setOf(SUSPEND_AUTO, SUSPEND_MANUAL, SUSPEND_NEVER) ||
            (it == SUSPEND_NATIVE && mode == SessionService.MODE_STEAM) }
            ?: SUSPEND_MANUAL
        val prefs = prefs(context)
        val key = "suspendPolicy.${prefMode(mode)}"
        prefs.edit().apply {
            if (mode == SessionService.MODE_STEAM && prefs.getString(key, null) == LEGACY_SUSPEND_DOWNLOADS) {
                putBoolean(STEAM_DOWNLOADS_IN_BACKGROUND, true)
            }
            putString(key, normalized)
        }.apply()
    }

    fun steamDownloadsInBackground(context: Context): Boolean {
        val prefs = prefs(context)
        return prefs.getBoolean(STEAM_DOWNLOADS_IN_BACKGROUND, false) ||
            prefs.getString("suspendPolicy.${SessionService.MODE_STEAM}", null) == LEGACY_SUSPEND_DOWNLOADS
    }

    fun setSteamDownloadsInBackground(context: Context, enabled: Boolean) {
        val prefs = prefs(context)
        val policyKey = "suspendPolicy.${SessionService.MODE_STEAM}"
        prefs.edit().apply {
            putBoolean(STEAM_DOWNLOADS_IN_BACKGROUND, enabled)
            if (prefs.getString(policyKey, null) == LEGACY_SUSPEND_DOWNLOADS) {
                putString(policyKey, SUSPEND_AUTO)
            }
        }.apply()
    }

    fun zramCompression(context: Context): Boolean {
        val prefs = prefs(context)
        return if (prefs.contains("zramCompression")) prefs.getBoolean("zramCompression", false) else ZramSupport.recommended(context)
    }

    fun setZramCompression(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("zramCompression", on).apply()
    }


    fun gameStorage(context: Context): String = prefs(context).getString("gameStorage", "") ?: ""

    const val GAME_STORAGE_OFF = "off"

    fun gameStorageLabel(context: Context): String = prefs(context).getString("gameStorageLabel", "SD Card") ?: "SD Card"

    fun setGameStorage(context: Context, path: String, label: String) {
        prefs(context).edit().putString("gameStorage", path).putString("gameStorageLabel", label).apply()
    }
}
