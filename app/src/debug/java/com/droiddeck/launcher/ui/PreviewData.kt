package com.droiddeck.launcher.ui

import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.gpu.FrameGen
import com.droiddeck.launcher.gpu.Lossless
import com.droiddeck.launcher.input.ControllerPrefs
import com.droiddeck.launcher.input.SecondScreenMode
import com.droiddeck.launcher.input.SecondScreenDisplay
import com.droiddeck.launcher.session.ComponentsManager
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.update.AppUpdates
import java.io.File

/** Deterministic fixtures shared by Studio previews and the render smoke tests. */
internal object PreviewData {
    val games = listOf("Portal 2", "Celeste", "Stardew Valley", "Hollow Knight", "A Short Hike", "Dead Cells")
        .mapIndexed { i, name -> Library.SteamGame(100 + i, name, null, "/preview/steam") }
    val controller = ControllerPrefs.Settings(0xFF1A9FFF.toInt(), 80, 100, true, true, true, true, true, true, false, emptyMap())
    fun front(empty: Boolean = false, busy: Boolean = false, ready: Boolean = true, home: Boolean = false, updates: UpdatesState = UpdatesState()) = FrontEndState(
        installed = if (ready) "2026.10.07" else null, ready = ready, available = "2026.10.07",
        busy = busy, stage = if (busy) "Downloading runtime" else "", percent = if (busy) 42 else -1,
        desktopInstalled = ready, offlineAccount = "Player", offline = false, frameGenLabel = "Off", romsDir = "/preview/ROMs", logsEnabled = true,
        steamGames = if (empty) emptyList() else games,
        emulators = listOf(Library.Emulator("dolphin", "Dolphin", "GameCube / Wii", "dolphin-emu", true, listOf(
            Library.Rom("Wind Waker", File("/preview/ROMs/Wind Waker.iso"), "/root/ROMs/Wind Waker.iso", "dolphin"),
        ))), running = null, controller = controller, animationsEnabled = false, storeEnabled = true,
        buildLabel = "Design preview", updates = updates, isHomeApp = home, homeScreenEnabled = home,
        androidApps = if (home) listOf(com.droiddeck.launcher.HomeApp.LaunchableApp("Files", "preview.files", "preview.files.Main", null),
            com.droiddeck.launcher.HomeApp.LaunchableApp("Settings", "preview.settings", "preview.settings.Main", null)) else emptyList(),
    )
    val frontActions = FrontEndActions(
        onPlay = {},
        onPlayDesktopUi = {},
        onSteamGame = {},
        onDesktop = {},
        onEmulator = {},
        onRom = {},
        onResume = {},
        onSteamSettings = {},
        onDesktopSettings = {},
        onInstallPackage = {},
        onRemovePackage = {},
        onRuntime = {},
        onFrameGenPick = {},
        onImportLossless = {},
        onProtons = {},
        onComponents = {},
        onPerformance = {},
        onRoms = {},
        onFiles = {},
        onLogs = {},
        onOffline = {},
        onWirelessAdbPair = { _, _, _, done -> done("Preview only") },
        onWirelessAdbApply = { _, _, _, done -> done("Preview only") },
        onSetPhantomProcessLimit = { _, done -> done("Preview only") },
        controller = ControllerActions(
            onOsc = {},
            onTint = {},
            onOpacity = {},
            onSize = {},
            onStickClick = {},
            onAdaptiveSticks = {},
            onRumble = {},
            onSteamButton = {},
            onQamButton = {},
            onKeyboardButton = {},
            onEditLayout = {},
            onResetLayout = {},
            onMapping = {},
            onResetAll = {},
        ),
    )
    fun mode(steam: Boolean = true) = ModeSettings(
        mode = if (steam) SessionService.MODE_STEAM else SessionService.MODE_DESKTOP, resolution = "1280x720",
        hdr = false, hdrReason = "HDR is unavailable on this display", touchMode = SessionPrefs.TOUCH_AUTO,
        suspendPolicy = SessionPrefs.SUSPEND_AUTO, oscMode = SessionPrefs.OSC_AUTO,
        directAudio = true, mic = false, renderer = "vulkan", stretch16x9 = false,
        pipSupported = true, syncBackend = "auto", steamChannel = "stable", steamController = "deck",
        addedGamesDirs = listOf("/preview/Games"), deckyInstalled = "3.0.0", deckyEnabled = true,
    )
    val modeActions = ModeSettingsActions(
        onResolution = {},
        onHdr = {},
        onTouch = {},
        onSuspendPolicy = {},
        onOsc = {},
        onDirectAudio = {},
        onMic = {},
        onRenderer = {},
        onDismiss = {},
    )
    val components = ComponentsManager.Snapshot(
        protons = listOf(ComponentsManager.ProtonView(
            ComponentsManager.Proton("ge-proton", "GE-Proton", File("/preview/proton"), "/opt/proton", "10-20", false),
            mapOf("fex" to ComponentsManager.Component("FEX-2609", "FEX-2609", "Original", null, null),
                  "dxvk" to ComponentsManager.Component("2.7", "DXVK 2.7", "Original", null, null),
                  "vkd3d" to ComponentsManager.Component("2.14", "VKD3D-Proton 2.14", "Original", null, null)),
            emptyList(), false,
        )),
        packages = listOf(ComponentsManager.Package("dxvk-2.7.tzst", "dxvk", "2.7", "DXVK 2.7", 24_000_000)),
    )
    val catalog = listOf(ComponentsManager.CatalogItem("dxvk-2.7.tzst", "dxvk", "2.7", "", 24_000_000, ""))
    val drivers = listOf(DriverRow("auto", "Auto", "Recommended for this device", false),
        DriverRow("turnip", "Turnip 25.3", "Android and Linux", true, DriverRow.BUNDLE))
    val gpu = GpuDriversState(gpuName = "Adreno 740", gpuFamily = "Adreno 7xx", soc = "Snapdragon 8 Gen 2",
        supportText = "Turnip supported", autoStatus = "Turnip 25.3", activeBundle = "Turnip 25.3",
        pairs = listOf(PairRow("turnip", "Turnip", "25.3", "Android and Linux", true, true, true, true, true)),
        androidRows = drivers, linuxRows = drivers)
    val drawerActions = DrawerActions(
        steam = true, isHomeApp = false, androidApps = emptyList(), hudOn = true,
        frameGen = FrameGen.Mode.OFF, lossless = Lossless.State.NONE, oscMode = SessionPrefs.OSC_AUTO,
        onScreenButtonsVisible = true, suspendPolicy = SessionPrefs.SUSPEND_AUTO, backActionsInverted = false,
        touchMode = SessionPrefs.TOUCH_AUTO, touchAuto = "Touchpad", fexPreset = "balanced",
        secondScreenMode = SecondScreenMode.NONE, secondScreenDisplays = listOf(SecondScreenDisplay(2, "Secondary screen")),
        selectedSecondScreenDisplay = 2, controller = controller, components = components, pipSupported = true,
        onHud = {},
        onFrameGenPick = {},
        onImportLossless = {},
        onKeyboard = {},
        onHardwareKeyboard = {},
        onSteamMenu = {},
        onQam = {},
        onOsc = {},
        onSuspendPolicy = {},
        onBackActionsInverted = {},
        onTouch = {},
        onFexPreset = {},
        onSecondScreenMode = {},
        onSecondScreenDisplay = {},
        onLaunchAndroidApp = { _, _ -> },
        onBackground = {},
        onShareLogs = {},
        onStop = {},
        onClose = {},
    )
    const val TIME = 1_791_331_200_000L
    val installed = AppUpdates.Installed("preview-old", 0, "0.3.0", 10, true, TIME - 86_400_000, true)
    val release = AppUpdates.Release("v0.3.1", "Improve handheld layouts", "Settings and library improvements.", "preview-new", 0, "0.3.1", 11, TIME,
        "", AppUpdates.Apk("DroidDeck.apk", "", 200_000_000, "", "com.droiddeck.launcher", 11, com.droiddeck.launcher.BuildConfig.RELEASE_SIGNER))
    val updatesCatalog = AppUpdates.Catalog(release, release, emptyList(), TIME,
        listOf(AppUpdates.PreviewChange("preview-new", "Improve handheld layouts", "Settings and library improvements.", TIME)))
}
