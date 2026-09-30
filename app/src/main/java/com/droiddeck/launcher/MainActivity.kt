package com.droiddeck.launcher

import android.Manifest
import android.app.ActivityOptions
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import java.io.File
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import com.droiddeck.launcher.gpu.FrameGen
import com.droiddeck.launcher.gpu.TurnipDriver
import com.droiddeck.launcher.gpu.LsfgNative
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.runtime.DesktopCatalog
import com.droiddeck.launcher.runtime.LinuxRuntimeInstaller
import com.droiddeck.launcher.runtime.DeckyManager
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.ui.PackageRow
import com.droiddeck.launcher.session.OfflineMode
import com.droiddeck.launcher.session.ProtonExtras
import com.droiddeck.launcher.session.ComponentsManager
import com.droiddeck.launcher.ui.ComponentsPage
import com.droiddeck.launcher.session.SessionLogShare
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.ui.ProtonPage
import com.droiddeck.launcher.core.CpuCores
import com.droiddeck.launcher.core.PhantomProcessLimit
import com.droiddeck.launcher.core.PhantomProcessStatus
import com.droiddeck.launcher.core.WirelessAdbFix
import com.droiddeck.launcher.core.WirelessAdbPairingService
import com.droiddeck.launcher.ui.CoreRow
import com.droiddeck.launcher.ui.PerformancePage
import com.droiddeck.launcher.ui.ModeSettingsPage
import com.droiddeck.launcher.ui.ModeSettings
import com.droiddeck.launcher.ui.ModeSettingsActions
import com.droiddeck.launcher.ui.ConfirmDialog
import com.droiddeck.launcher.ui.ControllerActions
import com.droiddeck.launcher.ui.ControllerMappingPage
import com.droiddeck.launcher.input.ControllerPrefs
import com.droiddeck.launcher.input.ControllerEditorActivity
import com.droiddeck.launcher.ui.CreditsDialog
import com.droiddeck.launcher.ui.FrontEndScreen
import com.droiddeck.launcher.ui.FrontEndState
import com.droiddeck.launcher.ui.FrontEndActions
import com.droiddeck.launcher.frontend.CoverArt
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.ui.DroidDeckTheme
import com.droiddeck.launcher.ui.RomsDialog
import com.droiddeck.launcher.files.InAppFilePicker
import com.droiddeck.launcher.session.SessionArtifacts
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.session.GameStorage
import com.droiddeck.launcher.input.SecondScreenDisplay
import com.droiddeck.launcher.input.SecondScreenDisplays

/**
 * The whole app outside a session: is the runtime installed, is there a newer one, frame
 * generation, and one button that starts Steam. Everything a Steam client can do - the library,
 * the store, downloads, settings - is the client's own job once [SessionActivity] has it on screen.
 */
class MainActivity : ComponentActivity() {
    private val ui = Handler(Looper.getMainLooper())
    private val drivers = DriverMenus(this, ui)
    private val components = ComponentsMenu(this, ui)
    private val decky = DeckyMenu(this, ui)
    private val protons = ProtonMenu(this, ui)

    // The screen's state. Compose redraws whatever reads these when they change.
    private var installed by mutableStateOf<String?>(null)
    /** A session launch waiting on its button to fill the page (LaunchFlood). */
    private var flood by mutableStateOf<PendingFlood?>(null)
    private var floodProgress by androidx.compose.runtime.mutableFloatStateOf(0f)
    private class PendingFlood(val from: androidx.compose.ui.geometry.Rect, val intent: Intent)
    /** A session stopped behind a flood: its blue, drawn back into the button it started from (FloodReturn). */
    private var returning by mutableStateOf<ReturningFlood?>(null)
    private class ReturningFlood(val color: Int, val to: androidx.compose.ui.geometry.Rect?)
    private var ready by mutableStateOf(false)
    private var available by mutableStateOf<LinuxRuntimeInstaller.Release?>(null)
    private var busy by mutableStateOf(false)
    private var stage by mutableStateOf("")
    private var percent by mutableIntStateOf(-1)
    private var failed by mutableStateOf(false)
    private var frameGenLabel by mutableStateOf("Off")
    private var showRemove by mutableStateOf(false)
    private var showNonAdreno by mutableStateOf<LinuxRuntimeInstaller.Release?>(null)
    private var glThread by mutableStateOf(true)
    private var noGlError by mutableStateOf(true)
    private var steamDeckMode by mutableStateOf(false)
    private var steamController by mutableStateOf(SessionPrefs.CONTROLLER_DECK)
    private var showCredits by mutableStateOf(false)
    private var showProtons by mutableStateOf(false)
    // Components page: FEX / DXVK / VKD3D-Proton per Proton (ComponentsManager).
    private var showComponents by mutableStateOf(false)
    private var focusComponentsContent by mutableStateOf(true)
    private var showMapping by mutableStateOf(false)
    private var controllerSettings by mutableStateOf<ControllerPrefs.Settings?>(null)
    private var catalog by mutableStateOf<List<DesktopCatalog.Entry>?>(null)
    private var catalogLoading by mutableStateOf(false)
    private var packageRows by mutableStateOf<List<PackageRow>?>(null)
    private var pkgId by mutableStateOf<String?>(null)
    private var pkgStage by mutableStateOf<String?>(null)
    private var pkgPercent by mutableIntStateOf(-1)
    private var desktopInstalled by mutableStateOf(false)
    private var offlineAccount by mutableStateOf<String?>(null)
    private var offline by mutableStateOf(false)
    private var showPerformance by mutableStateOf(false)
    private var clientOverride by mutableStateOf(false)
    private var clientCores by mutableStateOf<Set<Int>>(emptySet())
    private var gameCores by mutableStateOf<Set<Int>>(emptySet())
    private var tuSysmem by mutableStateOf(false)
    private var zinkLazy by mutableStateOf(false)
    private var noXalia by mutableStateOf(true)
    private var gamescopeRealtime by mutableStateOf(false)
    private var prootNoSeccomp by mutableStateOf(false)
    private var guestHostname by mutableStateOf(SessionPrefs.DEFAULT_GUEST_HOSTNAME)
    private var phantomWarning by mutableStateOf<String?>(null)
    private var phantomProcessStatus by mutableStateOf(PhantomProcessStatus.NOT_APPLICABLE)
    private var showPhantomGate by mutableStateOf(false)
    private var directAudio by mutableStateOf(false)
    private var clientDirectAudio by mutableStateOf(false)
    private var forceFullscreen by mutableStateOf(true)
    private var launcherFullscreen by mutableStateOf(true)
    private var mic by mutableStateOf(false)

