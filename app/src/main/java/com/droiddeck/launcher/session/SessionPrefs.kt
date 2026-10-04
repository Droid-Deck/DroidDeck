package com.droiddeck.launcher.session

import android.content.Context

/** The in-session switches: the HUD and how the on-screen controls decide to appear. */
object SessionPrefs {
    const val SUSPEND_AUTO = "auto"
    const val SUSPEND_MANUAL = "manual"
    const val SUSPEND_NEVER = "never"

    const val CONTROLLER_DECK = "deck"
    const val CONTROLLER_XBOX360 = "xbox360"
    const val OSC_AUTO = "auto"
    const val OSC_ALWAYS = "always"
    const val OSC_STEAM_QAM = "steam-qam"
    const val OSC_NEVER = "never"

    const val BACK_MENU_THEN_QAM = "1: menu 2: QAM"
    const val BACK_QAM_THEN_MENU = "1: QAM 2: menu"

    fun backActionsOrder(inverted: Boolean): String =
        if (inverted) BACK_QAM_THEN_MENU else BACK_MENU_THEN_QAM

    private fun prefs(context: Context) = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    /** Whether the launcher hides Android's status and navigation bars. */
    fun launcherFullscreen(context: Context): Boolean = prefs(context).getBoolean("launcherFullscreen", true)

    fun setLauncherFullscreen(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("launcherFullscreen", on).apply()
    }

    fun animationsEnabled(context: Context): Boolean = prefs(context).getBoolean("animations", true)

