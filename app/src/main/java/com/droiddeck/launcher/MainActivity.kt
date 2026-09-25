package com.droiddeck.launcher

import android.Manifest
import android.content.Intent
import java.io.File
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.gpu.FrameGen
import com.droiddeck.launcher.gpu.LinuxVulkanDriver
import com.droiddeck.launcher.gpu.LinuxVulkanDriverManager
import com.droiddeck.launcher.gpu.TurnipDriver
import com.droiddeck.launcher.gpu.TurnipReleases
import com.droiddeck.launcher.gpu.LsfgNative
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.runtime.DesktopCatalog
import com.droiddeck.launcher.runtime.LinuxRuntimeInstaller
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.ui.PackageRow
import com.droiddeck.launcher.session.OfflineMode
import com.droiddeck.launcher.session.ProtonExtras
import com.droiddeck.launcher.session.SessionLogShare
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.ui.ProtonPage
import com.droiddeck.launcher.ui.ProtonRow
import com.droiddeck.launcher.core.CpuCores
import com.droiddeck.launcher.ui.CoreRow
import com.droiddeck.launcher.ui.PerformancePage
import com.droiddeck.launcher.ui.ModeSettingsPage
import com.droiddeck.launcher.ui.ModeSettings
import com.droiddeck.launcher.ui.ModeSettingsActions
import com.droiddeck.launcher.ui.DriverRow
import com.droiddeck.launcher.ui.ConfirmDialog
import com.droiddeck.launcher.ui.CreditsDialog
import com.droiddeck.launcher.ui.FrontEndScreen
import com.droiddeck.launcher.ui.FrontEndState
import com.droiddeck.launcher.ui.FrontEndActions
import com.droiddeck.launcher.frontend.CoverArt
import com.droiddeck.launcher.frontend.SteamArt
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

    // The screen's state. Compose redraws whatever reads these when they change.
    private var installed by mutableStateOf<String?>(null)
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
    private var showCredits by mutableStateOf(false)
    private var showProtons by mutableStateOf(false)
    private var catalog by mutableStateOf<List<DesktopCatalog.Entry>?>(null)
    private var catalogLoading by mutableStateOf(false)
    private var packageRows by mutableStateOf<List<PackageRow>?>(null)
    private var pkgId by mutableStateOf<String?>(null)
    private var pkgStage by mutableStateOf<String?>(null)
    private var pkgPercent by mutableIntStateOf(-1)
    private var desktopInstalled by mutableStateOf(false)
    private var offlineAccount by mutableStateOf<String?>(null)
    private var offline by mutableStateOf(false)
    private var protonRows by mutableStateOf<List<ProtonRow>>(emptyList())
    private var protonBusyId by mutableStateOf<String?>(null)
    private var protonStage by mutableStateOf<String?>(null)
    private var protonPercent by mutableIntStateOf(-1)
    private var showPerformance by mutableStateOf(false)
    private var clientOverride by mutableStateOf(false)
    private var clientCores by mutableStateOf<Set<Int>>(emptySet())
    private var gameCores by mutableStateOf<Set<Int>>(emptySet())
    private var tuSysmem by mutableStateOf(false)
    private var zinkLazy by mutableStateOf(false)
    private var noXalia by mutableStateOf(false)
    private var prootNoSeccomp by mutableStateOf(false)
    private var phantomWarning by mutableStateOf<String?>(null)
    private var directAudio by mutableStateOf(false)
    private var clientDirectAudio by mutableStateOf(false)
    private var forceFullscreen by mutableStateOf(true)
    private var mic by mutableStateOf(false)
    private var linuxRows by mutableStateOf<List<DriverRow>>(emptyList())
    /** The latest Banners-Turnip release as each driver menu offers it (see [refreshReleaseRows]). */
    private var linuxDownloads by mutableStateOf<List<com.droiddeck.launcher.ui.DownloadRow>>(emptyList())
    private var androidDownloads by mutableStateOf<List<com.droiddeck.launcher.ui.DownloadRow>>(emptyList())
    private var releaseStatus by mutableStateOf("Not checked yet - tap refresh to look for new drivers")
    private var releaseChecking by mutableStateOf(false)
    private var canRestoreBundled by mutableStateOf(false)
    /** Asset name -> download percent, while it downloads. */
    private val releaseProgress = HashMap<String, Int>()
    private var linuxSteam by mutableStateOf("")
    private var linuxDesktop by mutableStateOf("")
    private var androidRows by mutableStateOf<List<DriverRow>>(emptyList())
    private var androidSelected by mutableStateOf("")

    // The app's own picker (files/), once per kind of pick: the two driver lists validate
    // differently, and the reason a zip is refused names the list it belongs in.
    private val pickLinuxDriver = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedUri(r.data)?.let { importDriver(it, linux = true) }
    }
    private val pickAndroidDriver = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedUri(r.data)?.let { importDriver(it, linux = false) }
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
    private var theme by mutableStateOf("paper")
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
                protonBusyId != null || ProtonExtras.installInProgress -> {
                    android.widget.Toast.makeText(this, "Wait for the compatibility tool install to finish", android.widget.Toast.LENGTH_SHORT).show()
                    return
                }
                pkgStage != null -> {
                    android.widget.Toast.makeText(this, "Wait for the desktop app install to finish", android.widget.Toast.LENGTH_SHORT).show()
                    return
                }
            }
        }
        super.startActivity(intent)
        if (intent?.component?.className == SessionActivity::class.java.name) overridePendingTransition(R.anim.session_rise, R.anim.session_hold)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        theme = SessionPrefs.theme(this)
        backActionsInverted = SessionPrefs.backActionsInverted(this)
        setContent {
            DroidDeckTheme(theme) {
                val sm = settingsMode
                val page: (@Composable () -> Unit)? = when {
                    sm != null -> { { ModeSettingsHost(sm) } }
                    showPerformance -> { { PerformanceHost() } }
                    showProtons -> { { ProtonHost() } }
                    else -> null
                }
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
                        pageKey = sm?.let { "settings:$it" } ?: if (showPerformance) "performance" else if (showProtons) "protons" else null,
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
                    ),
                    FrontEndActions(
                        onPlay = { startSession(Intent(this, SessionActivity::class.java)) },
                        onPlayDesktopUi = {
                            startSession(Intent(this, SessionActivity::class.java)
                                .putExtra(SessionService.EXTRA_STEAM_UI, "desktop"))
                        },
                        onSteamGame = { g ->
                            startActivity(Intent(this, SessionActivity::class.java)
                                .putExtra(SessionService.EXTRA_STEAM_URL, "steam://rungameid/${g.gameId}"))
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
                        onPerformance = { refreshCores(); showProtons = false; showPerformance = true },
                        onRoms = { showRoms = true },
                        onFiles = { startActivity(Intent(this, com.droiddeck.launcher.files.FileManagerActivity::class.java)) },
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
                        onPageBack = { settingsMode = null; showPerformance = false; showProtons = false },
                        onTheme = { id -> SessionPrefs.setTheme(this, id); theme = id },
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
                    ),
                    page = page,
                )
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
            }
        }

        // The session's logs land in Downloads so a failed run can be handed over as a folder
        // rather than dug out of app-private storage. targetSdk 28 means the old permission still
        // grants exactly that.
        val wanted = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            wanted.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        // The microphone is on by default; ask once, with the storage prompt, so voice chat works
        // without a trip to the settings. A refusal is not asked again - the toggle asks when used.
        if (SessionPrefs.micEnabled(this) && !SessionPrefs.micAsked(this)
            && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            wanted.add(Manifest.permission.RECORD_AUDIO)
            SessionPrefs.setMicAsked(this)
        }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
        // A session folder left without its ending - the process was killed - gets it now.
        if (!SessionState.running) Thread({ SessionArtifacts.finishAbandoned(this) }, "finish-abandoned").start()
    }

    override fun onResume() {
        super.onResume()
        refreshHomeAppState()
        refreshSecondScreenDisplays()
        refresh()
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
        showProtons = true
        refreshProtons()
    }

    @Composable
    private fun ProtonHost() {
        ProtonPage(
            rows = protonRows,
            busyId = protonBusyId,
            stage = protonStage,
            percent = protonPercent,
            runtimeReady = ready,
            sessionRunning = SessionState.running,
            onInstall = { id -> installProton(id) },
            onCancel = { id -> ProtonExtras.tools.firstOrNull { it.id == id }?.let { ProtonExtras.unqueue(this, it) }; refreshProtons() },
            onRemove = { id -> removeProton(id) },
            onBack = { showProtons = false },
        )
    }

    private fun installProton(id: String) {
        val tool = ProtonExtras.tools.firstOrNull { it.id == id } ?: return
        if (protonBusyId != null || SessionState.running) return
        ProtonExtras.unqueue(this, tool)
        protonBusyId = id
        protonStage = "Starting…"
        protonPercent = -1
        Thread({
            val problem = ProtonExtras.install(this, tool) { label, value ->
                ui.post { protonStage = label; protonPercent = value }
            }
            ui.post {
                protonBusyId = null
                protonStage = null
                protonPercent = -1
                refreshProtons()
                if (problem != null) android.widget.Toast.makeText(this, problem, android.widget.Toast.LENGTH_LONG).show()
            }
        }, "install-proton-$id").start()
    }

    private fun removeProton(id: String) {
        val tool = ProtonExtras.tools.firstOrNull { it.id == id } ?: return
        if (protonBusyId != null || SessionState.running) return
        protonBusyId = id
        protonStage = "Removing ${tool.name}…"
        protonPercent = -1
        Thread({
            ProtonExtras.remove(this, tool)
            ui.post {
                protonBusyId = null
                protonStage = null
                refreshProtons()
            }
        }, "remove-proton-$id").start()
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
                linuxRows = linuxRows,
                linuxSelected = if (mode == SessionService.MODE_STEAM) linuxSteam else linuxDesktop,
                androidRows = androidRows, androidSelected = androidSelected,
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
                addedGamesDirs = if (mode == SessionService.MODE_STEAM) addedGamesDirs else null,
                addedGames = if (mode == SessionService.MODE_STEAM) addedGames else emptyList(),
                addedGamesArt = addedGamesArt,
                linuxDownloads = linuxDownloads, androidDownloads = androidDownloads, releaseStatus = releaseStatus,
                releaseChecking = releaseChecking, canRestoreBundled = canRestoreBundled,
            ),
            ModeSettingsActions(
                onResolution = { cap -> SessionPrefs.setResolutionCap(this, mode, cap); resolutionCap = cap },
                onCustomResolution = { size -> SessionPrefs.setCustomResolution(this, mode, size); customResolution = size },
                onShape = { shape -> SessionPrefs.setShapeMode(this, shape); shapeMode = shape },
                onHdr = { on -> SessionPrefs.setHdr(this, mode, on); hdrOn = on },
                onSelectLinux = { id -> SessionPrefs.setLinuxDriver(this, mode, id); refreshDrivers() },
                onImportLinux = { pickLinuxDriver.launch(InAppFilePicker.buildIntent(this, ZIP_EXT, "Choose a Linux runtime driver (-Linux zip)")) },
                onRemoveLinux = { id -> deleteDriver(id, linux = true) },
                onRefreshReleases = { checkLatestTurnip() },
                onDownloadDriver = { name -> downloadReleaseDriver(name) },
                onRestoreBundled = { TurnipDriver(this).restoreBundled(); refreshDrivers() },
                onSelectAndroid = { id -> SessionPrefs.setAndroidDriver(this, id); refreshDrivers() },
                onImportAndroid = { pickAndroidDriver.launch(InAppFilePicker.buildIntent(this, ZIP_EXT, "Choose a display driver (AdrenoTools zip)")) },
                onRemoveAndroid = { id -> deleteDriver(id, linux = false) },
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
                onPickAddedGamesDir = { pickAddedGamesDir.launch(InAppFilePicker.buildDirIntent(this, "Choose a folder of your own games", addedGamesDirs.lastOrNull())) },
                onAddedGamesArt = { on -> SessionPrefs.setAddedGamesArt(this, on); addedGamesArt = on; if (on) refreshAddedGames() },
                onForgetAddedGamesDir = { dir -> SessionPrefs.setAddedGamesDirs(this, addedGamesDirs - dir); addedGamesDirs = SessionPrefs.addedGamesDirs(this); refreshAddedGames(); refresh() },
                onAddedGameExe = { folder, path -> SessionPrefs.setAddedGameExe(this, folder, path); refreshAddedGames(); refresh() },
                onPickAddedGameExe = { folder ->
                    pendingAddedGame = folder
                    pickAddedGameExe.launch(InAppFilePicker.buildIntent(this, listOf("exe"), "Choose the game's .exe", folder))
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
            tuSysmem = tuSysmem, zinkLazy = zinkLazy, glThread = glThread, noGlError = noGlError, steamDeckMode = steamDeckMode, noXalia = noXalia,
            prootNoSeccomp = prootNoSeccomp, phantomWarning = phantomWarning,
            onClientOverride = { on -> SessionPrefs.setClientCpusOverride(this, on); clientOverride = on },
            onTuSysmem = { on -> SessionPrefs.setTuSysmem(this, on); tuSysmem = on },
            onZinkLazy = { on -> SessionPrefs.setZinkLazy(this, on); zinkLazy = on },
            onGlThread = { on -> SessionPrefs.setGlThread(this, on); glThread = on },
            onNoGlError = { on -> SessionPrefs.setNoGlError(this, on); noGlError = on },
            onSteamDeckMode = { on -> SessionPrefs.setSteamDeckMode(this, on); steamDeckMode = on },
            onNoXalia = { on -> SessionPrefs.setNoXalia(this, on); noXalia = on },
            onProotNoSeccomp = { on -> SessionPrefs.setProotNoSeccomp(this, on); prootNoSeccomp = on },
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
        refreshDrivers()
        resolutionCap = SessionPrefs.resolutionCap(this, mode)
        customResolution = SessionPrefs.customResolution(this, mode)
        fexPreset = SessionPrefs.fexPreset(this)
        steamChannel = SessionPrefs.steamChannel(this)
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

    private fun refreshDrivers() {
        val lm = LinuxVulkanDriverManager(this)
        fun origin(id: String) = if (TurnipReleases.isDownloaded(this, id)) DriverRow.DOWNLOADED else DriverRow.IMPORTED
        linuxRows = LinuxVulkanDriver.optionValues(this).map { id ->
            if (id.isEmpty()) DriverRow("", "Runtime default", "the Turnip built into the runtime", false)
            else DriverRow(
                id, lm.getDriverName(id),
                listOfNotNull(
                    lm.getDriverVersion(id).takeIf { it.isNotEmpty() },
                    lm.getMinGlibc(id).takeIf { it.isNotEmpty() }?.let { "glibc $it+" },
                ).joinToString(" · "),
                true, origin(id),
            )
        }
        linuxSteam = SessionPrefs.linuxDriver(this, SessionService.MODE_STEAM)
        linuxDesktop = SessionPrefs.linuxDriver(this, SessionService.MODE_DESKTOP)
        val td = TurnipDriver(this)
        val auto = td.autoId()
        androidRows = buildList {
            add(DriverRow(
                TurnipDriver.AUTO, "Auto - picked by GPU",
                if (auto == "system") "system Vulkan: no bundled build for this GPU" else "${td.displayName(auto)} (bundled)",
                false,
            ))
            for (id in td.visibleBundled()) add(DriverRow(id, td.displayName(id), td.driverVersion(id), true, DriverRow.BUNDLED))
            for (id in td.enumerateImported()) add(DriverRow(id, td.displayName(id), td.driverVersion(id), true, origin(id)))
        }
        canRestoreBundled = td.hiddenBundled().isNotEmpty()
        androidSelected = SessionPrefs.androidDriver(this)
        refreshReleaseRows()
    }

    /**
     * Import off the main thread - a driver zip is a few MB and the glibc check reads the whole
     * library - then say what happened. A refusal's message is the user-facing reason.
     */
    private fun importDriver(uri: Uri, linux: Boolean) {
        val name = displayNameOf(uri)
        Thread({
            val problem = try {
                if (linux) LinuxVulkanDriverManager(this).installDriver(uri, name)
                else TurnipDriver(this).installFromZip(uri, name)
                null
            } catch (e: IllegalArgumentException) {
                e.message
            } catch (e: Exception) {
                Log.w(TAG, "driver import", e)
                "Import failed: ${e.message}"
            }
            ui.post {
                android.widget.Toast.makeText(
                    this, problem ?: "Imported ${name ?: "driver"}",
                    if (problem != null) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT,
                ).show()
                refreshDrivers()
            }
        }, "import-driver").start()
    }

    /**
     * Delete an imported or downloaded driver. A mode still set to it goes back to its default, so a
     * session never starts on a driver that is gone; a release download is forgotten, so the menu
     * offers it again.
     */
    private fun deleteDriver(id: String, linux: Boolean) {
        if (linux) {
            LinuxVulkanDriverManager(this).removeDriver(id)
            for (mode in listOf(SessionService.MODE_STEAM, SessionService.MODE_DESKTOP)) {
                if (SessionPrefs.linuxDriver(this, mode) == id) SessionPrefs.setLinuxDriver(this, mode, "")
            }
        } else {
            val td = TurnipDriver(this)
            if (id in TurnipDriver.BUNDLED) td.hideBundled(id) else td.remove(id)
            if (SessionPrefs.androidDriver(this) == id) SessionPrefs.setAndroidDriver(this, TurnipDriver.AUTO)
        }
        TurnipReleases.forget(this, id)
        android.widget.Toast.makeText(this, "Deleted ${id}", android.widget.Toast.LENGTH_SHORT).show()
        refreshDrivers()
    }

    /** The download entries and the refresh line, from what the last check found. */
    private fun refreshReleaseRows() {
        val check = TurnipReleases.cached(this)
        val lm = LinuxVulkanDriverManager(this)
        val td = TurnipDriver(this)
        fun rows(linux: Boolean) = check?.assets.orEmpty()
            .filter { it.linux == linux }
            .filter { a -> TurnipReleases.installedId(this, a) { id -> if (linux) lm.isInstalled(id) else td.isInstalled(id) } == null }
            .map { a ->
                val mb = "%.1f MB".format(a.size / 1_048_576.0)
                com.droiddeck.launcher.ui.DownloadRow(a.name, "${a.source} ${a.tag}", "${a.label} · $mb", releaseProgress[a.name])
            }
        linuxDownloads = rows(linux = true)
        androidDownloads = rows(linux = false)
        if (!releaseChecking) releaseStatus = when (check) {
            null -> "Not checked yet - tap refresh to look for new drivers"
            else -> "Latest: " + check.latest.joinToString(" · ") { "${it.first} ${it.second}" } +
                (if (check.failed.isEmpty()) "" else " · ${check.failed.joinToString()} unreachable") +
                " · checked ${ago(check.checkedAt)}"
        }
    }

    private fun ago(t: Long): String {
        val m = ((System.currentTimeMillis() - t) / 60_000).coerceAtLeast(0)
        return when {
            m < 1 -> "just now"
            m < 60 -> "$m min ago"
            m < 48 * 60 -> "${m / 60} h ago"
            else -> "${m / (24 * 60)} days ago"
        }
    }

    /** Only when the user taps refresh: nothing goes online on its own. */
    private fun checkLatestTurnip() {
        if (releaseChecking) return
        releaseChecking = true
        releaseStatus = "Checking Banners-Turnip and WinNative…"
        Thread({
            val problem = try { TurnipReleases.refresh(this); null } catch (e: Exception) {
                Log.w(TAG, "latest Turnip check", e); e.message ?: "check failed"
            }
            ui.post {
                releaseChecking = false
                refreshReleaseRows()
                if (problem != null) releaseStatus = "Couldn't check: $problem"
            }
        }, "turnip-release-check").start()
    }

    /** Download one release driver and import it through the same importer a picked zip uses. */
    private fun downloadReleaseDriver(assetName: String) {
        val asset = TurnipReleases.cached(this)?.assets?.firstOrNull { it.name == assetName } ?: return
        if (releaseProgress.containsKey(assetName)) return
        releaseProgress[assetName] = 0
        refreshReleaseRows()
        Thread({
            var file: java.io.File? = null
            val problem = try {
                file = TurnipReleases.download(this, asset) { pct ->
                    ui.post { releaseProgress[assetName] = pct; refreshReleaseRows() }
                }
                val uri = Uri.fromFile(file)
                val id = if (asset.linux) LinuxVulkanDriverManager(this).installDriver(uri, asset.name)
                         else TurnipDriver(this).installFromZip(uri, asset.name)
                TurnipReleases.recordDownload(this, asset, id)
                null
            } catch (e: IllegalArgumentException) {
                e.message
            } catch (e: Exception) {
                Log.w(TAG, "release driver download", e)
                "Download failed: ${e.message}"
            } finally {
                file?.let { com.droiddeck.launcher.core.FileUtils.delete(it) }
            }
            ui.post {
                releaseProgress.remove(assetName)
                android.widget.Toast.makeText(
                    this, problem ?: "Installed ${asset.name.removeSuffix(".zip")} - pick it in the menu",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                refreshDrivers()
            }
        }, "download-turnip").start()
    }

    private fun displayNameOf(uri: Uri): String? = if (uri.scheme == "file") uri.lastPathSegment else try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) {
        null
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
        prootNoSeccomp = SessionPrefs.prootNoSeccomp(this)
        // Android 12 kills the children an app forks itself once there are more than a handful.
        // A session is nothing but those, so where this is on the OS ends the session and no log
        // of ours says why. We cannot change a secure setting from here - only say so.
        phantomWarning = runCatching {
            android.provider.Settings.Global.getString(contentResolver, "settings_enable_monitor_phantom_procs")
        }.getOrNull().let { v ->
            when (v?.lowercase()) {
                "false", "0" -> null
                else -> "Android 12 and later kill the extra processes an app starts for itself once there are more than a few, and a session is made of dozens: proot, gamescope, the client and its helpers, Wine. Where that is left on, the client dies with nothing in its log, because nothing in the session did it. Some phones have a \"restrict child processes\" switch in Developer options - turn it off. Otherwise, over adb:\n\n    adb shell settings put global settings_enable_monitor_phantom_procs false\n\nThis phone " + (if (v == null) "has not been set either way, so the ROM's default applies." else "currently reports it as on.")
            }
        }
    }

    private fun refreshProtons() {
        protonRows = ProtonExtras.tools.map { ProtonRow(it.id, it.name, ProtonExtras.installed(this, it), ProtonExtras.queued(this, it)) }
    }

    private fun refresh() {
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
                com.droiddeck.launcher.frontend.AddedGameArt.resolve(this, g).let { art -> Library.SteamGame(g.appId.toInt(), g.name, art.header ?: art.portrait, "added", g.gameId) }
            } else emptyList()
            val emus = Library.emulators(this) { id -> DesktopCatalog.installed(this, id) != null }
            ui.post { steamGames = games; emulatorList = emus }
            // Box art for the games that have none, fetched after the list is up; the list is
            // rebuilt once if any was found.
            if (!OfflineMode.enabled(this) && SteamArt.fetchMissing(this, games.filter { it.library != "added" }.map { it.appId })) {
                val refreshed = games.map { g -> if (g.library == "added") g else Library.SteamGame(g.appId, g.name, SteamArt.resolve(this, g.appId), g.library, g.gameId) }
                ui.post { steamGames = refreshed }
            }
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
    private fun startSession(intent: Intent) {
        val warn = installed == null && !com.droiddeck.launcher.core.DeviceSupport.adreno()
        if (warn && available != null) showNonAdreno = available
        else startActivity(intent)
    }

    private fun install(release: LinuxRuntimeInstaller.Release) {
        busy = true
        failed = false
        stage = "Starting…"
        percent = -1
        Thread({
            val ok = LinuxRuntimeInstaller.install(this, release) { s, p ->
                ui.post { stage = s; percent = p }
            }
            ui.post {
                busy = false
                failed = !ok
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
    }
}