    // The app's own picker (files/), once per kind of pick: the two driver lists validate
    // differently, and the reason a zip is refused names the list it belongs in.
    private val pickComponent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedUri(r.data)?.let { importComponent(it) }
    }
    private val pickLinuxDriver = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedUri(r.data)?.let { drivers.importDriver(it, linux = true) }
    }
    private val pickAndroidDriver = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedUri(r.data)?.let { drivers.importDriver(it, linux = false) }
    }
    private val pickAddedGamesDir = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedPath(r.data)?.let { path ->
            SessionPrefs.setAddedGamesDirs(this, addedGamesDirs + path)
            addedGamesDirs = SessionPrefs.addedGamesDirs(this)
        addedGamesArt = SessionPrefs.addedGamesArt(this)
            refreshAddedGames()
            refresh()
        }
    }
    private var pendingAddedGame: String? = null
    private val pickAddedGameExe = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val folder = pendingAddedGame ?: return@registerForActivityResult
        pendingAddedGame = null
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedPath(r.data)?.let { path ->
            SessionPrefs.setAddedGameExe(this, folder, path)
            refreshAddedGames()
            refresh()
        }
    }
    private var addedGamesDirs by mutableStateOf<List<String>>(emptyList())
    private var addedGamesArt by mutableStateOf(true)
    @Volatile private var artFetchRunning = false
    private var addedGames by mutableStateOf<List<com.droiddeck.launcher.ui.AddedGameRow>>(emptyList())
    private val pickRomsDir = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedPath(r.data)?.let { path ->
            SessionPrefs.setRomsDir(this, path)
            romsDir = path
        }
    }
    // The mode whose settings dialog is open, with what it shows; refreshed by openModeSettings().
    private var settingsMode by mutableStateOf<String?>(null)
    private var resolutionCap by mutableStateOf(1080)
    private var customResolution by mutableStateOf<Pair<Int, Int>?>(null)
    private var fexPreset by mutableStateOf("")
    private var steamChannel by mutableStateOf("publicbeta")
    private var runSteamAtStartup by mutableStateOf(false)
    private var theme by mutableStateOf("graphite")
    private var shapeMode by mutableStateOf(SessionPrefs.SHAPE_AUTO)
    private var hdrOn by mutableStateOf(false)
    private var hdrReason by mutableStateOf<String?>(null)
    private var touchMode by mutableStateOf(SessionPrefs.TOUCH_AUTO)
    private var suspendPolicy by mutableStateOf(SessionPrefs.SUSPEND_MANUAL)
    private var oscMode by mutableStateOf(SessionPrefs.OSC_AUTO)
    private var backActionsInverted by mutableStateOf(false)
    private var renderer by mutableStateOf("vulkan")
    private var gameStorage by mutableStateOf("")
    private var storageOptions by mutableStateOf<List<Pair<String, String>>>(emptyList())
    private val pickGameStorage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedPath(r.data)?.let { path -> setGameStorage(path, GameStorage.labelFor(this, path)) }
    }
    private var romsDir by mutableStateOf<String?>(null)
    private var steamGames by mutableStateOf<List<Library.SteamGame>>(emptyList())
    private var emulatorList by mutableStateOf<List<Library.Emulator>>(emptyList())
    private var runningLabel by mutableStateOf<String?>(null)
    private var logsEnabled by mutableStateOf(true)
    private var showRoms by mutableStateOf(false)
    private var homeAppSelected by mutableStateOf(false)
    private var homeScreenEnabled by mutableStateOf(false)
    private var defaultHomeLabel by mutableStateOf<String?>(null)
    private var androidApps by mutableStateOf<List<HomeApp.LaunchableApp>>(emptyList())
    private var secondScreenDisplays by mutableStateOf<List<SecondScreenDisplay>>(emptyList())
    private lateinit var displayManager: DisplayManager
    private val secondScreenDisplayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refreshSecondScreenDisplays()
        override fun onDisplayRemoved(displayId: Int) = refreshSecondScreenDisplays()
        override fun onDisplayChanged(displayId: Int) = refreshSecondScreenDisplays()
    }

    private val homeRoleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshHomeAppState()
    }

    /** The session surface rises over the front end instead of cutting to it. */
    override fun startActivity(intent: Intent?) {
        if (intent?.component?.className == SessionActivity::class.java.name) {
            when {
                protons.protonBusyId != null || ProtonExtras.installInProgress -> {
                    android.widget.Toast.makeText(this, "Wait for the compatibility tool install to finish", android.widget.Toast.LENGTH_SHORT).show()
                    return
                }
                pkgStage != null -> {
                    android.widget.Toast.makeText(this, "Wait for the desktop app install to finish", android.widget.Toast.LENGTH_SHORT).show()
                    return
                }
            }
        }
        if (intent?.component?.className == SessionActivity::class.java.name) {
            if (flood != null) return
            // Started by a blue button: it floods the page first. A running session is re-joined
            // as before, rising over the front end.
            val from = com.droiddeck.launcher.ui.LaunchOrigin.take()
            if (from != null && !SessionState.running && com.droiddeck.launcher.ui.Motion.scale != 0f) {
                flood = PendingFlood(from, intent)
                return
            }
            com.droiddeck.launcher.ui.LaunchOrigin.flooding = null
        }
        super.startActivity(intent)
        if (intent?.component?.className == SessionActivity::class.java.name) overridePendingTransition(R.anim.session_rise, R.anim.session_hold)
    }

    /** The page is all blue: the session opens on the same blue, with no animation of its own. */
    private fun launchFlooded(f: PendingFlood) {
        val signal = com.droiddeck.launcher.ui.Themes.byId(theme).signal
        super.startActivity(f.intent.putExtra(com.droiddeck.launcher.ui.EXTRA_FLOOD, signal.toArgb()))
        overridePendingTransition(0, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        refreshPhantomStatus()
        theme = SessionPrefs.theme(this)
        backActionsInverted = SessionPrefs.backActionsInverted(this)
        launcherFullscreen = SessionPrefs.launcherFullscreen(this)
        applyLauncherFullscreen()
        setContent {
            DroidDeckTheme(theme) {
                val sm = settingsMode
                val page: (@Composable () -> Unit)? = when {
                    sm != null -> { { ModeSettingsHost(sm) } }
                    showPerformance -> { { PerformanceHost() } }
                    showProtons -> { { ProtonHost() } }
                    showComponents -> { { ComponentsHost() } }
                    showMapping -> { { MappingHost() } }
                    else -> null
                }
                com.droiddeck.launcher.ui.FloodBehind({ floodProgress }) {
                FrontEndScreen(
                    FrontEndState(
                        installed = installed, ready = ready, available = available?.version,
                        busy = busy, stage = stage, percent = percent,
                        desktopInstalled = desktopInstalled,
                        offlineAccount = offlineAccount, offline = offline,
                        frameGenLabel = frameGenLabel, romsDir = romsDir, logsEnabled = logsEnabled,
                        steamGames = steamGames, emulators = emulatorList, running = runningLabel,
                        frameGenEngine = FrameGen.engine(this), frameGenMultiplier = FrameGen.multiplier(this),
                        lsfgReady = LsfgNative.isInstalled(this),
                        pageKey = sm?.let { "settings:$it" } ?: if (showPerformance) "performance" else if (showProtons) "protons" else if (showComponents) "components" else if (showMapping) "controller-mapping" else null,
                        theme = theme,
                        isHomeApp = homeAppSelected,
                        homeScreenEnabled = homeScreenEnabled,
                        defaultHomeLabel = defaultHomeLabel,
                        androidApps = androidApps,
                        secondScreenDisplays = secondScreenDisplays,
                        packages = packageRows,
                        packageCatalogLoading = catalogLoading,
                        packageBusyId = pkgId,
                        packageStage = pkgStage,
                        packagePercent = pkgPercent,
                        sessionRunning = SessionState.running,
                        backActionsInverted = backActionsInverted,
                        buildLabel = BuildConfig.BUILD_LABEL,
                        oscMode = oscMode,
                        controller = controllerSettings,
                        phantomProcessStatus = phantomProcessStatus,
                        showPhantomGate = showPhantomGate,
                        launcherFullscreen = launcherFullscreen,
                    ),
                    FrontEndActions(
                        onPlay = { startSteamSession() },
                        // Steam's desktop client as a window on the desktop: under gamescope the
                        // client puts itself into Big Picture whatever it is started with.
                        onPlayDesktopUi = {
                            startSession(Intent(this, SessionActivity::class.java)
                                .putExtra(SessionService.EXTRA_MODE, SessionService.MODE_DESKTOP)
                                .putExtra(SessionService.EXTRA_STEAM_UI, "desktop"), steamSession = true)
                        },
                        onSteamGame = { g ->
                            startSession(Intent(this, SessionActivity::class.java)
                                .putExtra(SessionService.EXTRA_STEAM_URL, "steam://rungameid/${g.gameId}"), steamSession = true)
                        },
                        onDesktop = {
                            startSession(Intent(this, SessionActivity::class.java)
                                .putExtra(SessionService.EXTRA_MODE, SessionService.MODE_DESKTOP))
                        },
                        onEmulator = { e -> launchProgram(e.program) },
                        onRom = { g ->
                            val e = emulatorList.first { it.id == g.emulatorId }
                            startActivity(Intent(this, SessionActivity::class.java)
                                .putExtra(SessionService.EXTRA_MODE, SessionService.MODE_RUN)
                                .putExtra(SessionService.EXTRA_PROGRAM, e.program)
                                .putExtra(SessionService.EXTRA_PROGRAM_ARGS, Library.launchArgs(e.id, g.guestPath).toTypedArray()))
                        },
                        // The activity re-attaches to the session that is running; nothing restarts.
                        onResume = { startActivity(Intent(this, SessionActivity::class.java)) },
                        onSteamSettings = { openModeSettings(SessionService.MODE_STEAM) },
                        onDesktopSettings = { openModeSettings(SessionService.MODE_DESKTOP) },
                        onInstallPackage = { id -> installPackage(id) },
                        onRemovePackage = { id -> removePackage(id) },
                        onRuntime = { onRuntimeButton() },
                        onFrameGenPick = { engine, multiplier ->
                            FrameGen.set(this, engine, multiplier)
                            frameGenLabel = FrameGen.label(this)
                        },
                        onProtons = { openProtons() },
                        onComponents = { focusContent -> openComponents(focusContent) },
                        onPerformance = { refreshCores(); showProtons = false; showComponents = false; showMapping = false; showPerformance = true },
                        onRoms = { showRoms = true },
                        onFiles = { startActivity(Intent(this, com.droiddeck.launcher.files.FileManagerActivity::class.java)) },
                        onBrowseFiles = { dir ->
                            startActivity(Intent(this, com.droiddeck.launcher.files.FileManagerActivity::class.java)
                                .putExtra(com.droiddeck.launcher.files.FileManagerActivity.EXTRA_START_DIR, dir.absolutePath))
                        },
                        onLogs = {
                            SessionPrefs.setLogsEnabled(this, !SessionPrefs.logsEnabled(this))
                            logsEnabled = SessionPrefs.logsEnabled(this)
                        },
                        onShareLogs = {
                            Thread({
                                val zip = runCatching { SessionLogShare.zipLatest(this) }.getOrNull()
                                ui.post {
                                    if (zip == null) android.widget.Toast.makeText(this, "No session logs yet: run a session first.", android.widget.Toast.LENGTH_LONG).show()
                                    else startActivity(SessionLogShare.shareIntent(this, zip))
                                }
                            }, "share-logs").start()
                        },
                        onOffline = {
                            OfflineMode.setEnabled(this, !OfflineMode.enabled(this))
                            offline = OfflineMode.enabled(this)
                        },
                        onCredits = { showCredits = true },
                        onPageBack = { settingsMode = null; showPerformance = false; showProtons = false; showComponents = false; showMapping = false },
                        onTheme = { id -> SessionPrefs.setTheme(this, id); theme = id },
                        onLauncherFullscreen = { on ->
                            SessionPrefs.setLauncherFullscreen(this, on)
                            launcherFullscreen = on
                            applyLauncherFullscreen()
                        },
                        onHomeApp = { manageHomeApp() },
                        onHomeScreen = { on ->
                            HomeApp.setHomeScreenEnabled(this, on)
                            refreshHomeAppState()
                        },
                        onAndroidApp = { app, displayId -> launchAndroidApp(app, displayId) },
                        onBackActionsInverted = { inverted ->
                            SessionPrefs.setBackActionsInverted(this, inverted)
                            backActionsInverted = inverted
                        },
                        onCheckLatestBuild = {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/The412Banner/DroidDeck/actions/workflows/build.yml")))
                        },
                        onRefreshPhantomStatus = { refreshPhantomStatus() },
                        onOpenDeveloperOptions = { displayId -> openDeveloperOptions(displayId) },
                        onWirelessAdbPair = { host, port, code, complete ->
                            Thread({
                                val result = runCatching {
                                    kotlinx.coroutines.runBlocking { WirelessAdbFix.pair(this@MainActivity, host, port, code) }
                                }
                                val error = result.exceptionOrNull()?.let { it.localizedMessage ?: "Wireless debugging pairing failed" }
                                ui.post { complete(error) }
                            }, "wireless-adb-pair").start()
                        },
                        onFindWirelessAdbPort = { host, complete ->
                            Thread({
                                val port = WirelessAdbFix.findConnectPort(this@MainActivity, host)
                                ui.post { complete(port) }
                            }, "wireless-adb-discovery").start()
                        },
                        onWirelessAdbApply = { host, port, enabled, complete ->
                            Thread({
                                val result = runCatching { WirelessAdbFix.setChildProcessLimit(this@MainActivity, host, port, enabled) }
                                val error = result.exceptionOrNull()?.let { it.localizedMessage ?: "Wireless debugging command failed" }
                                ui.post {
                                    if (error == null) refreshPhantomStatus()
                                    complete(error)
                                }
                            }, "wireless-adb-fix").start()
                        },
                        onSetPhantomProcessLimit = { enabled, complete ->
                            Thread({
                                val result = runCatching { WirelessAdbFix.setUsingSavedPairing(this@MainActivity, enabled) }
                                val error = result.exceptionOrNull()?.let { it.localizedMessage ?: "Wireless debugging is unavailable" }
                                ui.post {
                                    if (error == null) refreshPhantomStatus()
                                    complete(error)
                                }
                            }, "wireless-adb-setting").start()
                        },
                        onCopyPhantomCommand = { enabled ->
                            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                                ClipData.newPlainText("DroidDeck child-process setting", PhantomProcessLimit.adbCommand(enabled)),
                            )
                            android.widget.Toast.makeText(this, "ADB command copied", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        onDismissPhantomGate = { showPhantomGate = false },
                        onStartWirelessAdbPairing = { WirelessAdbPairingService.start(this) },
                        onOpenNotificationSettings = {
                            startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        },
                        controller = ControllerActions(
                            onOsc = { o -> SessionPrefs.setOscMode(this, o); oscMode = o },
                            onTint = { t -> ControllerPrefs.setTint(this, t); refreshController() },
                            onOpacity = { o -> ControllerPrefs.setOpacity(this, o); refreshController() },
                            onSize = { v -> ControllerPrefs.setSize(this, v); refreshController() },
                            onStickClick = { on -> ControllerPrefs.setStickClick(this, on); refreshController() },
                            onAdaptiveSticks = { on -> ControllerPrefs.setAdaptiveSticks(this, on); refreshController() },
                            onEditLayout = { startActivity(Intent(this, ControllerEditorActivity::class.java)) },
                            onResetLayout = { ControllerPrefs.resetAllLayouts(this); refreshController() },
                            onMapping = { settingsMode = null; showPerformance = false; showProtons = false; showComponents = false; showMapping = true },
                            onResetAll = { ControllerPrefs.resetAll(this); refreshController() },
                        ),
                    ),
                    page = page,
                )
                }
                if (showRoms) RomsDialog(
                    path = romsDir,
                    onChoose = {
                        showRoms = false
                        pickRomsDir.launch(InAppFilePicker.buildDirIntent(this, "Choose the ROMs folder", romsDir))
                    },
                    onClear = { SessionPrefs.setRomsDir(this, ""); romsDir = null; showRoms = false },
                    onDismiss = { showRoms = false },
                )
                showNonAdreno?.let { release ->
                    ConfirmDialog(
                        title = "Not an Adreno GPU",
                        text = "Turnip supports Adreno GPUs. On ${com.droiddeck.launcher.core.DeviceSupport.gpuName()}, Steam may show a black screen. Download: ${"%.0f".format(release.size / 1e6)} MB.",
                        confirm = "Install anyway",
                        onConfirm = { showNonAdreno = null; install(release) },
                        onDismiss = { showNonAdreno = null },
                    )
                }
                if (showRemove) ConfirmDialog(
                    title = "Remove Linux runtime",
                    text = "This deletes the runtime, the Steam client inside it, and every game installed there.",
                    confirm = "Remove",
                    onConfirm = { Thread({ LinuxRuntimeInstaller.uninstall(this); ui.post { refresh() } }, "uninstall").start() },
                    onDismiss = { showRemove = false },
                )
                if (showCredits) CreditsDialog { showCredits = false }
                flood?.let { f -> com.droiddeck.launcher.ui.LaunchFlood(f.from, onProgress = { floodProgress = it }) { launchFlooded(f) } }
                returning?.let { r ->
                    com.droiddeck.launcher.ui.FloodReturn(androidx.compose.ui.graphics.Color(r.color), r.to, onProgress = { floodProgress = it }) {
                        returning = null
                        floodProgress = 0f
                        com.droiddeck.launcher.ui.LaunchOrigin.flooding = null
                    }
                }
            }
        }

        if (savedInstanceState == null) ui.post { startSteamAtStartupIfEnabled() }

        // The session's logs land in Downloads so a failed run can be handed over as a folder
        // rather than dug out of app-private storage. targetSdk 28 means the old permission still
        // grants exactly that.
        val wanted = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            wanted.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        // The microphone is off by default. Someone who turned it on before the permission was
        // granted is asked once here, with the storage prompt; a refusal is not asked again - the
        // toggle asks when used.
        if (SessionPrefs.micEnabled(this) && !SessionPrefs.micAsked(this)
            && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            wanted.add(Manifest.permission.RECORD_AUDIO)
            SessionPrefs.setMicAsked(this)
        }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
        // A session folder left without its ending - the process was killed - gets it now.
        if (!SessionState.running) Thread({
            SessionArtifacts.finishAbandoned(this)
            SessionArtifacts.scrubOlder(this)
        }, "finish-abandoned").start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        startSteamAtStartupIfEnabled()
    }

    private fun startSteamAtStartupIfEnabled() {
        if (SessionPrefs.runSteamAtStartup(this) && !isFinishing && !isDestroyed) startSteamSession()
    }

    private fun startSteamSession() {
        startSession(Intent(this, SessionActivity::class.java), steamSession = true)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyLauncherFullscreen()
    }

    private fun applyLauncherFullscreen() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (launcherFullscreen) {
            (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        } else {
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    /** On the Components page the pad's LB / RB step through FEX, DXVK and VKD3D-Proton, wrapping around. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (showComponents && (event.keyCode == KeyEvent.KEYCODE_BUTTON_L1 || event.keyCode == KeyEvent.KEYCODE_BUTTON_R1)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val all = ComponentsManager.COMPONENTS
                val step = if (event.keyCode == KeyEvent.KEYCODE_BUTTON_R1) 1 else all.size - 1
                components.compComp = all[(all.indexOf(components.compComp).coerceAtLeast(0) + step) % all.size]
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        com.droiddeck.launcher.ui.Motion.refresh(this)
        // Back from a session stopped behind a flood: open on its blue, before the first frame.
        com.droiddeck.launcher.ui.QuitFlood.take()?.let { c ->
            returning = ReturningFlood(c, com.droiddeck.launcher.ui.LaunchOrigin.takeReturn())
        }
        refreshPhantomStatus()
        // Swaps queued while a game ran on that Proton go in once nothing uses it (usually the
        // session has just ended). Cheap when nothing is queued.
        if (!SessionState.running) Thread({
            val applied = runCatching { ComponentsManager.applyQueued(this) }.getOrDefault(emptyList())
            if (applied.isNotEmpty()) ui.post {
                android.widget.Toast.makeText(this, "Applied: " + applied.joinToString(", "), android.widget.Toast.LENGTH_LONG).show()
                if (showComponents) components.refreshComponents()
            }
        }, "components-queue").start()
        oscMode = SessionPrefs.oscMode(this)
        refreshController()
        refreshHomeAppState()
        refreshSecondScreenDisplays()
        refresh()
        decky.deckyInstalled = DeckyManager.installed(this)
        DeckyManager.syncCefMarker(this)
        decky.deckySupervisor = DeckyManager.supervisorEnabled(this)
        // Added games' art (a store lookup for what the folders lack) starts here, not only when
        // the cog opens.
        refreshAddedGames()
        if (catalog == null) loadDesktopCatalog()
        if (!busy) Thread({ checkCatalog() }, "catalog").start()
    }

    override fun onStart() {
        super.onStart()
        displayManager.registerDisplayListener(secondScreenDisplayListener, ui)
        refreshSecondScreenDisplays()
    }

    override fun onStop() {
        displayManager.unregisterDisplayListener(secondScreenDisplayListener)
        // The session covers the page by now; coming back finds it as it was.
        flood = null
        returning = null
        floodProgress = 0f
        com.droiddeck.launcher.ui.LaunchOrigin.flooding = null
        super.onStop()
    }

    private fun refreshHomeAppState() {
        homeScreenEnabled = HomeApp.isHomeScreenEnabled(this)
        homeAppSelected = homeScreenEnabled && HomeApp.isDefault(this)
        defaultHomeLabel = HomeApp.defaultLabel(this)
        androidApps = if (homeAppSelected) HomeApp.launchableApps(this) else emptyList()
    }

    private fun refreshSecondScreenDisplays() {
        if (!::displayManager.isInitialized) return
        secondScreenDisplays = SecondScreenDisplays.available(displayManager)
    }

    private fun launchAndroidApp(app: HomeApp.LaunchableApp, displayId: Int?) {
        try {
            HomeApp.launch(this, app, displayId)
        } catch (_: Exception) {
            val target = if (displayId == null || displayId == Display.DEFAULT_DISPLAY) "the primary screen"
                else secondScreenDisplays.firstOrNull { it.id == displayId }?.label ?: "display $displayId"
            android.widget.Toast.makeText(this, "Could not open ${app.label} on $target", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun manageHomeApp() {
        val request = HomeApp.roleRequestIntent(this)
        if (request != null) {
            homeRoleRequest.launch(request)
        } else {
            HomeApp.openSystemHomeSettings(this)
        }
    }

    private fun loadDesktopCatalog() {
        if (catalogLoading || catalog != null) return
        catalogLoading = true
        Thread({
            val fetched = DesktopCatalog.fetch()
            ui.post {
                catalog = fetched
                catalogLoading = false
                refreshPackages()
            }
        }, "catalog-desktop").start()
    }

    private fun openProtons() {
        settingsMode = null
        showPerformance = false
        showComponents = false
        showMapping = false
        showProtons = true
        protons.refreshProtons()
    }

    private fun refreshController() {
        controllerSettings = ControllerPrefs.read(this)
    }

    @Composable
    private fun MappingHost() {
        val settings = controllerSettings ?: return
        ControllerMappingPage(
            mapping = settings.mapping,
            onPick = { id, target -> ControllerPrefs.setTarget(this, id, target); refreshController() },
            onReset = { ControllerPrefs.resetMapping(this); refreshController() },
            onBack = { showMapping = false },
        )
    }

    private fun openComponents(focusContent: Boolean = true) {
        focusComponentsContent = focusContent
        settingsMode = null
        showPerformance = false
        showProtons = false
        showMapping = false
        showComponents = true
        components.refreshComponents(snapshotFirst = true)
    }

    private fun importComponent(uri: Uri) {
        val name = displayNameOf(uri) ?: "imported.wcp"
        components.componentAction("Importing") {
            val tmp = File(cacheDir, "component-import.wcp")
            contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } } ?: error("cannot read the file")
            try { "Imported ${ComponentsManager.importPackage(this, tmp, name).version}" } finally { tmp.delete() }
        }
    }

    @Composable
    private fun ComponentsHost() {
        ComponentsPage(
            snapshot = components.compSnapshot,
            catalog = components.compCatalog,
            catalogAt = components.compCatalogAt,
            protonId = components.compProton,
            comp = components.compComp,
            checking = components.compChecking,
            busy = components.compBusy,
            downloads = components.compDownloads,
            requestInitialFocus = focusComponentsContent,
            onProton = { components.compProton = it },
            onComp = { components.compComp = it },
            onSwap = { file -> components.compProton?.let { pid -> components.componentAction("Swapping") { ComponentsManager.swap(this, pid, file) } } },
            onRestore = { version -> components.compProton?.let { pid -> components.componentAction("Restoring") { ComponentsManager.restore(this, pid, components.compComp, version) } } },
            onCancelQueued = { components.compProton?.let { pid -> components.componentAction("Cancelling") { ComponentsManager.cancelQueued(this, pid, components.compComp); "The waiting swap was cancelled." } } },
            onDeletePackage = { file -> components.componentAction("Deleting") { ComponentsManager.deletePackage(this, file) } },
            onDeleteOriginal = { version -> components.compProton?.let { pid -> components.componentAction("Deleting") { ComponentsManager.deleteOriginal(this, pid, components.compComp, version) } } },
            onDownload = { components.downloadComponent(it) },
            onRefresh = { components.refreshComponentCatalog() },
            onImport = { pickComponent.launch(InAppFilePicker.buildIntent(this, WCP_EXT, "Choose a component package (-linux .wcp)")) },
            onBack = { showComponents = false },
        )
    }

    @Composable
    private fun ProtonHost() {
        ProtonPage(
            rows = protons.protonRows,
            busyId = protons.protonBusyId,
            stage = protons.protonStage,
            percent = protons.protonPercent,
            runtimeReady = ready,
            sessionRunning = SessionState.running,
            onInstall = { id -> protons.installProton(id) },
            onCancel = { id -> ProtonExtras.tools.firstOrNull { it.id == id }?.let { ProtonExtras.unqueue(this, it) }; protons.refreshProtons() },
            onRemove = { id -> protons.removeProton(id) },
            onBack = { showProtons = false },
        )
    }

    private fun refreshPackages() {
        packageRows = catalog?.map { PackageRow(it.id, it.kind, it.notes) }
        desktopInstalled = DesktopCatalog.desktopInstalled(this)
    }

    private fun launchProgram(path: String) {
        startActivity(Intent(this, SessionActivity::class.java)
            .putExtra(SessionService.EXTRA_MODE, SessionService.MODE_RUN)
            .putExtra(SessionService.EXTRA_PROGRAM, path))
    }

    private fun installPackage(id: String) {
        val entry = catalog?.firstOrNull { it.id == id } ?: return
        if (pkgStage != null || SessionState.running) return
        pkgId = id; pkgStage = "Starting…"; pkgPercent = -1
        Thread({
            val problem = DesktopCatalog.install(this, entry) { stage, percent ->
                ui.post { pkgStage = stage; pkgPercent = percent }
            }
            ui.post {
                pkgStage = null; pkgId = null
                if (problem != null) android.widget.Toast.makeText(this, "${entry.name}: $problem", android.widget.Toast.LENGTH_LONG).show()
                refreshPackages()
                refresh()
            }
        }, "install-pkg").start()
    }

    private fun removePackage(id: String) {
        val entry = catalog?.firstOrNull { it.id == id } ?: return
        if (pkgStage != null || SessionState.running) return
        pkgId = id; pkgStage = if (entry.kind == "appimage") "Removing ${entry.name}…" else "Forgetting ${entry.name}…"; pkgPercent = -1
        Thread({
            DesktopCatalog.remove(this, entry)
            ui.post {
                pkgStage = null; pkgId = null
                refreshPackages()
                refresh()
            }
        }, "remove-pkg").start()
    }

    @Composable
    private fun ModeSettingsHost(mode: String) {
        ModeSettingsPage(
            ModeSettings(
                mode = mode, resolutionCap = resolutionCap, customResolution = customResolution, shapeMode = shapeMode,
                hdr = hdrOn, hdrReason = hdrReason,
                linuxRows = drivers.linuxRows,
                linuxSelected = if (mode == SessionService.MODE_STEAM) drivers.linuxSteam else drivers.linuxDesktop,
                androidRows = drivers.androidRows, androidSelected = drivers.androidSelected,
                touchMode = touchMode,
                suspendPolicy = suspendPolicy,
                oscMode = if (mode == SessionService.MODE_STEAM) oscMode else null,
                backActionsInverted = backActionsInverted,
                directAudio = if (mode == SessionService.MODE_STEAM) directAudio else null,
                clientDirectAudio = clientDirectAudio,
                forceFullscreen = if (mode == SessionService.MODE_STEAM) forceFullscreen else null,
                mic = if (mode == SessionService.MODE_STEAM) mic else null,
                renderer = if (mode == SessionService.MODE_DESKTOP) renderer else null,
                gameStorage = if (mode == SessionService.MODE_STEAM) gameStorage else null,
                storageOptions = storageOptions,
                fexPreset = if (mode == SessionService.MODE_STEAM) fexPreset else null,
                steamChannel = if (mode == SessionService.MODE_STEAM) steamChannel else null,
                steamDeckMode = mode == SessionService.MODE_STEAM && steamDeckMode,
                steamController = if (mode == SessionService.MODE_STEAM) steamController else null,
                runSteamAtStartup = mode == SessionService.MODE_STEAM && runSteamAtStartup,
                addedGamesDirs = if (mode == SessionService.MODE_STEAM) addedGamesDirs else null,
                addedGames = if (mode == SessionService.MODE_STEAM) addedGames else emptyList(),
                addedGamesArt = addedGamesArt,
                linuxDownloads = drivers.linuxDownloads, androidDownloads = drivers.androidDownloads, releaseStatus = drivers.releaseStatus,
                releaseChecking = drivers.releaseChecking, canRestoreBundled = drivers.canRestoreBundled,
                deckyInstalled = if (mode == SessionService.MODE_STEAM) decky.deckyInstalled else null,
                deckyLatestRelease = if (mode == SessionService.MODE_STEAM) decky.deckyReleases.firstOrNull() else null,
                deckyChecking = decky.deckyChecking, deckyStage = decky.deckyStage, deckyPercent = decky.deckyPercent,
                deckyEnabled = decky.deckySupervisor, deckySessionRunning = SessionState.running,
            ),
            ModeSettingsActions(
                onResolution = { cap -> SessionPrefs.setResolutionCap(this, mode, cap); resolutionCap = cap },
                onCustomResolution = { size -> SessionPrefs.setCustomResolution(this, mode, size); customResolution = size },
                onShape = { shape -> SessionPrefs.setShapeMode(this, shape); shapeMode = shape },
                onHdr = { on -> SessionPrefs.setHdr(this, mode, on); hdrOn = on },
                onSelectLinux = { id -> SessionPrefs.setLinuxDriver(this, mode, id); drivers.refreshDrivers() },
                onImportLinux = { pickLinuxDriver.launch(InAppFilePicker.buildIntent(this, ZIP_EXT, "Choose a Linux runtime driver (-Linux zip)")) },
                onRemoveLinux = { id -> drivers.deleteDriver(id, linux = true) },
                onRefreshReleases = { drivers.checkLatestTurnip() },
                onDownloadDriver = { name -> drivers.downloadReleaseDriver(name) },
                onRestoreBundled = { TurnipDriver(this).restoreBundled(); drivers.refreshDrivers() },
                onSelectAndroid = { id -> SessionPrefs.setAndroidDriver(this, id); drivers.refreshDrivers() },
                onImportAndroid = { pickAndroidDriver.launch(InAppFilePicker.buildIntent(this, ZIP_EXT, "Choose a display driver (AdrenoTools zip)")) },
                onRemoveAndroid = { id -> drivers.deleteDriver(id, linux = false) },
                onTouch = { t -> SessionPrefs.setTouchMode(this, t); touchMode = t },
                onSuspendPolicy = { policy -> SessionPrefs.setSuspendPolicy(this, mode, policy); suspendPolicy = policy },
                onOsc = { o -> SessionPrefs.setOscMode(this, o); oscMode = o },
                onBackActionsInverted = { inverted ->
                    SessionPrefs.setBackActionsInverted(this, inverted)
                    backActionsInverted = inverted
                },
                onDirectAudio = { on -> SessionPrefs.setDirectAudio(this, on); directAudio = on },
                onClientDirectAudio = { on -> SessionPrefs.setClientDirectAudio(this, on); clientDirectAudio = on },
                onForceFullscreen = { on -> SessionPrefs.setForceFullscreen(this, on); forceFullscreen = on },
                onMic = { on ->
                    SessionPrefs.setMicEnabled(this, on)
                    mic = on
                    // The session checks the grant itself at start; asking here means the
                    // answer is in before the first session that wants it.
                    if (on && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
                    }
                },
                onRenderer = { r -> SessionPrefs.setDesktopRenderer(this, r); renderer = r },
                onGameStorage = { path, label -> setGameStorage(path, label) },
                onPickGameStorageFolder = {
                    pickGameStorage.launch(InAppFilePicker.buildDirIntent(this, "Choose the game storage folder", gameStorage.ifEmpty { null }))
                },
                onFexPreset = { id -> SessionPrefs.setFexPreset(this, id); fexPreset = id },
                onSteamChannel = { id -> SessionPrefs.setSteamChannel(this, id); steamChannel = id },
                onSteamDeckMode = { on ->
                    SessionPrefs.setSteamDeckMode(this, on)
                    steamDeckMode = on
                    steamChannel = SessionPrefs.steamChannel(this)
                },
                onSteamController = { id -> SessionPrefs.setSteamController(this, id); steamController = id },
                onRunSteamAtStartup = { on ->
                    SessionPrefs.setRunSteamAtStartup(this, on)
                    runSteamAtStartup = on
                },
                onPickAddedGamesDir = { pickAddedGamesDir.launch(InAppFilePicker.buildDirIntent(this, "Choose a folder of your own games", addedGamesDirs.lastOrNull())) },
                onAddedGamesArt = { on -> SessionPrefs.setAddedGamesArt(this, on); addedGamesArt = on; if (on) refreshAddedGames() },
                onForgetAddedGamesDir = { dir -> SessionPrefs.setAddedGamesDirs(this, addedGamesDirs - dir); addedGamesDirs = SessionPrefs.addedGamesDirs(this); refreshAddedGames(); refresh() },
                onAddedGameExe = { folder, path -> SessionPrefs.setAddedGameExe(this, folder, path); refreshAddedGames(); refresh() },
                onPickAddedGameExe = { folder ->
                    pendingAddedGame = folder
                    pickAddedGameExe.launch(InAppFilePicker.buildIntent(this, listOf("exe"), "Choose the game's .exe", folder))
                },
                onDeckyInstall = { release -> decky.installDecky(release) },
                onDeckyCheck = { decky.refreshDecky() },
                onDeckyEnabled = { enabled -> DeckyManager.setSupervisorEnabled(this, enabled); decky.deckySupervisor = enabled },
                onDeckyUninstall = {
                    DeckyManager.uninstall(this, wipeData = false)
                    decky.deckyInstalled = null
                    decky.deckySupervisor = false
                },
                onDismiss = { settingsMode = null },
            ),
        )
    }

    @Composable
    private fun PerformanceHost() {
        PerformancePage(
            cores = CpuCores.all.map { c -> CoreRow(c, "cpu$c" + (CpuCores.maxGhz(c)?.let { String.format(java.util.Locale.US, " · %.1f GHz", it) } ?: "")) },
            clientOverride = clientOverride, clientCores = clientCores, gameCores = gameCores,
            tuSysmem = tuSysmem, zinkLazy = zinkLazy, glThread = glThread, noGlError = noGlError, noXalia = noXalia,
            gamescopeRealtime = gamescopeRealtime,
            prootNoSeccomp = prootNoSeccomp, guestHostname = guestHostname, phantomWarning = phantomWarning,
            onClientOverride = { on -> SessionPrefs.setClientCpusOverride(this, on); clientOverride = on },
            onTuSysmem = { on -> SessionPrefs.setTuSysmem(this, on); tuSysmem = on },
            onZinkLazy = { on -> SessionPrefs.setZinkLazy(this, on); zinkLazy = on },
            onGlThread = { on -> SessionPrefs.setGlThread(this, on); glThread = on },
            onNoGlError = { on -> SessionPrefs.setNoGlError(this, on); noGlError = on },
            onNoXalia = { on -> SessionPrefs.setNoXalia(this, on); noXalia = on },
            onGamescopeRealtime = { on -> SessionPrefs.setGamescopeRealtime(this, on); gamescopeRealtime = on },
            onProotNoSeccomp = { on -> SessionPrefs.setProotNoSeccomp(this, on); prootNoSeccomp = on },
            onGuestHostname = { name -> SessionPrefs.setGuestHostname(this, name) },
            onClientCore = { core, on ->
                clientCores = if (on) clientCores + core else clientCores - core
                SessionPrefs.setClientCpus(this, CpuCores.format(clientCores))
            },
            onGameCore = { core, on ->
                gameCores = if (on) gameCores + core else gameCores - core
                SessionPrefs.setGameCpus(this, CpuCores.format(gameCores))
            },
            onDismiss = { showPerformance = false },
        )
    }

    /** The added games as the settings page lists them; a scan of the folder, on this thread (one level, small). */
    private fun refreshAddedGames() {
        addedGames = com.droiddeck.launcher.frontend.AddedGames.scan(this).map { g ->
            com.droiddeck.launcher.ui.AddedGameRow(g.folder.path, g.folderName(), g.exe.path, g.exe.name, g.candidates.map { c -> c.path to c.name }.distinctBy { it.first })
        }
        // Art the games do not have yet, from Steam's store, off the main thread; the rail
        // redraws when something arrives.
        if (SessionPrefs.addedGamesArt(this) && !artFetchRunning) {
            artFetchRunning = true
            Thread({
                try {
                    val games = com.droiddeck.launcher.frontend.AddedGames.scan(this)
                    if (com.droiddeck.launcher.frontend.AddedGameArt.fetchMissing(this, games)) ui.post { refresh() }
                } finally {
                    artFetchRunning = false
                }
            }, "added-art").start()
        }
    }

    private fun openModeSettings(mode: String) {
        showPerformance = false
        showProtons = false
        showComponents = false
        showMapping = false
        drivers.refreshDrivers()
        resolutionCap = SessionPrefs.resolutionCap(this, mode)
        customResolution = SessionPrefs.customResolution(this, mode)
        fexPreset = SessionPrefs.fexPreset(this)
        steamChannel = SessionPrefs.steamChannel(this)
        steamDeckMode = SessionPrefs.steamDeckMode(this)
        steamController = SessionPrefs.steamController(this)
        runSteamAtStartup = SessionPrefs.runSteamAtStartup(this)
        addedGamesDirs = SessionPrefs.addedGamesDirs(this)
        refreshAddedGames()
        shapeMode = SessionPrefs.shapeMode(this)
        hdrOn = SessionPrefs.hdr(this, mode)
        hdrReason = com.droiddeck.launcher.wayland.HdrSupport.probe(this).reason
        touchMode = SessionPrefs.touchMode(this)
        suspendPolicy = SessionPrefs.suspendPolicy(this, mode)
        oscMode = SessionPrefs.oscMode(this)
        backActionsInverted = SessionPrefs.backActionsInverted(this)
        directAudio = SessionPrefs.directAudio(this)
        clientDirectAudio = SessionPrefs.clientDirectAudio(this)
        forceFullscreen = SessionPrefs.forceFullscreen(this)
        mic = SessionPrefs.micEnabled(this)
        renderer = SessionPrefs.desktopRenderer(this)
        gameStorage = SessionPrefs.gameStorage(this)
        storageOptions = GameStorage.options(this).map { it.label to it.path }
        if (mode == SessionService.MODE_STEAM) {
            decky.deckyInstalled = DeckyManager.installed(this)
            decky.deckySupervisor = DeckyManager.supervisorEnabled(this)
            decky.refreshDecky()
        }
        settingsMode = mode
    }

    /** A second Steam library, proven writable first; "" = internal only. */
    private fun setGameStorage(path: String, label: String) {
        if (path.isNotEmpty() && path != SessionPrefs.GAME_STORAGE_OFF) {
            val problem = GameStorage.prepare(path)
            if (problem != null) {
                android.widget.Toast.makeText(this, "Not usable: $problem", android.widget.Toast.LENGTH_LONG).show()
                return
            }
        }
        SessionPrefs.setGameStorage(this, path, label)
        gameStorage = path
    }

    /** The two masks as the dialog shows them; an empty stored list shows as every core ticked. */
    private fun refreshCores() {
        clientOverride = SessionPrefs.clientCpusOverride(this)
        glThread = SessionPrefs.glThread(this)
        noGlError = SessionPrefs.noGlError(this)
        steamDeckMode = SessionPrefs.steamDeckMode(this)
        clientCores = CpuCores.parse(SessionPrefs.clientCpus(this)).ifEmpty { CpuCores.all.toSet() }
        gameCores = CpuCores.parse(SessionPrefs.gameCpus(this)).ifEmpty { CpuCores.all.toSet() }
        tuSysmem = SessionPrefs.tuSysmem(this)
        zinkLazy = SessionPrefs.zinkLazy(this)
        noXalia = SessionPrefs.noXalia(this)
        gamescopeRealtime = SessionPrefs.gamescopeRealtime(this)
        prootNoSeccomp = SessionPrefs.prootNoSeccomp(this)
        guestHostname = SessionPrefs.guestHostname(this)
        refreshPhantomStatus()
    }

    private fun refresh() {
        if (!busy && LinuxRuntimeInstaller.isInstalling()) followInstall { LinuxRuntimeInstaller.attach(it) }
        desktopInstalled = DesktopCatalog.desktopInstalled(this)
        offlineAccount = OfflineMode.account(this)
        offline = OfflineMode.enabled(this)
        installed = LinuxRuntimeInstaller.installedVersion(this)
        ready = LinuxRuntime.isInstalled(this)
        frameGenLabel = FrameGen.label(this)
        romsDir = SessionPrefs.romsDir(this).takeIf { it.isNotEmpty() }
        logsEnabled = SessionPrefs.logsEnabled(this)
        runningLabel = if (SessionState.running) when (SessionState.mode) {
            SessionService.MODE_DESKTOP -> "Desktop"
            SessionService.MODE_RUN -> SessionState.program?.substringAfterLast('/')?.substringBefore('.') ?: "Program"
            else -> "Steam"
        } else null
        // The libraries, off the main thread: manifests and a folder scan.
        Thread({
            val games = if (ready) Library.steamGames(this) + com.droiddeck.launcher.frontend.AddedGames.scan(this).map { g ->
                com.droiddeck.launcher.frontend.AddedGameArt.resolve(this, g).let { art ->
                    Library.SteamGame(
                        g.steamAppId ?: g.appId.toInt(), g.name, art.portrait ?: art.header, "added", g.gameId,
                        hero = art.hero ?: art.header, gameFiles = g.folder,
                        protonPrefix = Library.protonPrefix(this, g.steamAppId?.toLong() ?: g.appId),
                    )
                }
            } else emptyList()
            val emus = Library.emulators(this) { id -> DesktopCatalog.installed(this, id) != null }
            ui.post { steamGames = games.distinctBy { it.gameId }; emulatorList = emus }
            // Box art for the games that have none, fetched after the list is up; the list is
            // rebuilt once if any was found.
            if (!OfflineMode.enabled(this) && CoverArt.fetchMissing(this, emus.flatMap { it.games })) {
                val refreshed = Library.emulators(this) { id -> DesktopCatalog.installed(this, id) != null }
                ui.post { emulatorList = refreshed }
            }
        }, "library").start()
    }

    private fun onRuntimeButton() {
        if (busy) return
        val release = available
        if (installed != null && release?.version == installed) {
            // Nothing to install: offer the one destructive thing this screen can do.
            showRemove = true
            return
        }
        if (release == null) {
            Thread({ checkCatalog() }, "catalog").start()
            return
        }
        // The runtime draws with Turnip, an Adreno driver: on Mali, Xclipse or PowerVR the
        // compositor gets no usable Vulkan device and a session is sound over a black screen.
        // Said before the download, not after it; the user may still go ahead.
        if (installed == null && !com.droiddeck.launcher.core.DeviceSupport.adreno()) { showNonAdreno = release; return }
        install(release)
    }

    /** Starts a session; with no runtime on a non-Adreno, the same warning Setup gives comes first, before any download. */
    private fun startSession(intent: Intent, steamSession: Boolean = false) {
        refreshPhantomStatus()
        if (steamSession && PhantomProcessLimit.blocksSteam(phantomProcessStatus)) {
            showPhantomGate = true
            return
        }
        val warn = installed == null && !com.droiddeck.launcher.core.DeviceSupport.adreno()
        if (warn && available != null) showNonAdreno = available
        else startActivity(intent)
    }

    private fun refreshPhantomStatus() {
        phantomProcessStatus = PhantomProcessLimit.read(this)
        phantomWarning = if (PhantomProcessLimit.blocksSteam(phantomProcessStatus)) {
            "${PhantomProcessLimit.title(phantomProcessStatus)}. ${PhantomProcessLimit.instructions(phantomProcessStatus)}\n\n${PhantomProcessLimit.adbCommand()}"
        } else null
    }

    private fun openDeveloperOptions(displayId: Int?) {
        val highlight = if (PhantomProcessLimit.hasDeveloperToggle() &&
            WirelessAdbPairingService.stage.value == WirelessAdbPairingService.Stage.Idle) null else "toggle_adb_wireless"
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply { if (highlight != null) putExtra(":settings:fragment_args_key", highlight) }
        try {
            if (displayId == null) startActivity(intent)
            else startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
        } catch (error: Exception) {
            if (displayId != null) {
                runCatching { startActivity(intent) }
                android.widget.Toast.makeText(this, "Could not open Settings on the bottom screen; opened it on the main screen.", android.widget.Toast.LENGTH_LONG).show()
            } else {
                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                android.widget.Toast.makeText(this, "Open Developer options in Settings", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun install(release: LinuxRuntimeInstaller.Release) {
        // The service keeps the process alive if the user switches away; this screen joins the
        // same install (or starts it, if it gets there first) to show the progress.
        com.droiddeck.launcher.runtime.RuntimeInstallService.start(this, release)
        followInstall { listener -> LinuxRuntimeInstaller.install(this, release, listener) }
    }

    /**
     * Shows an install's progress until it ends. [run] either starts one or joins the one already
     * running (null: nothing was), which is how a launcher rebuilt mid-install picks it back up.
     */
    private fun followInstall(run: (LinuxRuntimeInstaller.ProgressListener) -> Boolean?) {
        busy = true
        failed = false
        stage = "Starting…"
        percent = -1
        Thread({
            val ok = run(LinuxRuntimeInstaller.ProgressListener { s, p ->
                ui.post { stage = s; percent = p }
            })
            ui.post {
                busy = false
                if (ok != null) failed = !ok
                refresh()
            }
        }, "install").start()
    }

    private fun checkCatalog() {
        val release = LinuxRuntimeInstaller.fetchRelease()
        Log.i(TAG, "catalog: " + (release?.version ?: "unreachable"))
        ui.post { if (release != null) available = release }
    }

    companion object {
        private const val TAG = "MainActivity"
        /** What the picker offers for a driver zip; some file apps label a zip as a plain stream. */
        private val ZIP_EXT = listOf("zip")
        private val WCP_EXT = listOf("wcp")
    }
}