    fun setAnimationsEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("animations", on).apply()
    }

    /** The Flathub Store (beta): its rail item. Off by default. */
    fun storeEnabled(context: Context): Boolean = prefs(context).getBoolean("storeEnabled", false)

    fun setStoreEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("storeEnabled", on).apply()
    }

    /**
     * The session's performance HUD (the fps box). A Deck-mode Steam session with the performance
     * overlay has Steam's own (mangoapp, from the QAM), so there the HUD is off unless turned on
     * in one - a choice kept apart from every other session's, where it stays on by default.
     */
    fun hudEnabled(context: Context): Boolean = prefs(context).getBoolean(hudKey(context), !mangoappSession(context))

    fun setHudEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(hudKey(context), on).apply()
    }

    private fun hudKey(context: Context) = if (mangoappSession(context)) "hudDeck" else "hud"

    /** A Steam session that runs Deck mode with its performance overlay (mangoapp). */
    private fun mangoappSession(context: Context) =
        SessionState.mode == SessionService.MODE_STEAM && steamDeckMode(context) && mangoapp(context)

    /** When enabled, a single Back opens Steam QAM and a double Back opens the session menu. */
    fun backActionsInverted(context: Context): Boolean = prefs(context).getBoolean("backActionsInverted", false)

    fun setBackActionsInverted(context: Context, inverted: Boolean) {
        prefs(context).edit().putBoolean("backActionsInverted", inverted).apply()
    }

    const val TOUCH_AUTO = "auto"
    const val TOUCH_PAD = "touchpad"
    const val TOUCH_DIRECT = "direct"
    const val TOUCH_OFF = "off"

    /** How touch drives the pointer: a touchpad (drag moves it from where it is) or direct
     *  (it jumps under the finger). Auto = touchpad on the desktop, direct in Steam.
     *  Off ignores touches on the guest picture; Android controls remain usable. */
    fun touchMode(context: Context): String = prefs(context).getString("touch", TOUCH_AUTO) ?: TOUCH_AUTO

    fun setTouchMode(context: Context, mode: String) {
        prefs(context).edit().putString("touch", mode).apply()
    }

    const val SHAPE_AUTO = "auto"
    const val SHAPE_WIDE = "16:9"
    const val SHAPE_EXACT = "exact"

    /** The choices the settings offer, in order. */
    val shapeChoices = listOf(
        SHAPE_AUTO to "Auto (16:9+)",
        SHAPE_EXACT to "Match screen",
        SHAPE_WIDE to "Always 16:9",
    )

    /**
     * The shape of the display the session presents: the panel's own (never narrower than 16:9),
     * exactly the panel's (a 4:3 or 3:2 handheld, drawn edge to edge), or a fixed 16:9. A foldable defaults to 16:9, which sits with modest bars on either of its
     * panels; the panel's own shape would fit one and leave a strip on the other, and gamescope's
     * display cannot change size once the session is up.
     */
    fun shapeMode(context: Context): String =
        prefs(context).getString("shape", null)
            ?: if (context.packageManager.hasSystemFeature("android.hardware.sensor.hinge_angle")) SHAPE_WIDE else SHAPE_AUTO

    fun setShapeMode(context: Context, mode: String) {
        prefs(context).edit().putString("shape", mode).apply()
    }

    fun oscMode(context: Context): String = prefs(context).getString("osc", OSC_AUTO) ?: OSC_AUTO

    fun setOscMode(context: Context, mode: String) {
        prefs(context).edit().putString("osc", mode).apply()
    }

    /**
     * The imported glibc Turnip every session draws with inside the runtime - Steam, its games and
     * the desktop alike, as they run on the same GPU; "" = the driver built into the runtime.
     * Resolved by LinuxVulkanDriver at session start. It was once chosen per mode: the Steam
     * session's choice, the one nearly everyone set, carries over.
     */
    fun linuxDriver(context: Context): String =
        prefs(context).getString("linuxDriver", null)
            ?: prefs(context).getString("linuxDriver.steam", null)
            ?: prefs(context).getString("linuxDriver.desktop", "") ?: ""

    fun setLinuxDriver(context: Context, id: String) {
        prefs(context).edit().putString("linuxDriver", id).apply()
    }

    const val GPU_DRIVERS_AUTO = "auto"
    const val GPU_DRIVERS_MANUAL = "manual"

    /**
     * Who picks the GPU drivers: [GPU_DRIVERS_AUTO] (the app, the matched pair recommended for
     * this GPU, kept current - DriverPairs) or [GPU_DRIVERS_MANUAL] (the user). Auto for new
     * installs; see settleGpuDriverMode.
     */
    fun gpuDriverMode(context: Context): String =
        prefs(context).getString("gpuDriverMode", GPU_DRIVERS_AUTO) ?: GPU_DRIVERS_AUTO

    fun setGpuDriverMode(context: Context, mode: String) {
        prefs(context).edit().putString("gpuDriverMode", mode).apply()
    }

    /**
     * Auto arrived after people had picked drivers by hand: an install that chose either driver
     * keeps its choice (Manual), everyone else is Auto. Decided once, at process start.
     */
    fun settleGpuDriverMode(context: Context) {
        val p = prefs(context)
        if (p.contains("gpuDriverMode")) return
        val chosen = androidDriver(context).isNotEmpty() || linuxDriver(context).isNotEmpty()
        p.edit().putString("gpuDriverMode", if (chosen) GPU_DRIVERS_MANUAL else GPU_DRIVERS_AUTO).apply()
    }

    /** Drivers Auto downloaded: the only ones it removes when a newer pair replaces them. */
    fun gpuAutoInstalled(context: Context): Set<String> =
        prefs(context).getStringSet("gpuAutoInstalled", emptySet()).orEmpty()

    fun setGpuAutoInstalled(context: Context, ids: Set<String>) {
        prefs(context).edit().putStringSet("gpuAutoInstalled", ids.toSet()).apply()
    }

    /**
     * The Steam client's own sound through the DirectAudio relay instead of the classic AAudio
     * sink. Off by default: on an AYN Thor (Android 13, 20 ms bursts) the relay path stayed choppy
     * where the classic sink - the one 0.1.5 shipped - was fine.
     */
    fun clientDirectAudio(context: Context): Boolean = prefs(context).getBoolean("clientDirectAudio", false)

    fun setClientDirectAudio(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("clientDirectAudio", on).apply()
    }

    /**
     * Steam only: gamescope makes every game window the size of the screen. A game that resizes
     * its own window when it loses focus (FlatOut) otherwise comes back smaller, drawn in a
     * corner; a game that sets its own resolution and never looks at its window again (Quake 3)
     * instead draws small in the bottom-left of the stretched one. On unless turned off.
     */
    fun forceFullscreen(context: Context): Boolean = prefs(context).getBoolean("forceFullscreen", true)

    fun setForceFullscreen(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("forceFullscreen", on).apply()
        writeForceFullscreenFlag(context)
    }

    fun stretch16x9(context: Context): Boolean = prefs(context).getBoolean("stretch16x9", false)

    fun setStretch16x9(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("stretch16x9", on).apply()
    }

    /**
     * The same choice as a file the running session watches, so the drawer can change it live:
     * the session hands every change to gamescope, which reads GAMESCOPE_FORCE_WINDOWS_FULLSCREEN
     * off its root window whenever it changes. Written again at every session start so a file left
     * by an earlier session never disagrees with the setting.
     */
    fun writeForceFullscreenFlag(context: Context) {
        runCatching {
            java.io.File(com.droiddeck.launcher.runtime.LinuxRuntime.rootDir(context), "root/.droiddeck-fill")
                .writeText(if (forceFullscreen(context)) "1\n" else "0\n")
        }
    }

    /**
     * DirectAudio for games: their Wine audio driver talks to the relay helper on this side. On
     * unless the user turned it off.
     */
    fun directAudio(context: Context): Boolean = prefs(context).getBoolean("directAudio", true)

    fun setDirectAudio(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("directAudio", on).apply()
    }

    /** The microphone for voice chat, off until the user turns it on (which asks for RECORD_AUDIO). */
    fun micEnabled(context: Context): Boolean = prefs(context).getBoolean("mic", false)

    /** Whether the app has already asked for the microphone once at start-up. */
    fun micAsked(context: Context): Boolean = prefs(context).getBoolean("micAsked", false)

    fun setMicAsked(context: Context) {
        prefs(context).edit().putBoolean("micAsked", true).apply()
    }

    fun setMicEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("mic", on).apply()
    }

    /** Optional SSID reporting and scans. A Location grant alone never opts the user in. */
    fun wifiDiscoveryEnabled(context: Context): Boolean = prefs(context).getBoolean("wifiDiscovery", false)

    fun setWifiDiscoveryEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("wifiDiscovery", on).apply()
    }

    fun wifiDiscoveryAsked(context: Context): Boolean = prefs(context).getBoolean("wifiDiscoveryAsked", false)

    fun setWifiDiscoveryAsked(context: Context) {
        prefs(context).edit().putBoolean("wifiDiscoveryAsked", true).apply()
    }

    /**
     * Whether the client's own core pick is overridden. When on, BL_CLIENT_CPUS is sent even when
     * it names every core - unlike a game mask, the point here is to undo a pin Steam applies to
     * itself, and the scheduler's default is exactly what Steam's choice takes away.
     */
    fun clientCpusOverride(context: Context): Boolean = prefs(context).getBoolean("clientCpusOverride", false)

    fun setClientCpusOverride(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("clientCpusOverride", on).apply()
    }

    /** Comma-separated core list for the client (taskset -c syntax); "" = every core. */
    fun clientCpus(context: Context): String = prefs(context).getString("clientCpus", "") ?: ""

    fun setClientCpus(context: Context, list: String) {
        prefs(context).edit().putString("clientCpus", list).apply()
    }

    /** Comma-separated core list for games (taskset -c syntax); "" = every core = nothing sent. */
    fun gameCpus(context: Context): String = prefs(context).getString("gameCpus", "") ?: ""

    fun setGameCpus(context: Context, list: String) {
        prefs(context).edit().putString("gameCpus", list).apply()
    }

    /**
     * Whether Steam's xalia helper is kept out of the session (PROTON_USE_XALIA=0).
     *
     * xalia is an x86 Windows program Proton launches to give Windows programs gamepad navigation.
     * Under FEX it cannot load the session's aarch64 preload shim, so its socket() and memfd calls
     * reach the vendor's seccomp filter raw; where that answers ENOSYS - a Galaxy Fold, measured -
     * it storms, and the session dies seconds after Big Picture appears. On by default: where it
     * does run, it sits beside every game under FEX for nothing a controller-first session needs
     * (about 10% of a core beside Once Upon a KATAMARI on an SD 8 Gen 2).
     */
    fun noXalia(context: Context): Boolean = prefs(context).getBoolean("noXalia", true)

    fun setNoXalia(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("noXalia", on).apply()
    }

    fun fastSync(context: Context): Boolean = prefs(context).getBoolean("fastSync", false)

    fun setFastSync(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("fastSync", on).apply()
    }

    fun fsyncFirst(context: Context): Boolean = prefs(context).getBoolean("fsyncFirst", true)

    fun setFsyncFirst(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("fsyncFirst", on).apply()
    }

    const val SYNC_ESYNC = "esync"
    const val SYNC_NTSYNC = "ntsync"
    const val SYNC_FSYNC = "fsync"
    const val SYNC_WINESERVER = "wineserver"

    /**
     * The sync Proton games use, as the three switches above decide it: droiddeck-ntsync wins
     * while it is on, then droiddeck-fsync first, then droiddeck-esync, and wineserver alone while all three are off.
     */
    fun syncBackend(context: Context): String = syncBackendOf(fastSync(context), fsyncFirst(context), syncFallback(context))

    fun syncBackendOf(fastSync: Boolean, fsyncFirst: Boolean, syncFallback: Boolean): String = when {
        fastSync -> SYNC_NTSYNC
        fsyncFirst -> SYNC_FSYNC
        syncFallback -> SYNC_ESYNC
        else -> SYNC_WINESERVER
    }

    /** Picks one sync for Proton games; the switches change together, in one write. */
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

    /**
     * Whether gamescope asks for realtime-priority Vulkan queues (GAMESCOPE_FORCE_VULKAN_REALTIME=1,
     * which the app's gamescope build honours without CAP_SYS_NICE). Off by default, as in
     * Bannerlator's session: the compositor's queue preempting the game's buys nothing on a device
     * whose GPU is waiting on the CPU.
     */
    fun gamescopeRealtime(context: Context): Boolean = prefs(context).getBoolean("gamescopeRealtime", false)

    fun setGamescopeRealtime(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("gamescopeRealtime", on).apply()
    }

    /**
     * Whether proot runs without its seccomp acceleration (PROOT_NO_SECCOMP=1).
     *
     * proot normally installs a seccomp filter so only the syscalls it must rewrite stop in the
     * tracer; everything else runs untraced, which is most of proot's speed. Where a vendor kernel
     * handles that filter badly the wrong calls are trapped or refused - ENOSYS from calls that
     * plainly exist is the signature - and the fallback is to trace everything instead: slower,
     * but correct. Max's advice for devices whose kernels "don't work well with it".
     */
    /** Hold the GPU at its top clock during a session (GpuClockPin). Off by default: power and heat. */
    fun gpuClockPin(context: Context): Boolean = prefs(context).getBoolean("gpuClockPin", false)

    fun setGpuClockPin(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("gpuClockPin", on).apply()
    }

    fun prootNoSeccomp(context: Context): Boolean = prefs(context).getBoolean("prootNoSeccomp", false)

    fun setProotNoSeccomp(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("prootNoSeccomp", on).apply()
    }

    /**
     * Whether the session's path lookups take proot's fast path (ProotFastPath): answered inside
     * each process instead of a round trip through the tracer. On by default; it needs proot's
     * seccomp filter, so it is off whenever proot runs without one.
     */
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

    /**
     * Turnip's sysmem rendering (TU_DEBUG=sysmem) for the runtime's driver: bypasses GMEM tiling.
     * On by default, as WinNative runs every Linux session: Chromium -> ANGLE -> Zink draws the
     * client's interface as many small render passes, each paying GMEM's load/store and binning,
     * and WinNative's A/B on an Adreno 840 put a game ahead with it too (Palworld 42.8 against 41.3).
     */
    fun tuSysmem(context: Context): Boolean = prefs(context).getBoolean("tuSysmem", true)

    fun setTuSysmem(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("tuSysmem", on).apply()
    }

    /*
     * The client's interface is drawn Chromium -> ANGLE -> Zink -> Turnip, and that chain is what
     * limits its menus (~14 fps on an Adreno 840 while a game ran 89). These make the chain
     * cheaper rather than asking for more; the three environment switches are on by default,
     * Deck mode off. Bannerlator's LinuxTuning, carried over; none device-proven here yet.
     */
    /** mesa_glthread=true: GL marshalled off the calling thread. */
    fun glThread(context: Context): Boolean = prefs(context).getBoolean("glThread", true)
    fun setGlThread(context: Context, on: Boolean) { prefs(context).edit().putBoolean("glThread", on).apply() }

    /** MESA_NO_ERROR=1: no GL error checking. */
    fun noGlError(context: Context): Boolean = prefs(context).getBoolean("noGlError", true)
    fun setNoGlError(context: Context, on: Boolean) { prefs(context).edit().putBoolean("noGlError", on).apply() }

    /**
     * What the pad is to the Steam client: [CONTROLLER_DECK], a Steam Deck controller (Quick Access
     * button, gyro, Steam Input's full treatment - SteamDeckPad), or [CONTROLLER_XBOX360], the plain
     * Xbox 360 pad of earlier versions (QAM by the Guide+A chord).
     */
    fun steamController(context: Context): String =
        prefs(context).getString("steamController", CONTROLLER_DECK) ?: CONTROLLER_DECK
    fun setSteamController(context: Context, id: String) { prefs(context).edit().putString("steamController", id).apply() }

    /** Runs the SteamOS gamepad client with its Quick Access performance controls. On by default (settleDeckModeDefault). */
    fun steamDeckMode(context: Context): Boolean = prefs(context).getBoolean("steamDeckMode", true)
    fun setSteamDeckMode(context: Context, on: Boolean) { prefs(context).edit().putBoolean("steamDeckMode", on).apply() }

    /**
     * Deck mode is the default: every install, new or from before, is moved to it once. An install
     * from before 0.3.0 had off written down for it whether or not anyone chose it, so the move
     * can't tell a choice from that default and moves everyone; turning it off afterwards sticks.
     * Run once at process start, before anything reads or writes these prefs.
     */
    fun settleDeckModeDefault(context: Context) {
        val p = prefs(context)
        if (p.getBoolean("deckModeMoved", false)) return
        p.edit().putBoolean("steamDeckMode", true).putBoolean("deckModeMoved", true).apply()
    }

    /**
     * Deck mode's performance overlay (mangoapp, beside gamescope): the QAM's Overlay Level draws
     * through it. Off is the way out where Valve's mangoapp crashes (one Turnip build did).
     */
    fun mangoapp(context: Context): Boolean = prefs(context).getBoolean("mangoapp", true)
    fun setMangoapp(context: Context, on: Boolean) { prefs(context).edit().putBoolean("mangoapp", on).apply() }

    /**
     * Zink's lazy descriptor mode (ZINK_DESCRIPTORS=lazy) with its compact set layout
     * (ZINK_DEBUG=compact) for the client's GL-on-Vulkan UI, as WinNative runs it. On by default.
     */
    fun zinkLazy(context: Context): Boolean = prefs(context).getBoolean("zinkLazy", true)

    fun setZinkLazy(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("zinkLazy", on).apply()
    }

    /** The Android driver the compositor loads: "" = pick by GPU, else a bundled or imported id. */
    @JvmStatic
    fun androidDriver(context: Context): String = prefs(context).getString("androidDriver", "") ?: ""

    @JvmStatic
    fun setAndroidDriver(context: Context, id: String) {
        prefs(context).edit().putString("androidDriver", id).apply()
    }

    // ── Storage the session can see ─────────────────────────────────────────────────────────────

    /**
     * The folder on this device that every session shows at `/root/ROMs`, for the emulators on the
     * desktop. "" = none chosen. All of internal storage is at `/root/Storage` regardless.
     */
    fun romsDir(context: Context): String =
        prefs(context).getString("romsDir", "") ?: ""

    fun setRomsDir(context: Context, path: String) {
        prefs(context).edit().putString("romsDir", path).apply()
    }

    /**
     * Whether a session writes its folder under Download/DroidDeck. Off, the same logs are kept in
     * the app's cache for the session's lifetime (the scripts need somewhere to write) and thrown
     * away at the end, so nothing accumulates in Downloads.
     */
    fun logsEnabled(context: Context): Boolean =
        prefs(context).getBoolean("logs", true)

    fun setLogsEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("logs", on).apply()
    }

    // ── Per-mode display ────────────────────────────────────────────────────────────────────

    /**
     * The tallest the session's display may be, in pixels, for MODE_STEAM / MODE_DESKTOP:
     * 0 = the panel's own height, otherwise a cap. Both modes default to 720p: the client's CEF
     * is the heaviest thing in a session, Big Picture is drawn for a TV at arm's length, and on
     * a handheld panel 720p is where its menus stay responsive on a regular flagship; the desktop
     * and the emulators under it get the same GPU headroom. Read once, when the session's display
     * is sized; a cap the user chose wins over the default.
     */
    fun resolutionCap(context: Context, mode: String): Int = prefs(context).getInt("resolutionCap.$mode", defaultResolutionCap(mode))

    /** Whether the user chose the mode's resolution (a cap or a custom size) rather than the default. */
    fun resolutionChosen(context: Context, mode: String): Boolean =
        prefs(context).contains("resolutionCap.$mode") || customResolution(context, mode) != null

    /**
     * The FEXCore preset for the games the client launches (core/FexPreset ids); "" = FEX's defaults.
     * Performance + TSO unless chosen, WinNative's default: FEX's own defaults keep half-barrier TSO
     * and full-precision x87, which cost every x86 game time; a game that needs them can still be
     * given another preset.
     */
    fun fexPreset(context: Context): String = prefs(context).getString("fexPreset", DEFAULT_FEX_PRESET) ?: DEFAULT_FEX_PRESET

    private const val DEFAULT_FEX_PRESET = "PERFORMANCE_TSO"

    fun setFexPreset(context: Context, id: String) {
        prefs(context).edit().putString("fexPreset", id).apply()
        runCatching { GameEnvironmentStore.publish(context) }
            .onFailure { android.util.Log.e("GameEnvironment", "Could not update game environment", it) }
    }

    /**
     * The Steam client branch used for the first download and forced on the command line:
     * "steamdeck_publicbeta" by default, or "publicbeta" when chosen with Deck mode off.
     * Deck mode always takes the Deck branch, whatever was chosen:
     * with -steamos3 the client picks its own branch as SteamOS does, and on publicbeta it settled
     * on steamdeck_stable - an older client it then offered as a "Software Update" in every session,
     * which applying turns into the exit-42 restart loop (seen on device 2026-09-30). Earlier, Deck
     * mode on publicbeta also reinstalled the same client at every start (2026-09-23). On
     * steamdeck_publicbeta the client finds no update. The choice applies with Deck mode off.
     */
    fun steamChannel(context: Context): String =
        if (steamDeckMode(context)) "steamdeck_publicbeta"
        else prefs(context).getString("steamChannel", null) ?: "steamdeck_publicbeta"

    fun setSteamChannel(context: Context, id: String) {
        prefs(context).edit().putString("steamChannel", id).apply()
    }

    /** Whether opening DroidDeck starts a Steam session instead of showing the front end. */
    fun runSteamAtStartup(context: Context): Boolean = prefs(context).getBoolean("runSteamAtStartup", false)

    fun setRunSteamAtStartup(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("runSteamAtStartup", on).apply()
    }

    /**
     * The folders of the user's own Windows games (one game per subfolder), any number of them
     * from anywhere on the device. The single folder an earlier build kept is carried in.
     */
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

    /** Whether added games without art of their own get Steam's store art fetched for them. */
    fun addedGamesArt(context: Context): Boolean = prefs(context).getBoolean("addedGamesArt", true)

    fun setAddedGamesArt(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("addedGamesArt", on).apply()
    }

    /** The .exe the user chose for one game folder (by its path), "" = the scanner's pick. */
    fun addedGameExe(context: Context, folderPath: String): String = prefs(context).getString("addedExe:$folderPath", "") ?: ""

    fun setAddedGameExe(context: Context, folderPath: String, path: String) {
        prefs(context).edit().putString("addedExe:$folderPath", path).apply()
    }

    /** The app's colour theme (ui/Themes ids); Graphite unless chosen otherwise. */
    fun theme(context: Context): String = prefs(context).getString("theme", "graphite") ?: "graphite"

    fun setTheme(context: Context, id: String) {
        prefs(context).edit().putString("theme", id).apply()
    }

    /** What a mode gets when nothing was chosen. */
    @Suppress("UNUSED_PARAMETER")
    fun defaultResolutionCap(mode: String): Int = 720

    fun setResolutionCap(context: Context, mode: String, cap: Int) {
        prefs(context).edit().putInt("resolutionCap.$mode", cap).apply()
    }

    /**
     * A fixed size for the session's display, per mode, or null. When set it replaces both the
     * cap and the shape: the compositor fits it to the panel with bars where the shapes differ.
     */
    fun customResolution(context: Context, mode: String): Pair<Int, Int>? =
        parseResolution(prefs(context).getString("customRes.$mode", null))

    fun setCustomResolution(context: Context, mode: String, size: Pair<Int, Int>?) {
        prefs(context).edit().putString("customRes.$mode", size?.let { "${it.first}x${it.second}" }).apply()
    }

    /** "1024x768" (or ×, or *) to an even size inside 320x240..3840x2160; anything else is null. */
    fun parseResolution(text: String?): Pair<Int, Int>? {
        val parts = text?.trim()?.split('x', 'X', '×', '*')?.map { it.trim() } ?: return null
        if (parts.size != 2) return null
        val w = parts[0].toIntOrNull() ?: return null
        val h = parts[1].toIntOrNull() ?: return null
        if (w !in 320..3840 || h !in 240..2160) return null
        return Pair(w and 1.inv(), h and 1.inv())
    }

    /**
     * What the desktop shell composites with: vulkan (the default) or gles2 on the GPU, through the
     * app's patched wlroots (tools/wlroots) - the Adreno stand-in is not a DRM device and stock
     * wlroots cannot allocate on it - or pixman in software. A GPU renderer a device cannot start
     * falls back to pixman by itself (droiddeck-desktop). `Download/droiddeck-wlr-renderer` still
     * overrides it.
     */
    fun desktopRenderer(context: Context): String = prefs(context).getString("desktopRenderer", "vulkan") ?: "vulkan"

    fun setDesktopRenderer(context: Context, renderer: String) {
        prefs(context).edit().putString("desktopRenderer", renderer).apply()
        // A choice made again is a retry: forget that a renderer failed to start here before.
        java.io.File(com.droiddeck.launcher.runtime.LinuxRuntime.rootDir(context), "root/.droiddeck-renderer-failed").delete()
    }

    /**
     * HDR10 output for MODE_STEAM / MODE_DESKTOP. Off by default. Honoured only when the panel
     * lists HDR10 (HdrSupport), and decided when the compositor starts, which is once per app
     * process: a change applies after the app is fully closed and opened again.
     */
    fun hdr(context: Context, mode: String): Boolean = prefs(context).getBoolean("hdr.$mode", false)

    fun setHdr(context: Context, mode: String, on: Boolean) {
        prefs(context).edit().putBoolean("hdr.$mode", on).apply()
    }

    /**
     * The session's frame cap, 0 for none. One number used everywhere a frame is paced: gamescope's
     * -r (what the client and its games see as the display's rate), the compositor's buffer release
     * pacer, the rate the display layer votes for, and the panel mode picked, which is the fastest
     * one the cap divides evenly (40 on a 120 Hz panel, not on a 144 Hz one). A 60 fps cap on a
     * 144 Hz panel with nothing else changed judders; this is what WinNative's per-shortcut limit
     * does. Applies next session.
     */
    fun fpsLimit(context: Context, mode: String): Int = prefs(context).getInt("fpsLimit.$mode", 0)

    fun setFpsLimit(context: Context, mode: String, fps: Int) {
        prefs(context).edit().putInt("fpsLimit.$mode", fps.coerceAtLeast(0)).apply()
    }

    val fpsLimitChoices = listOf(0 to "Off", 30 to "30", 40 to "40", 45 to "45", 60 to "60", 90 to "90", 120 to "120")

    val upscalerChoices = listOf(
        0 to "Off", 4 to "AMD FSR 1", 3 to "Snapdragon GSR", 8 to "Snapdragon GSR (quality)",
        7 to "NVIDIA NIS", 6 to "Sharpen only",
    )

    fun upscaler(context: Context): Int =
        prefs(context).getInt("upscaler", 0).takeIf { m -> upscalerChoices.any { it.first == m } } ?: 0

    fun setUpscaler(context: Context, mode: Int) {
        prefs(context).edit().putInt("upscaler", mode).apply()
    }

    val upscaleSharpnessChoices = listOf(0 to "0%", 25 to "25%", 50 to "50%", 75 to "75%", 100 to "100%")

    fun upscaleSharpness(context: Context): Int = prefs(context).getInt("upscaleSharpness", 75).coerceIn(0, 100)

    fun setUpscaleSharpness(context: Context, pct: Int) {
        prefs(context).edit().putInt("upscaleSharpness", pct.coerceIn(0, 100)).apply()
    }

    /**
     * The mode whose per-mode settings apply: a program run under gamescope (MODE_RUN) is a
     * fullscreen session like Steam's, so it takes Steam's display, driver and HDR choices.
     */
    fun prefMode(mode: String): String = if (mode == SessionService.MODE_RUN) SessionService.MODE_STEAM else mode

    fun pipAutoEnter(context: Context): Boolean = prefs(context).getBoolean("pipAutoEnter", false)

    fun setPipAutoEnter(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("pipAutoEnter", enabled).apply()
    }

    fun suspendPolicy(context: Context, mode: String): String =
        prefs(context).getString("suspendPolicy.${prefMode(mode)}", SUSPEND_MANUAL)
            ?.takeIf { it == SUSPEND_AUTO || it == SUSPEND_MANUAL || it == SUSPEND_NEVER }
            ?: SUSPEND_MANUAL

    fun setSuspendPolicy(context: Context, mode: String, policy: String) {
        val normalized = policy.takeIf { it == SUSPEND_AUTO || it == SUSPEND_MANUAL || it == SUSPEND_NEVER }
            ?: SUSPEND_MANUAL
        prefs(context).edit().putString("suspendPolicy.${prefMode(mode)}", normalized).apply()
    }

    // ── Game storage ────────────────────────────────────────────────────────────────────────

    /**
     * A second Steam library on this device: the folder bound at /mnt/droiddeck-sd and
     * registered with the client, which then asks where to install every game and shows both
     * on its Storage page. "" = automatic: the SD card when one is in the phone (the default,
     * so the choice is made inside the client like anywhere else); GAME_STORAGE_OFF = internal
     * only; otherwise the folder chosen.
     */
    fun gameStorage(context: Context): String = prefs(context).getString("gameStorage", "") ?: ""

    const val GAME_STORAGE_OFF = "off"

    fun gameStorageLabel(context: Context): String = prefs(context).getString("gameStorageLabel", "SD Card") ?: "SD Card"

    fun setGameStorage(context: Context, path: String, label: String) {
        prefs(context).edit().putString("gameStorage", path).putString("gameStorageLabel", label).apply()
    }
}
