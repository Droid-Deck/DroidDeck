package com.droiddeck.launcher

import androidx.compose.foundation.layout.fillMaxSize
import android.Manifest
import android.app.ActivityOptions
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import java.io.File
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
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
import com.droiddeck.launcher.gpu.Lossless
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.runtime.DesktopCatalog
import com.droiddeck.launcher.runtime.LinuxRuntimeInstaller
import com.droiddeck.launcher.runtime.DeckyManager
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.ui.PackageRow
import com.droiddeck.launcher.ui.exposeTestTags
import com.droiddeck.launcher.session.OfflineMode
import com.droiddeck.launcher.session.ProtonExtras
import com.droiddeck.launcher.session.ComponentsManager
import com.droiddeck.launcher.ui.ComponentsPage
import com.droiddeck.launcher.session.GameSaves
import com.droiddeck.launcher.session.SessionLogShare
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SteamRepair
import com.droiddeck.launcher.core.CpuCores
import com.droiddeck.launcher.core.DownloadSource
import com.droiddeck.launcher.core.PhantomProcessLimit
import com.droiddeck.launcher.core.PhantomProcessStatus
import com.droiddeck.launcher.core.WirelessAdbFix
import com.droiddeck.launcher.core.WifiDiscovery
import com.droiddeck.launcher.core.WirelessAdbPairingService
import com.droiddeck.launcher.ui.CoreRow
import com.droiddeck.launcher.ui.PerformancePage
import com.droiddeck.launcher.ui.ModeSettings
import com.droiddeck.launcher.ui.ModeSettingsActions
import com.droiddeck.launcher.ui.ConfirmDialog
import com.droiddeck.launcher.ui.ControllerActions
import com.droiddeck.launcher.ui.ControllerMappingPage
import com.droiddeck.launcher.input.ControllerPrefs
import com.droiddeck.launcher.input.ControllerEditorActivity
import com.droiddeck.launcher.ui.FrontEndScreen
import com.droiddeck.launcher.ui.FrontEndState
import com.droiddeck.launcher.ui.FrontEndActions
import com.droiddeck.launcher.frontend.CoverArt
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.frontend.GameFiles
import com.droiddeck.launcher.frontend.GameFileSync
import com.droiddeck.launcher.frontend.GameLaunchIntent
import com.droiddeck.launcher.ui.DroidDeckTheme
import com.droiddeck.launcher.ui.RomsDialog
import com.droiddeck.launcher.files.InAppFilePicker
import com.droiddeck.launcher.session.SessionArtifacts
import com.droiddeck.launcher.session.SessionPhase
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.session.GameStorage
import com.droiddeck.launcher.input.SecondScreenDisplay
import com.droiddeck.launcher.input.SecondScreenDisplays

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        com.droiddeck.launcher.core.AppLanguage.applyTo(this, newBase)
    }

    private val ui = Handler(Looper.getMainLooper())
    private val drivers = DriverMenus(this, ui)
    private val components = ComponentsMenu(this, ui)
    private val decky = DeckyMenu(this, ui)
    private val updates = UpdatesMenu(this, ui)
    private val protons = ProtonMenu(this, ui)

    private var installed by mutableStateOf<String?>(null)
    private var flood by mutableStateOf<PendingFlood?>(null)
    private var floodProgress by androidx.compose.runtime.mutableFloatStateOf(0f)
    private class PendingFlood(val from: androidx.compose.ui.geometry.Rect, val intent: Intent)
    private var returning by mutableStateOf<ReturningFlood?>(null)
    private class ReturningFlood(val color: Int, val to: androidx.compose.ui.geometry.Rect?)
    private var ready by mutableStateOf(false)
    private var removalPending by mutableStateOf(false)
    private var available by mutableStateOf<LinuxRuntimeInstaller.Release?>(null)
    private var busy by mutableStateOf(false)
    private var stage by mutableStateOf("")
    private var percent by mutableIntStateOf(-1)
    private var failed by mutableStateOf(false)
    private var frameGenLabel by mutableStateOf("")
    private var lossless by mutableStateOf(Lossless.State.NONE)
    private var showRemove by mutableStateOf(false)
    private var pendingSwitch by mutableStateOf<Intent?>(null)
    private var switchingTo: Intent? = null
    private var switchPoll: Runnable? = null
    private var switchDeadline = 0L
    private var showResetSettings by mutableStateOf(false)
    private var lowSpace by mutableStateOf<Long?>(null)
    private var showNonAdreno by mutableStateOf<LinuxRuntimeInstaller.Release?>(null)
    private var glThread by mutableStateOf(true)
    private var noGlError by mutableStateOf(true)
    private var steamRepairQueued by mutableStateOf(false)
    private var mangoapp by mutableStateOf(true)
    private var steamController by mutableStateOf(SessionPrefs.CONTROLLER_DECK)
    private var showComponents by mutableStateOf(false)
    private var focusComponentsContent by mutableStateOf(true)
    private var showMapping by mutableStateOf(false)
    private var saveBusy: String? = null
    private var onSavePicked: ((File) -> Unit)? = null
    private var controllerSettings by mutableStateOf<ControllerPrefs.Settings?>(null)
    private var catalog by mutableStateOf<List<DesktopCatalog.Entry>?>(null)
    private var catalogLoading by mutableStateOf(false)
    private var packageRows by mutableStateOf<List<PackageRow>?>(null)
    private var pkgId by mutableStateOf<String?>(null)
    private var pkgStage by mutableStateOf<String?>(null)
    private var pkgPercent by mutableIntStateOf(-1)
    private var desktopInstalled by mutableStateOf(false)
    private var desktopPresent by mutableStateOf(false)
    private var offlineAccount by mutableStateOf<String?>(null)
    private var offline by mutableStateOf(false)
    private var showPerformance by mutableStateOf(false)
    private var clientOverride by mutableStateOf(false)
    private var clientCores by mutableStateOf<Set<Int>>(emptySet())
    private var gameCores by mutableStateOf<Set<Int>>(emptySet())
    private var tuSysmem by mutableStateOf(false)
    private var zinkLazy by mutableStateOf(false)
    private var noXalia by mutableStateOf(true)
    private var fastSync by mutableStateOf(false)
    private var syncFallback by mutableStateOf(true)
    private var fsyncFirst by mutableStateOf(false)
    private var gamescopeRealtime by mutableStateOf(false)
    private var gpuClockPin by mutableStateOf(false)
    private var prootNoSeccomp by mutableStateOf(false)
    private var prootFastPath by mutableStateOf(true)
    private var guestHostname by mutableStateOf(SessionPrefs.DEFAULT_GUEST_HOSTNAME)
    private var phantomWarning by mutableStateOf<String?>(null)
    private var phantomProcessStatus by mutableStateOf(PhantomProcessStatus.NOT_APPLICABLE)
    private var showPhantomGate by mutableStateOf(false)
    private var directAudio by mutableStateOf(false)
    private var picturePosition by mutableStateOf("center")
    private var stretch16x9 by mutableStateOf(false)
    private var orientation by mutableStateOf(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
    private var launcherFullscreen by mutableStateOf(true)
    private var downloadSource by mutableStateOf(DownloadSource.MODE_AUTO)
    private var downloadMirror by mutableStateOf("")
    private var downloadPrefix by mutableStateOf("")
    private var animationsEnabled by mutableStateOf(true)
    private var gameStoresEnabled by mutableStateOf(true)
    private var gameStoresSpeedTier by mutableStateOf("fast")
    private var storesOpenTab by mutableStateOf(SessionPrefs.STORES_OPEN_LIBRARY)
    private var navRequest by mutableStateOf<String?>(null)
    private var storesShowMature by mutableStateOf(false)
    private var storesSort by mutableStateOf(SessionPrefs.STORES_SORTS.first())
    private var storesView by mutableStateOf(SessionPrefs.STORES_VIEWS.first())
    private var mic by mutableStateOf(false)
    private var wifiDiscovery by mutableStateOf(false)
    private var wifiDiscoveryPermission by mutableStateOf(false)
    private var wifiDiscoveryLocation by mutableStateOf(false)
    private var wifiDiscoveryAsked by mutableStateOf(false)
    private var wifiDiscoveryBlocked by mutableStateOf(false)
    private val wifiLocationReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            refreshWifiDiscovery()
        }
    }
    private val mediaRefresh = Runnable {
        refresh()
        refreshAddedGames()
    }
    private val mediaReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            ui.removeCallbacks(mediaRefresh)
            ui.postDelayed(mediaRefresh, MEDIA_SETTLE_MS)
        }
    }
    private val wifiLocationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        SessionPrefs.setWifiDiscoveryEnabled(this, WifiDiscovery.permissionGranted(this))
        refreshWifiDiscovery()
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        com.droiddeck.launcher.handtracking.HandTracking.refreshPermission(this)
    }
    private var placementCheck by mutableStateOf<Boolean?>(null)
    private val wifiPermissionSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        SessionPrefs.setWifiDiscoveryEnabled(this, WifiDiscovery.permissionGranted(this))
        refreshWifiDiscovery()
    }

    private val pickComponent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedUri(r.data)?.let { importComponent(it) }
    }
    private val pickLinuxDriver = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedUri(r.data)?.let { drivers.importDriver(it, linux = true) }
    }
    private val pickAndroidDriver = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedUri(r.data)?.let { drivers.importDriver(it, linux = false) }
    }
    private val pickDeckyPluginZip = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedFile(r.data)?.let { decky.importPluginZip(it) }
    }
    private val pickBootMovie = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedFile(r.data)?.let { importBootMovie(it) }
    }
    private val pickAnyDriver = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedUri(r.data)?.let { drivers.importDriver(it, linux = null) }
    }
    private val pickLossless = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedFile(r.data)?.let { importLossless(it) }
    }
    private val pickSaveZip = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val then = onSavePicked.also { onSavePicked = null } ?: return@registerForActivityResult
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedFile(r.data)?.let(then)
    }
    private val pickSaveDir = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val then = onSavePicked.also { onSavePicked = null } ?: return@registerForActivityResult
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedFile(r.data)?.let(then)
    }

    private fun refreshWifiDiscovery() {
        wifiDiscoveryPermission = WifiDiscovery.permissionGranted(this)
        wifiDiscoveryLocation = WifiDiscovery.locationEnabled(this)
        wifiDiscoveryAsked = SessionPrefs.wifiDiscoveryAsked(this)
        wifiDiscoveryBlocked = !wifiDiscoveryPermission && wifiDiscoveryAsked &&
            WifiDiscovery.permissions.none { shouldShowRequestPermissionRationale(it) }
        if (!wifiDiscoveryPermission && SessionPrefs.wifiDiscoveryEnabled(this)) {
            SessionPrefs.setWifiDiscoveryEnabled(this, false)
        }
        wifiDiscovery = SessionPrefs.wifiDiscoveryEnabled(this)
    }

    private fun openWifiDiscoverySettings() {
        if (!WifiDiscovery.permissionGranted(this)) {
            wifiPermissionSettings.launch(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")))
        } else {
            startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
    }

    private fun syncLossless() {
        Thread({
            Lossless.sync(this)
            val state = Lossless.state(this)
            ui.post { lossless = state }
        }, "lossless-sync").start()
    }

    private fun importBootMovie(file: File) {
        bootMovieImporting = true
        bootMovieProgress = -1
        Thread({
            val result = com.droiddeck.launcher.session.BootMovie.import(
                this, file,
                onStart = { ui.post { android.widget.Toast.makeText(this, getString(R.string.boot_movie_converting_started, file.name), android.widget.Toast.LENGTH_LONG).show() } },
                onProgress = { percent -> ui.post { bootMovieProgress = percent } },
            )
            val message = when (result) {
                is com.droiddeck.launcher.session.BootMovie.Import.Done -> getString(
                    if (result.audioDropped) R.string.boot_movie_imported_silent else R.string.boot_movie_imported, result.name)
                com.droiddeck.launcher.session.BootMovie.Import.TooLong ->
                    getString(R.string.boot_movie_too_long, com.droiddeck.launcher.session.BootMovie.MAX_CONVERT_SECONDS)
                is com.droiddeck.launcher.session.BootMovie.Import.Unsupported -> getString(R.string.boot_movie_unsupported)
                com.droiddeck.launcher.session.BootMovie.Import.Failed -> getString(R.string.boot_movie_import_failed)
            }
            ui.post {
                bootMovieImporting = false
                bootMovieProgress = -1
                loadBootMovie()
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }, "boot-movie-import").start()
    }

    private fun loadBootMovie() {
        bootMovie = com.droiddeck.launcher.session.BootMovie.choice(this)
        bootMovieSound = SessionPrefs.bootMovieSound(this)
        bootMovieCustomName = com.droiddeck.launcher.session.BootMovie.customName(this)
    }

    private fun importLossless(dll: File) {
        Thread({
            val message = Lossless.message(this, Lossless.import(this, dll))
            val state = Lossless.state(this)
            ui.post {
                lossless = state
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }, "lossless-import").start()
    }

    private fun importSaves(name: String, game: () -> GameSaves.Game) {
        if (SessionState.running) {
            android.widget.Toast.makeText(this, R.string.main_saves_close_session, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        onSavePicked = { zip ->
            saveAction(getString(R.string.main_saves_importing, name)) {
                val (written, backup) = GameSaves.import(this, game(), zip)
                val kind = GameSaves.layoutOf(zip)?.label(this) ?: getString(R.string.saves_zip)
                resources.getQuantityString(
                    if (backup != null) R.plurals.main_saves_imported_backup else R.plurals.main_saves_imported,
                    written, written, kind, name,
                )
            }
        }
        pickSaveZip.launch(InAppFilePicker.buildIntent(this, listOf("zip"), getString(R.string.main_saves_pick_zip, name), GameSaves.savesDir().parentFile?.parentFile?.path))
    }

    private fun exportSaves(name: String, layout: GameSaves.Layout, game: () -> GameSaves.Game) {
        onSavePicked = { dir ->
            saveAction(getString(R.string.main_saves_exporting, name)) {
                val (zip, count) = GameSaves.export(this, game(), layout, dir)
                resources.getQuantityString(R.plurals.main_saves_exported, count, count, layout.label(this), zip.path.removePrefix("/storage/emulated/0/"))
            }
        }
        GameSaves.savesDir().mkdirs()
        pickSaveDir.launch(InAppFilePicker.buildDirIntent(this, getString(R.string.main_saves_pick_folder, name, layout.label(this)), GameSaves.savesDir().path))
    }
    private fun addGameExe(path: String) {
        val exe = java.io.File(path)
        Thread({
            val result = try { com.droiddeck.launcher.frontend.AddedGames.addExe(this, exe) } catch (e: Exception) {
                Log.w(TAG, "add game: ${e.message}"); com.droiddeck.launcher.frontend.AddedGames.AddResult.NotFound
            }
            val game = when (result) {
                is com.droiddeck.launcher.frontend.AddedGames.AddResult.Added -> result.game
                is com.droiddeck.launcher.frontend.AddedGames.AddResult.Existing -> result.game
                is com.droiddeck.launcher.frontend.AddedGames.AddResult.Switched -> result.game
                else -> null
            }
            ui.post {
                if (game == null) {
                    if (result is com.droiddeck.launcher.frontend.AddedGames.AddResult.Unreachable)
                        android.widget.Toast.makeText(this, R.string.games_add_unreachable, android.widget.Toast.LENGTH_LONG).show()
                    return@post
                }
                updateAddedGames(listOf(game.folder.path), then = { fetchAddedGameArt() })
                navRequest = "app:${game.steamAppId ?: game.appId.toInt()}"
            }
        }, "add-game-exe").start()
    }
    private var addedGamesArt by mutableStateOf(true)
    @Volatile private var artFetchRunning = false
    private var sgdbUserKey by mutableStateOf(false)
    private val pickRomsDir = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedPath(r.data)?.let { path ->
            SessionPrefs.setRomsDir(this, path)
            romsDir = path
        }
    }
    private var settingsMode by mutableStateOf<String?>(null)
    private var resolution by mutableStateOf(com.droiddeck.launcher.session.SessionDisplay.DEFAULT_RESOLUTION)
    private var fexPreset by mutableStateOf("")
    private var forceSsbs by mutableStateOf(false)
    private var steamClientArch by mutableStateOf("arm64")
    private var runSteamAtStartup by mutableStateOf(false)
    private var bootMovie by mutableStateOf(com.droiddeck.launcher.session.BootMovie.DROIDDECK_30)
    private var bootMovieSound by mutableStateOf(true)
    private var bootMovieCustomName by mutableStateOf<String?>(null)
    private var bootMovieImporting by mutableStateOf(false)
    private var bootMovieProgress by mutableStateOf(-1)
    private var theme by mutableStateOf("graphite")
    private var appScale by mutableStateOf(com.droiddeck.launcher.core.AppUiPrefs.DEFAULT_SCALE)
    private var hdrOn by mutableStateOf(false)
    private var fpsLimit by mutableStateOf(0)
    private var upscaler by mutableStateOf(0)
    private var upscaleSharpness by mutableStateOf(75)
    private var hdrReason by mutableStateOf<String?>(null)
    private var touchMode by mutableStateOf(SessionPrefs.TOUCH_AUTO)
    private var suspendPolicy by mutableStateOf(SessionPrefs.SUSPEND_MANUAL)
    private var steamDownloadsInBackground by mutableStateOf(false)
    private var zramCompression by mutableStateOf(false)
    private var zramSupported by mutableStateOf<Boolean?>(null)
    private var oscMode by mutableStateOf(SessionPrefs.OSC_AUTO)
    private var backActionsInverted by mutableStateOf(false)
    private var gameStorage by mutableStateOf("")
    private var storageDiagnostics by mutableStateOf(false)
    private var storageOptions by mutableStateOf<List<GameStorage.Option>>(emptyList())
    private val pickGameStorage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedPath(r.data)?.let { path -> setGameStorage(path, GameStorage.labelFor(this, path)) }
    }
    private var romsDir by mutableStateOf<String?>(null)
    private var steamGames by mutableStateOf<List<Library.SteamGame>>(emptyList())
    private var steamGamesX64 by mutableStateOf(false)
    @Volatile private var componentSweepDone = false
    @Volatile private var componentSweepSession: String? = null
    private var emulatorList by mutableStateOf<List<Library.Emulator>>(emptyList())
    private var runningLabel by mutableStateOf<String?>(null)
    private var logsEnabled by mutableStateOf(true)
    private var vacDiagnostics by mutableStateOf(false)
    private var agentCommands by mutableStateOf(false)
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

    override fun startActivity(intent: Intent?) {
        if (intent?.component?.className == SessionActivity::class.java.name) {
            when {
                busy || LinuxRuntimeInstaller.isBusy() -> {
                    android.widget.Toast.makeText(this, R.string.main_wait_runtime, android.widget.Toast.LENGTH_SHORT).show()
                    return
                }
                protons.protonBusyId != null || ProtonExtras.installInProgress -> {
                    android.widget.Toast.makeText(this, R.string.main_wait_proton_install, android.widget.Toast.LENGTH_SHORT).show()
                    return
                }
                pkgStage != null -> {
                    android.widget.Toast.makeText(this, R.string.main_wait_desktop_install, android.widget.Toast.LENGTH_SHORT).show()
                    return
                }
            }
        }
        if (intent?.component?.className == SessionActivity::class.java.name) {
            if (flood != null) return
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

    private fun launchFlooded(f: PendingFlood) {
        if (busy || LinuxRuntimeInstaller.isBusy()) { flood = null; floodProgress = 0f; return }
        val signal = com.droiddeck.launcher.ui.Themes.byId(theme).signal
        super.startActivity(f.intent.putExtra(com.droiddeck.launcher.ui.EXTRA_FLOOD, signal.toArgb()))
        overridePendingTransition(0, 0)
    }

    private var shortcutPicker by mutableStateOf(false)
    private var shortcutLibraryScanning by mutableStateOf(false)
    @Volatile private var libraryScanGeneration = 0
    private var pendingGameLink: String? = null
    private var gameSyncFolder by mutableStateOf<String?>(null)
    private fun pickGameExport(game: Library.SteamGame?) {
        onSavePicked = { folder ->
            saveAction(getString(R.string.game_frontend_files)) {
                val saved = if (game != null) GameFiles.export(this, folder, game)
                else {
                    GameFileSync.enable(this, folder)
                    folder
                }
                ui.post {
                    gameSyncFolder = GameFileSync.folder(this)
                }
                getString(R.string.game_file_exported, saved.path)
            }
        }
        pickSaveDir.launch(InAppFilePicker.buildDirIntent(this, getString(R.string.game_file_folder), gameSyncFolder))
    }

    private fun readGameIntent(request: Intent) {
        request.getStringExtra(com.droiddeck.launcher.stores.download.StoreDownloadService.EXTRA_NAV)?.let { navRequest = it; request.removeExtra(com.droiddeck.launcher.stores.download.StoreDownloadService.EXTRA_NAV) }
        shortcutPicker = request.action == Intent.ACTION_CREATE_SHORTCUT
        if (shortcutPicker) {
            steamGames = emptyList()
            shortcutLibraryScanning = true
        } else shortcutLibraryScanning = false
        pendingGameLink = null
        if (GameLaunchIntent.accepts(request.action)) {
            val copy = Intent(request)
            Thread({
                val id = GameLaunchIntent.read(this, copy)
                ui.post {
                    if (intent === request && !isDestroyed) {
                        pendingGameLink = id
                        if (id == null) {
                            android.widget.Toast.makeText(this, R.string.game_link_invalid, android.widget.Toast.LENGTH_LONG).show()
                        } else refresh()
                    }
                }
            }, "game-launch-file").start()
        }
    }

    private fun launchGame(game: Library.SteamGame, returnHome: Boolean = false): Boolean {
        if (protons.protonBusyId != null || ProtonExtras.installInProgress || pkgStage != null) {
            android.widget.Toast.makeText(this, R.string.main_wait_install_game, android.widget.Toast.LENGTH_SHORT).show()
            return false
        }
        if (com.droiddeck.launcher.session.NoSteamLaunch.enabled(this, game)) return launchWithoutSteam(game, returnHome)
        if (SessionState.running) {
            if (SessionState.mode != SessionService.MODE_STEAM || SessionState.stopRequested) {
                android.widget.Toast.makeText(this, R.string.game_link_session_busy, android.widget.Toast.LENGTH_LONG).show()
                return true
            }
            val steamVr = game.gameIdString == com.droiddeck.launcher.gpu.SteamVr.APP_ID
            if (!steamVr && !SessionService.launchGame(this, game.gameIdString)) {
                android.widget.Toast.makeText(this, R.string.game_link_failed, android.widget.Toast.LENGTH_LONG).show()
                return false
            }
            startActivity(Intent(this, SessionActivity::class.java).setAction(SessionService.ACTION_RESUME)
                .putExtra(SessionActivity.EXTRA_STEAMVR, steamVr)
                .putExtra(SessionActivity.EXTRA_RETURN_HOME, returnHome)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            return true
        }
        return startSession(Intent(this, SessionActivity::class.java)
                .putExtra(SessionActivity.EXTRA_RETURN_HOME, returnHome)
                .apply {
                    if (game.gameIdString == com.droiddeck.launcher.gpu.SteamVr.APP_ID) {
                        putExtra(SessionActivity.EXTRA_STEAMVR, true)
                    } else {
                        putExtra(SessionService.EXTRA_STEAM_URL,
                            com.droiddeck.launcher.frontend.GameLaunchLink.steamUrl(game.gameIdString))
                    }
                }, steamSession = true)
    }

    private fun launchWithoutSteam(game: Library.SteamGame, returnHome: Boolean): Boolean {
        val app = applicationContext
        Thread({
            val added = if (game.library == Library.ADDED) runCatching { com.droiddeck.launcher.session.NoSteamLaunch.addedGame(app, game) }.getOrNull() else null
            val command = com.droiddeck.launcher.session.NoSteamLaunch.command(game, added)
            ui.post {
                if (isDestroyed) return@post
                if (command == null) {
                    android.widget.Toast.makeText(this, R.string.game_link_failed, android.widget.Toast.LENGTH_LONG).show()
                    return@post
                }
                com.droiddeck.launcher.session.NoSteamLaunch.remember(command)
                val next = Intent(this, SessionActivity::class.java)
                    .putExtra(SessionActivity.EXTRA_RETURN_HOME, returnHome)
                    .putExtra(SessionService.EXTRA_MODE, SessionService.MODE_RUN)
                    .putExtra(SessionService.EXTRA_PROGRAM, command.program)
                    .putExtra(SessionService.EXTRA_PROGRAM_ARGS, command.args.toTypedArray())
                when {
                    !SessionState.running -> startSessionOrWait(next)
                    SessionState.stopRequested -> android.widget.Toast.makeText(this, R.string.game_link_session_busy, android.widget.Toast.LENGTH_LONG).show()
                    SessionState.mode == SessionService.MODE_RUN && SessionState.program == command.program && SessionState.programArgs == command.args ->
                        startActivity(Intent(this, SessionActivity::class.java).setAction(SessionService.ACTION_RESUME)
                            .putExtra(SessionActivity.EXTRA_RETURN_HOME, returnHome)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    else -> pendingSwitch = next
                }
            }
        }, "no-steam-launch").start()
        return true
    }

    private fun opensEpicLauncher(next: Intent): Boolean =
        next.getStringExtra(SessionService.EXTRA_PROGRAM) == com.droiddeck.launcher.session.NoSteamLaunch.EPIC ||
            next.getStringExtra(SessionService.EXTRA_STEAM_URL) == com.droiddeck.launcher.frontend.GameLaunchLink.steamUrl(
                com.droiddeck.launcher.stores.epic.EpicClient.gameId(com.droiddeck.launcher.stores.epic.EpicClient.shortcutAppId))

    private fun uninstallEpicLauncher() {
        if (com.droiddeck.launcher.stores.epic.EpicClient.uninstalling) return
        com.droiddeck.launcher.stores.epic.EpicClient.uninstalling = true
        pendingSwitch?.takeIf(::opensEpicLauncher)?.let { pendingSwitch = null }
        switchingTo?.takeIf(::opensEpicLauncher)?.let {
            switchPoll?.let { poll -> ui.removeCallbacks(poll) }
            switchPoll = null
            switchingTo = null
        }
        if (SessionState.running) SessionService.stop(this)
        val app = applicationContext
        val deadline = android.os.SystemClock.uptimeMillis() + SESSION_SWITCH_WAIT_MS
        Thread({
            while ((SessionState.running || SessionState.phase !in SESSION_ENDED) && android.os.SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(SESSION_SWITCH_POLL_MS)
            }
            val ended = !SessionState.running && SessionState.phase in SESSION_ENDED
            val done = try {
                ended && com.droiddeck.launcher.stores.epic.EpicClient.uninstall(app)
            } finally {
                com.droiddeck.launcher.stores.epic.EpicClient.uninstalling = false
            }
            ui.post {
                if (!isDestroyed) refresh()
                android.widget.Toast.makeText(app, if (done) R.string.epic_client_uninstalled else R.string.epic_client_uninstall_failed,
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }, "epic-uninstall").start()
    }

    private fun openEpicLauncher(): Boolean {
        if (com.droiddeck.launcher.stores.epic.EpicClient.uninstalling) {
            android.widget.Toast.makeText(this, R.string.epic_client_uninstalling, android.widget.Toast.LENGTH_SHORT).show()
            return false
        }
        if (protons.protonBusyId != null || ProtonExtras.installInProgress || pkgStage != null) {
            android.widget.Toast.makeText(this, R.string.main_wait_install_game, android.widget.Toast.LENGTH_SHORT).show()
            return false
        }
        if (!com.droiddeck.launcher.stores.epic.EpicClient.launcherRunning(this) && !com.droiddeck.launcher.stores.epic.EpicClient.fexReady(this)) {
            com.droiddeck.launcher.stores.epic.EpicClient.requestFex(this)
            android.widget.Toast.makeText(this, R.string.epic_client_needs_fex, android.widget.Toast.LENGTH_LONG).show()
            if (!SessionState.running) startSteamSession()
            else if (SessionState.mode == SessionService.MODE_STEAM) startActivity(Intent(this, SessionActivity::class.java)
                .setAction(SessionService.ACTION_RESUME).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            return true
        }
        if (com.droiddeck.launcher.session.NoSteamLaunch.enabled(this, com.droiddeck.launcher.stores.epic.EpicClient.shortcutAppId)) {
            com.droiddeck.launcher.stores.epic.EpicClient.launcherEntry(this, Library.EPIC_LAUNCHER_NAME)?.let { return launchWithoutSteam(it, false) }
        }
        val launcher = Intent(this, SessionActivity::class.java)
            .putExtra(SessionActivity.EXTRA_RETURN_HOME, false)
            .putExtra(SessionService.EXTRA_STEAM_URL, com.droiddeck.launcher.frontend.GameLaunchLink.steamUrl(
                com.droiddeck.launcher.stores.epic.EpicClient.gameId(com.droiddeck.launcher.stores.epic.EpicClient.shortcutAppId)))
        if (SessionState.running && SessionState.mode == SessionService.MODE_STEAM && !SessionState.stopRequested) {
            val app = applicationContext
            val shortcut = com.droiddeck.launcher.stores.epic.EpicClient.shortcutAppId
            val listed = com.droiddeck.launcher.frontend.AddedGames.inSteam(app, shortcut)
            if (listed && !SessionService.launchGame(this, com.droiddeck.launcher.stores.epic.EpicClient.gameId(shortcut))) {
                android.widget.Toast.makeText(this, R.string.game_link_failed, android.widget.Toast.LENGTH_LONG).show()
                return false
            }
            startActivity(Intent(this, SessionActivity::class.java).setAction(SessionService.ACTION_RESUME)
                .putExtra(SessionActivity.EXTRA_RETURN_HOME, false)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            if (!listed) Thread({
                com.droiddeck.launcher.frontend.AddedGamesPublisher.republish(app)
                val id = com.droiddeck.launcher.stores.epic.EpicClient.steamLauncherId(app) ?: shortcut
                ui.post { SessionService.launchGame(app, com.droiddeck.launcher.stores.epic.EpicClient.gameId(id)) }
            }, "epic-launcher-open").start()
            return true
        }
        if (SessionState.running) {
            if (SessionState.stopRequested) android.widget.Toast.makeText(this, R.string.game_link_session_busy, android.widget.Toast.LENGTH_LONG).show()
            else pendingSwitch = launcher
            return true
        }
        return startSessionOrWait(launcher)
    }

    private fun startSessionOrWait(next: Intent): Boolean {
        val started = startSession(next, steamSession = next.hasExtra(SessionService.EXTRA_STEAM_URL))
        if (!started && (busy || LinuxRuntimeInstaller.isBusy())) android.widget.Toast.makeText(this, R.string.main_wait_install_game, android.widget.Toast.LENGTH_SHORT).show()
        return started
    }

    private fun startAfterSession(next: Intent) {
        SessionService.stop(this)
        awaitSessionEnd(next, android.os.SystemClock.uptimeMillis() + SESSION_SWITCH_WAIT_MS)
    }

    private fun awaitSessionEnd(next: Intent, deadline: Long) {
        switchingTo = next
        switchDeadline = deadline
        switchPoll?.let { ui.removeCallbacks(it) }
        val poll = object : Runnable {
            override fun run() {
                if (isDestroyed) return
                if (SessionState.running || SessionState.phase !in SESSION_ENDED) {
                    if (android.os.SystemClock.uptimeMillis() < deadline) ui.postDelayed(this, SESSION_SWITCH_POLL_MS)
                    else {
                        switchPoll = null
                        switchingTo = null
                        android.widget.Toast.makeText(this@MainActivity, R.string.game_link_session_busy, android.widget.Toast.LENGTH_LONG).show()
                    }
                    return
                }
                switchPoll = null
                switchingTo = null
                if (com.droiddeck.launcher.stores.epic.EpicClient.uninstalling && opensEpicLauncher(next)) return
                startSessionOrWait(next)
            }
        }
        switchPoll = poll
        ui.postDelayed(poll, SESSION_SWITCH_POLL_MS)
    }

    private fun handleGameLink(games: List<Library.SteamGame>) {
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        val id = pendingGameLink ?: return
        val game = games.firstOrNull { it.gameIdString == id }
        if (game == null) {
            pendingGameLink = null
            intent.data = null
            intent.action = Intent.ACTION_MAIN
            intent.removeExtra(GameLaunchIntent.EXTRA_APP_ID)
            android.widget.Toast.makeText(this, R.string.game_link_missing, android.widget.Toast.LENGTH_LONG).show()
        } else if (launchGame(game, returnHome = true)) {
            pendingGameLink = null
            intent.data = null
            intent.action = Intent.ACTION_MAIN
            intent.removeExtra(GameLaunchIntent.EXTRA_APP_ID)
        }
    }

    private fun chooseGameShortcut(game: Library.SteamGame) {
        setResult(RESULT_OK, com.droiddeck.launcher.frontend.GameShortcuts.result(this, game))
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readGameIntent(intent)
        gameSyncFolder = GameFileSync.folder(this)
        displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        refreshPhantomStatus()
        theme = SessionPrefs.theme(this)
        frameGenLabel = getString(R.string.frame_gen_off)
        appScale = com.droiddeck.launcher.core.AppUiPrefs.scale(this)
        val (cached, cachedX64) = com.droiddeck.launcher.frontend.LibraryCache.snapshot(this)
        steamGames = if (shortcutPicker) emptyList() else cached
        steamGamesX64 = cachedX64
        backActionsInverted = SessionPrefs.backActionsInverted(this)
        orientation = SessionPrefs.orientation(this)
        requestedOrientation = orientation
        launcherFullscreen = SessionPrefs.launcherFullscreen(this)
        downloadSource = DownloadSource.mode(this)
        downloadMirror = DownloadSource.mirror(this)
        downloadPrefix = DownloadSource.custom(this)
        DownloadSource.probe()
        animationsEnabled = SessionPrefs.animationsEnabled(this)
        gameStoresEnabled = SessionPrefs.gameStoresEnabled(this)
        gameStoresSpeedTier = SessionPrefs.gameStoresSpeedTier(this)
        storesOpenTab = SessionPrefs.storesOpenTab(this)
        storesShowMature = SessionPrefs.storesShowMature(this)
        storesSort = SessionPrefs.storesSort(this)
        storesView = SessionPrefs.storesView(this)
        applyLauncherFullscreen()
        updates.start()
        setContent {
            DroidDeckTheme(theme, appScale = appScale) {
            com.droiddeck.launcher.ui.FocusGlideHost(androidx.compose.ui.Modifier.fillMaxSize().exposeTestTags()) {
                val page: (@Composable () -> Unit)? = when {
                    showPerformance -> { { PerformanceHost() } }
                    showComponents -> { { ComponentsHost() } }
                    showMapping -> { { MappingHost() } }
                    else -> null
                }
                com.droiddeck.launcher.ui.FloodBehind({ floodProgress }) {
                FrontEndScreen(
                    FrontEndState(
                        installed = installed, ready = ready, available = available?.version, removalPending = removalPending, runtimeActionsBlocked = runtimeChangesBlocked(),
                        shortcutPicker = shortcutPicker,
                        shortcutLibraryScanning = shortcutLibraryScanning,
                        gameSyncFolder = gameSyncFolder,
                        busy = busy, stage = stage, percent = percent,
                        desktopInstalled = desktopInstalled, desktopPresent = desktopPresent,
                        offlineAccount = offlineAccount, offline = offline,
                        frameGenLabel = frameGenLabel, romsDir = romsDir, logsEnabled = logsEnabled,
                        vacDiagnostics = vacDiagnostics, agentCommands = agentCommands,
                        steamGames = steamGames, steamGamesX64 = steamGamesX64, emulators = emulatorList, running = runningLabel,
                        frameGen = FrameGen.mode(this),
                        lossless = lossless,
                        pageKey = if (showPerformance) "performance" else if (showComponents) "components" else if (showMapping) "controller-mapping" else null,
                        theme = theme,
                        language = com.droiddeck.launcher.core.AppLanguage.chosen(this),
                        appScale = appScale,
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
                        updates = updates.state(),
                        phantomProcessStatus = phantomProcessStatus,
                        showPhantomGate = showPhantomGate,
                        orientation = orientation,
                        launcherFullscreen = launcherFullscreen,
                        downloadSource = downloadSource,
                        downloadMirror = downloadMirror,
                        downloadPrefix = downloadPrefix,
                        animationsEnabled = animationsEnabled,
                        gameStoresEnabled = gameStoresEnabled,
                        gameStoresSpeedTier = gameStoresSpeedTier,
                        storesOpenTab = storesOpenTab,
                        navigate = navRequest,
                        addedGamesArt = addedGamesArt,
                        sgdbUserKey = sgdbUserKey,
                        storesShowMature = storesShowMature,
                        storesSort = storesSort,
                        storesView = storesView,
                        storeDownloadsActive = com.droiddeck.launcher.stores.StoresState.activeDownloads,
                    ),
                    FrontEndActions(
                        onPlay = { playSteam() },
                        onPlaySteamVr = { playSteamVr() },
                        onSteamUrl = { url ->
                            if (!SessionState.running) startSession(
                                Intent(this, SessionActivity::class.java).putExtra(SessionService.EXTRA_STEAM_URL, url), steamSession = true,
                            )
                        },
                        onPlayDesktopUi = {
                            startSession(Intent(this, SessionActivity::class.java)
                                .putExtra(SessionService.EXTRA_MODE, SessionService.MODE_STEAM)
                                .putExtra(SessionService.EXTRA_STEAM_UI, "desktop"), steamSession = true)
                        },
                        onSteamGame = { g ->
                            if (shortcutPicker) chooseGameShortcut(g)
                            else {
                                com.droiddeck.launcher.frontend.AutoComponents.beforeLaunch(this, g)
                                com.droiddeck.launcher.stores.StoreLaunch.prepare(this, g) { launchGame(g) }
                            }
                        },
                        onEpicLauncher = { openEpicLauncher() },
                        onUninstallEpicLauncher = { uninstallEpicLauncher() },
                        onGameShortcut = { g -> com.droiddeck.launcher.frontend.GameShortcuts.pin(this, g) },
                        onExportGameFile = { g -> pickGameExport(g) },
                        onSyncGameFiles = { pickGameExport(null) },
                        onAddGameExe = { path -> addGameExe(path) },
                        onAddedGamesChanged = { folders ->
                            val known = steamGames.mapNotNull { it.gameFiles?.path }.toSet()
                            updateAddedGames(folders, then = { if (folders.any { it !in known }) fetchAddedGameArt() })
                        },
                        onSteamGameArtChanged = { appId -> refreshSteamGameArt(appId) },
                        onAddedGameRemoved = { folder, appId, name -> removeAddedGame(folder, appId, name) },
                        onAddedGamesArt = { on -> SessionPrefs.setAddedGamesArt(this, on); addedGamesArt = on; if (on) refreshAddedGames() },
                        onSgdbKey = { key ->
                            Thread({
                                com.droiddeck.launcher.frontend.SteamGridDb.setUserKey(this, key)
                                ui.post { sgdbUserKey = key.isNotBlank(); refreshAddedGames() }
                            }, "sgdb-key").start()
                        },
                        onStopGameFileSync = {
                            Thread({
                                GameFileSync.disable(this)
                                ui.post { gameSyncFolder = null }
                            }, "game-file-stop-sync").start()
                        },
                        onCopyGameLink = { g ->
                            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                                ClipData.newPlainText(g.name, com.droiddeck.launcher.frontend.GameLaunchLink.uri(g.gameIdString)))
                            android.widget.Toast.makeText(this, R.string.game_link_copied, android.widget.Toast.LENGTH_SHORT).show()
                        },
                        onDesktop = {
                            startSession(Intent(this, SessionActivity::class.java)
                                .putExtra(SessionService.EXTRA_MODE, SessionService.MODE_DESKTOP))
                        },
                        onEmulator = { e -> launchProgram(e.program) },
                        onUserApp = { app ->
                            app.args.firstOrNull()?.let { Library.flatpakNames[it] = app.name }
                            startActivity(Intent(this, SessionActivity::class.java)
                                .putExtra(SessionService.EXTRA_MODE, SessionService.MODE_RUN)
                                .putExtra(SessionService.EXTRA_PROGRAM, app.program)
                                .putExtra(SessionService.EXTRA_PROGRAM_ARGS, app.args.toTypedArray()))
                        },
                        onFlatpakApp = { id, name ->
                            Library.flatpakNames[id] = name
                            startActivity(Intent(this, SessionActivity::class.java)
                                .putExtra(SessionService.EXTRA_MODE, SessionService.MODE_RUN)
                                .putExtra(SessionService.EXTRA_PROGRAM, com.droiddeck.launcher.runtime.FlatpakManager.LAUNCHER)
                                .putExtra(SessionService.EXTRA_PROGRAM_ARGS, arrayOf(id)))
                        },
                        onRom = { g ->
                            val e = emulatorList.first { it.id == g.emulatorId }
                            startActivity(Intent(this, SessionActivity::class.java)
                                .putExtra(SessionService.EXTRA_MODE, SessionService.MODE_RUN)
                                .putExtra(SessionService.EXTRA_PROGRAM, e.program)
                                .putExtra(SessionService.EXTRA_PROGRAM_ARGS, Library.launchArgs(e.id, g.guestPath).toTypedArray()))
                        },
                        onResume = { startActivity(Intent(this, SessionActivity::class.java)) },
                        onSteamSettings = { loadModeSettings(SessionService.MODE_STEAM) },
                        onRemoveDesktop = { removeDesktop() },
                        onDesktopSettings = { loadModeSettings(SessionService.MODE_DESKTOP) },
                        modeSheet = { mode, tab, extras -> ModeSheetHost(mode, tab, extras) },
                        onInstallPackage = { id -> installPackage(id) },
                        onRemovePackage = { id -> removePackage(id) },
                        onRuntime = { onRuntimeButton() },
                        onFrameGenPick = { mode ->
                            FrameGen.set(this, mode)
                            frameGenLabel = FrameGen.label(this)
                        },
                        onImportLossless = {
                            pickLossless.launch(InAppFilePicker.buildIntent(this, listOf("dll"), getString(R.string.lsfg_pick_title)))
                        },
                        onProtons = { openProtons() },
                        onComponents = { focusContent -> openComponents(focusContent) },
                        onComponentsTab = { tab -> openComponents(focusContent = true, tab = tab) },
                        onSaveImport = { sg -> importSaves(sg.name) { GameSaves.game(sg) } },
                        onSaveExport = { sg, layout -> exportSaves(sg.name, layout) { GameSaves.game(sg) } },
                        onPerformance = { refreshCores(); showComponents = false; showMapping = false; showPerformance = true },
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
                        onVacDiagnostics = {
                            SessionPrefs.setVacDiagnostics(this, !SessionPrefs.vacDiagnostics(this))
                            vacDiagnostics = SessionPrefs.vacDiagnostics(this)
                        },
                        onAgentCommands = {
                            com.droiddeck.launcher.agent.AgentAccess.setCommandsEnabled(this, !com.droiddeck.launcher.agent.AgentAccess.commandsEnabled(this))
                            agentCommands = com.droiddeck.launcher.agent.AgentAccess.commandsEnabled(this)
                        },
                        onShareLogs = {
                            SessionLogShare.prepare(this, { SessionLogShare.latest(this) }) { zip ->
                                if (zip == null) android.widget.Toast.makeText(this, R.string.main_no_logs_yet, android.widget.Toast.LENGTH_LONG).show()
                                else startActivity(SessionLogShare.shareIntent(this, zip))
                            }
                        },
                        onClearLogs = {
                            Thread({
                                val cleared = runCatching { SessionArtifacts.clearAll(this) }.getOrDefault(0)
                                ui.post {
                                    android.widget.Toast.makeText(this,
                                        if (cleared == 0) getString(R.string.main_no_logs_to_clear) else resources.getQuantityString(R.plurals.main_logs_cleared, cleared, cleared),
                                        android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }, "clear-logs").start()
                        },
                        onResetSettings = {
                            if (SessionState.running) android.widget.Toast.makeText(this, R.string.setup_reset_settings_session_running, android.widget.Toast.LENGTH_LONG).show()
                            else showResetSettings = true
                        },
                        onOffline = {
                            OfflineMode.setEnabled(this, !OfflineMode.enabled(this))
                            offline = OfflineMode.enabled(this)
                        },
                        onPageBack = { showPerformance = false; showComponents = false; showMapping = false },
                        onGameStoresEnabled = { on -> SessionPrefs.setGameStoresEnabled(this, on); gameStoresEnabled = on },
                        onTheme = { id -> SessionPrefs.setTheme(this, id); theme = id },
                        onLanguage = { tag ->
                            if (tag != com.droiddeck.launcher.core.AppLanguage.chosen(this)) {
                                com.droiddeck.launcher.core.AppLanguage.setChosen(this, tag)
                                recreate()
                            }
                        },
                        onAppScale = { percent ->
                            com.droiddeck.launcher.core.AppUiPrefs.setScale(this, percent)
                            appScale = com.droiddeck.launcher.core.AppUiPrefs.scale(this)
                        },
                        onOrientation = { value ->
                            SessionPrefs.setOrientation(this, value)
                            orientation = value
                            requestedOrientation = value
                        },
                        onLauncherFullscreen = { on ->
                            SessionPrefs.setLauncherFullscreen(this, on)
                            launcherFullscreen = on
                            applyLauncherFullscreen()
                        },
                        onGameStoresSpeedTier = { tier -> SessionPrefs.setGameStoresSpeedTier(this, tier); gameStoresSpeedTier = tier },
                        onStoresOpenTab = { tab -> SessionPrefs.setStoresOpenTab(this, tab); storesOpenTab = tab },
                        onNavigated = { navRequest = null },
                        onStoresShowMature = { show -> SessionPrefs.setStoresShowMature(this, show); storesShowMature = show },
                        onStoresSort = { sort -> SessionPrefs.setStoresSort(this, sort); storesSort = sort },
                        onStoresView = { view -> SessionPrefs.setStoresView(this, view); storesView = view },
                        onLibraryChanged = { refreshAddedGames(); refresh() },
                        onAnimationsEnabled = { on ->
                            SessionPrefs.setAnimationsEnabled(this, on)
                            animationsEnabled = on
                            com.droiddeck.launcher.ui.Motion.refresh(this)
                        },
                        onDownloadSource = { mode ->
                            DownloadSource.setMode(this, mode)
                            downloadSource = mode
                        },
                        onDownloadMirror = { host ->
                            DownloadSource.setMirror(this, host)
                            downloadMirror = host
                        },
                        onDownloadPrefix = { prefix ->
                            DownloadSource.setCustom(this, prefix)
                            downloadPrefix = DownloadSource.custom(this)
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
                        onRefreshPhantomStatus = { refreshPhantomStatus() },
                        onOpenDeveloperOptions = { displayId -> openDeveloperOptions(displayId) },
                        onWirelessAdbPair = { host, port, code, complete ->
                            Thread({
                                val result = runCatching {
                                    kotlinx.coroutines.runBlocking { WirelessAdbFix.pair(this@MainActivity, host, port, code) }
                                }
                                val error = result.exceptionOrNull()?.let { it.localizedMessage ?: getString(R.string.main_wadb_pair_failed) }
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
                                val error = result.exceptionOrNull()?.let { it.localizedMessage ?: getString(R.string.main_wadb_command_failed) }
                                ui.post {
                                    if (error == null) refreshPhantomStatus()
                                    complete(error)
                                }
                            }, "wireless-adb-fix").start()
                        },
                        onSetPhantomProcessLimit = { enabled, complete ->
                            Thread({
                                val result = runCatching { WirelessAdbFix.setUsingSavedPairing(this@MainActivity, enabled) }
                                val error = result.exceptionOrNull()?.let { it.localizedMessage ?: getString(R.string.main_wadb_unavailable) }
                                ui.post {
                                    if (error == null) refreshPhantomStatus()
                                    complete(error)
                                }
                            }, "wireless-adb-setting").start()
                        },
                        onCopyPhantomCommand = { enabled ->
                            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                                ClipData.newPlainText(getString(R.string.main_adb_clip_label), PhantomProcessLimit.adbCommand(enabled)),
                            )
                            android.widget.Toast.makeText(this, R.string.main_adb_copied, android.widget.Toast.LENGTH_SHORT).show()
                        },
                        onDismissPhantomGate = { showPhantomGate = false },
                        onStartWirelessAdbPairing = { WirelessAdbPairingService.start(this) },
                        onOpenNotificationSettings = {
                            startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        },
                        updates = updates.actions(),
                        controller = ControllerActions(
                            onOsc = { o -> SessionPrefs.setOscMode(this, o); oscMode = o },
                            onTint = { t -> ControllerPrefs.setTint(this, t); refreshController() },
                            onOpacity = { o -> ControllerPrefs.setOpacity(this, o); refreshController() },
                            onSize = { v -> ControllerPrefs.setSize(this, v); refreshController() },
                            onStickClick = { on -> ControllerPrefs.setStickClick(this, on); refreshController() },
                            onAdaptiveSticks = { on -> ControllerPrefs.setAdaptiveSticks(this, on); refreshController() },
                            onRumble = { on -> ControllerPrefs.setRumble(this, on); refreshController() },
                            onSteamButton = { on -> ControllerPrefs.setSteamButton(this, on); refreshController() },
                            onQamButton = { on -> ControllerPrefs.setQamButton(this, on); refreshController() },
                            onKeyboardButton = { on -> ControllerPrefs.setKeyboardButton(this, on); refreshController() },
                            onSelectSteam = { on -> ControllerPrefs.setSelectSteam(this, on); refreshController() },
                            onJoyConVr = { on -> ControllerPrefs.setJoyConVr(this, on); refreshController() },
                            onEditLayout = { startActivity(Intent(this, ControllerEditorActivity::class.java)) },
                            onResetLayout = { ControllerPrefs.resetAllLayouts(this); refreshController() },
                            onMapping = { showPerformance = false; showComponents = false; showMapping = true },
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
                        pickRomsDir.launch(InAppFilePicker.buildDirIntent(this, getString(R.string.main_pick_roms), romsDir))
                    },
                    onClear = { SessionPrefs.setRomsDir(this, ""); romsDir = null; showRoms = false },
                    onDismiss = { showRoms = false },
                )
                showNonAdreno?.let { release ->
                    ConfirmDialog(
                        title = androidx.compose.ui.res.stringResource(R.string.main_non_adreno_title),
                        text = androidx.compose.ui.res.stringResource(R.string.main_non_adreno_text, com.droiddeck.launcher.core.DeviceSupport.gpuName(this@MainActivity), release.size / 1e6),
                        confirm = androidx.compose.ui.res.stringResource(R.string.main_install_anyway),
                        onConfirm = { showNonAdreno = null; install(release) },
                        onDismiss = { showNonAdreno = null },
                    )
                }
                pendingSwitch?.let { next ->
                    ConfirmDialog(
                        title = androidx.compose.ui.res.stringResource(R.string.drawer_stop_title),
                        text = runningLabel ?: "",
                        confirm = androidx.compose.ui.res.stringResource(R.string.drawer_stop),
                        onConfirm = { pendingSwitch = null; startAfterSession(next) },
                        onDismiss = { pendingSwitch = null },
                    )
                }
                if (showRemove) ConfirmDialog(
                    title = androidx.compose.ui.res.stringResource(R.string.main_remove_runtime_title),
                    text = androidx.compose.ui.res.stringResource(R.string.main_remove_runtime_text),
                    confirm = androidx.compose.ui.res.stringResource(R.string.store_remove),
                    onConfirm = { showRemove = false; removeRuntime() },
                    onDismiss = { showRemove = false },
                )
                lowSpace?.let { free ->
                    com.droiddeck.launcher.ui.LowSpaceDialog(
                        free,
                        onFreeUp = { lowSpace = null; com.droiddeck.launcher.storage.StorageState.requestOpen() },
                        onPlay = { lowSpace = null; com.droiddeck.launcher.storage.StorageState.lowSpaceDismissed = true; startSteamSession() },
                        onDismiss = { lowSpace = null },
                    )
                }
                com.droiddeck.launcher.ui.HandCameraHost(
                    check = placementCheck,
                    onAskPermission = { cameraPermission.launch(Manifest.permission.CAMERA) },
                    onPlay = { placementCheck = null; startSteamSession(steamVr = true) },
                    onClose = { placementCheck = null },
                )
                if (showResetSettings) com.droiddeck.launcher.ui.ResetSettingsDialog(
                    onConfirm = { gameProfiles -> resetSettings(gameProfiles) },
                    onDismiss = { showResetSettings = false },
                )
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
        }

        if (savedInstanceState == null) ui.post { startSteamAtStartupIfEnabled() }
        else {
            pendingSwitch = savedIntent(savedInstanceState, KEY_PENDING_SWITCH)
            savedIntent(savedInstanceState, KEY_SWITCHING_TO)?.let { awaitSessionEnd(it, savedInstanceState.getLong(KEY_SWITCH_DEADLINE)) }
        }

        val wanted = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            wanted.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (SessionPrefs.micEnabled(this) && !SessionPrefs.micAsked(this)
            && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            wanted.add(Manifest.permission.RECORD_AUDIO)
            SessionPrefs.setMicAsked(this)
        }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
        Thread({ com.droiddeck.launcher.stores.StoreAccounts.encryptAll(this) }, "store-credentials").start()
        if (!SessionState.running) Thread({
            com.droiddeck.launcher.session.LogMigration.run(this)
            com.droiddeck.launcher.frontend.GamesFolderMigration.run(this)
            SessionLogShare.clear(this)
            SessionArtifacts.finishAbandoned(this)
            SessionArtifacts.scrubOlder(this)
            SessionArtifacts.prune(this)
        }, "finish-abandoned").start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readGameIntent(intent)
        refresh()
        startSteamAtStartupIfEnabled()
    }

    private fun startSteamAtStartupIfEnabled() {
        if (intent.action != Intent.ACTION_MAIN) return
        if (SessionPrefs.runSteamAtStartup(this) && !isFinishing && !isDestroyed) startSteamSession()
    }

    private fun playSteam() {
        val storage = com.droiddeck.launcher.storage.StorageState
        if (!SessionState.running && !storage.lowSpaceDismissed && storage.lowOnSpace(this)) {
            lowSpace = storage.freeBytes(this)
            storage.refresh(this)
        } else startSteamSession()
    }

    private fun playSteamVr() {
        val tracking = com.droiddeck.launcher.glasses.Glasses.settings.value
        if (!com.droiddeck.launcher.glasses.Glasses.state.value.usesSpatialControllers(tracking) &&
            com.droiddeck.launcher.handtracking.HandTracking.wanted(this)) placementCheck = true
        else startSteamSession(steamVr = true)
    }

    private fun startSteamSession(steamVr: Boolean = false) {
        startSession(Intent(this, SessionActivity::class.java).putExtra(SessionActivity.EXTRA_STEAMVR, steamVr), steamSession = true)
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
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (launcherFullscreen) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_A) com.droiddeck.launcher.ui.HeldKeys.confirm = event.action == KeyEvent.ACTION_DOWN
        if (showComponents && (event.keyCode == KeyEvent.KEYCODE_BUTTON_L1 || event.keyCode == KeyEvent.KEYCODE_BUTTON_R1)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                components.compComp = com.droiddeck.launcher.ui.layerStep(components.compComp, if (event.keyCode == KeyEvent.KEYCODE_BUTTON_R1) 1 else -1)
            }
            return true
        }
        return try {
            super.dispatchKeyEvent(event)
        } catch (e: IllegalStateException) {
            if (e.message?.contains("active focus target") != true) throw e
            android.util.Log.w("MainActivity", "key ${event.keyCode} dropped: ${e.message}")
            true
        }
    }

    override fun onResume() {
        super.onResume()
        orientation = SessionPrefs.orientation(this)
        requestedOrientation = orientation
        refreshWifiDiscovery()
        com.droiddeck.launcher.stores.StoresState.refresh(this)
        com.droiddeck.launcher.stores.StoresState.libraryListener = { refreshAddedGames(); refresh() }
        com.droiddeck.launcher.ui.Motion.refresh(this)
        com.droiddeck.launcher.ui.QuitFlood.take()?.let { c ->
            returning = ReturningFlood(c, com.droiddeck.launcher.ui.LaunchOrigin.takeReturn())
        }
        refreshPhantomStatus()
        syncLossless()
        updates.onResume()
        drivers.checkOnOpen()
        protons.checkUpdates()
        if (!SessionState.running) Thread({
            val applied = runCatching { ComponentsManager.applyQueued(this) }.getOrDefault(emptyList())
            if (applied.isNotEmpty()) ui.post {
                android.widget.Toast.makeText(this, getString(R.string.main_applied, applied.joinToString(", ")), android.widget.Toast.LENGTH_LONG).show()
            }
            ui.post { if (showComponents) components.refreshComponents() }
        }, "components-queue").start()
        else if (showComponents) components.refreshComponents()
        oscMode = SessionPrefs.oscMode(this)
        refreshController()
        refreshHomeAppState()
        refreshSecondScreenDisplays()
        refresh()
        decky.deckyInstalled = DeckyManager.installed(this)
        DeckyManager.syncCefMarker(this)
        decky.deckySupervisor = DeckyManager.supervisorEnabled(this)
        refreshAddedGames()
        if (catalog == null) loadDesktopCatalog()
        if (!busy) Thread({ checkCatalog() }, "catalog").start()
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(wifiLocationReceiver, android.content.IntentFilter(android.location.LocationManager.MODE_CHANGED_ACTION))
        registerReceiver(mediaReceiver, android.content.IntentFilter().apply {
            for (action in listOf(Intent.ACTION_MEDIA_MOUNTED, Intent.ACTION_MEDIA_UNMOUNTED, Intent.ACTION_MEDIA_REMOVED,
                Intent.ACTION_MEDIA_EJECT, Intent.ACTION_MEDIA_BAD_REMOVAL)) addAction(action)
            addDataScheme("file")
        })
        displayManager.registerDisplayListener(secondScreenDisplayListener, ui)
        refreshSecondScreenDisplays()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingSwitch?.let { outState.putParcelable(KEY_PENDING_SWITCH, it) }
        switchingTo?.let {
            outState.putParcelable(KEY_SWITCHING_TO, it)
            outState.putLong(KEY_SWITCH_DEADLINE, switchDeadline)
        }
    }

    private fun savedIntent(state: Bundle, key: String): Intent? =
        if (Build.VERSION.SDK_INT >= 33) state.getParcelable(key, Intent::class.java)
        else @Suppress("DEPRECATION") state.getParcelable(key)

    override fun onDestroy() {
        updates.unregister()
        super.onDestroy()
    }

    override fun onStop() {
        unregisterReceiver(wifiLocationReceiver)
        unregisterReceiver(mediaReceiver)
        ui.removeCallbacks(mediaRefresh)
        com.droiddeck.launcher.stores.StoresState.libraryListener = null
        displayManager.unregisterDisplayListener(secondScreenDisplayListener)
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
        // The launcher can itself be shown on a non-default display (DeX, an external monitor); that one is not a "second screen".
        val ownDisplayId = if (android.os.Build.VERSION.SDK_INT >= 30) display?.displayId ?: android.view.Display.DEFAULT_DISPLAY else @Suppress("DEPRECATION") windowManager.defaultDisplay.displayId
        secondScreenDisplays = SecondScreenDisplays.available(displayManager, ownDisplayId)
    }

    private fun launchAndroidApp(app: HomeApp.LaunchableApp, displayId: Int?) {
        try {
            HomeApp.launch(this, app, displayId)
        } catch (_: Exception) {
            val label = secondScreenDisplays.firstOrNull { it.id == displayId }?.label
            val message = when {
                displayId == null || displayId == Display.DEFAULT_DISPLAY -> getString(R.string.android_app_open_failed_primary, app.label)
                label != null -> getString(R.string.android_app_open_failed_on, app.label, label)
                else -> getString(R.string.android_app_open_failed_display, app.label, displayId)
            }
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
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

    private fun saveAction(label: String, work: () -> String) {
        if (busy || LinuxRuntimeInstaller.isBusy() || saveBusy != null) return
        saveBusy = label
        Thread({
            val message = runCatching(work).getOrElse { e -> getString(R.string.main_action_failed, label, e.message ?: e.javaClass.simpleName) }
            ui.post {
                saveBusy = null
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }, "game-saves-action").start()
    }

    private fun openProtons() = openComponents(focusContent = true, tab = com.droiddeck.launcher.ui.PROTON_TAB)

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

    private fun openComponents(focusContent: Boolean = true, tab: String? = null) {
        focusComponentsContent = focusContent
        if (tab != null) components.compComp = tab
        showPerformance = false
        showMapping = false
        showComponents = true
        fexPreset = SessionPrefs.fexPreset(this)
        forceSsbs = SessionPrefs.forceSsbs(this)
        fastSync = SessionPrefs.fastSync(this)
        fsyncFirst = SessionPrefs.fsyncFirst(this)
        syncFallback = SessionPrefs.syncFallback(this)
        components.refreshComponents(snapshotFirst = true)
        drivers.refreshDrivers()
        protons.refreshProtons()
        protons.checkUpdates()
    }

    private fun importComponent(uri: Uri) {
        val name = displayNameOf(uri) ?: "imported.wcp"
        components.componentAction(getString(R.string.comp_busy_importing)) {
            val tmp = File(cacheDir, "component-import.wcp")
            contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } } ?: error(getString(R.string.comp_cannot_read))
            try { getString(R.string.comp_imported, ComponentsManager.importPackage(this, tmp, name).version) } finally { tmp.delete() }
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
            busy = if (busy) stage else components.compBusy,
            downloads = components.compDownloads,
            requestInitialFocus = focusComponentsContent,
            onProton = { components.chooseProton(it) },
            onComp = { components.compComp = it },
            onSwap = { file -> components.compProton?.let { pid -> components.componentAction(getString(R.string.comp_busy_swapping)) { ComponentsManager.swap(this, pid, file) } } },
            onRestore = { version -> components.compProton?.let { pid -> components.componentAction(getString(R.string.comp_busy_restoring)) { ComponentsManager.restore(this, pid, components.compComp, version) } } },
            onCancelQueued = { components.compProton?.let { pid -> components.componentAction(getString(R.string.comp_busy_cancelling)) { ComponentsManager.cancelQueued(this, pid, components.compComp); getString(R.string.comp_swap_cancelled) } } },
            onDeletePackage = { file -> components.componentAction(getString(R.string.comp_busy_deleting)) { ComponentsManager.deletePackage(this, file) } },
            onDeleteOriginal = { version -> components.compProton?.let { pid -> components.componentAction(getString(R.string.comp_busy_deleting)) { ComponentsManager.deleteOriginal(this, pid, components.compComp, version) } } },
            onDownload = { components.downloadComponent(it) },
            onRefresh = { components.refreshComponentCatalog() },
            onImport = { pickComponent.launch(InAppFilePicker.buildIntent(this, WCP_EXT, getString(R.string.comp_pick_wcp))) },
            onBack = { if (!com.droiddeck.launcher.ui.Hops.back { showComponents = false }) showComponents = false },
            games = steamGames,
            generalFexPreset = fexPreset,
            onGeneralFexPreset = { id -> SessionPrefs.setFexPreset(this, id); fexPreset = id },
            gpu = drivers.state(),
            gpuActions = com.droiddeck.launcher.ui.GpuDriversActions(
                onAuto = { on -> drivers.setMode(on) },
                onPair = { key -> drivers.selectPair(key) },
                onRefresh = { if (drivers.mode == SessionPrefs.GPU_DRIVERS_AUTO) drivers.ensureAuto(force = true) else drivers.checkLatestTurnip() },
                onSelectLinux = { id -> drivers.selectDriver(id, linux = true) },
                onSelectAndroid = { id -> drivers.selectDriver(id, linux = false) },
                onRemoveLinux = { id -> drivers.deleteDriver(id, linux = true) },
                onRemoveAndroid = { id -> drivers.deleteDriver(id, linux = false) },
                onDownloadDriver = { name -> drivers.downloadReleaseDriver(name) },
                onImportLinux = { pickLinuxDriver.launch(InAppFilePicker.buildIntent(this, ZIP_EXT, getString(R.string.drivers_pick_linux))) },
                onImportAndroid = { pickAndroidDriver.launch(InAppFilePicker.buildIntent(this, ZIP_EXT, getString(R.string.drivers_pick_android))) },
                onImportZip = { pickAnyDriver.launch(InAppFilePicker.buildIntent(this, ZIP_EXT, getString(R.string.drivers_pick_any))) },
                onRestoreBundled = { TurnipDriver(this).restoreBundled(); drivers.refreshDrivers() },
                onMode = { m -> drivers.chooseMode(m) },
                onRestart = { drivers.restartApp() },
            ),
            protons = com.droiddeck.launcher.ui.ProtonsState(
                rows = protons.protonRows, busyId = protons.protonBusyId, stage = protons.protonStage, percent = protons.protonPercent,
                runtimeReady = ready && !busy, sessionRunning = SessionState.running,
            ),
            protonActions = com.droiddeck.launcher.ui.ProtonsActions(
                onInstall = { id -> protons.installProton(id) },
                onCancel = { id -> ProtonExtras.tools.firstOrNull { it.id == id }?.let { ProtonExtras.unqueue(this, it) }; protons.refreshProtons() },
                onRemove = { id, dir -> protons.removeProton(id, dir) },
                onRemoveOlder = { id -> protons.removeProton(id, older = true) },
            ),
            protonOptions = {
                com.droiddeck.launcher.ui.ProtonOptions(
                    syncBackend = SessionPrefs.syncBackendOf(fastSync, fsyncFirst, syncFallback), fexPreset = fexPreset, forceSsbs = forceSsbs,
                    onSyncBackend = { id ->
                        SessionPrefs.setSyncBackend(this, id)
                        fastSync = SessionPrefs.fastSync(this)
                        fsyncFirst = SessionPrefs.fsyncFirst(this)
                        syncFallback = SessionPrefs.syncFallback(this)
                    },
                    onFexPreset = { id -> SessionPrefs.setFexPreset(this, id); fexPreset = id },
                    onForceSsbs = { on -> SessionPrefs.setForceSsbs(this, on); forceSsbs = on },
                )
            },
        )
        androidx.compose.runtime.LaunchedEffect(components.compDownloadRevision) { components.refreshComponents() }
        androidx.compose.runtime.LaunchedEffect(protons.protonBusyId) { if (protons.protonBusyId == null) components.refreshComponents() }
    }

    private fun refreshPackages() {
        packageRows = catalog?.map { PackageRow(it.id, it.kind, it.notes) }
        desktopInstalled = DesktopCatalog.desktopInstalled(this)
        desktopPresent = DesktopCatalog.desktopPresent(this)
    }

    private fun launchProgram(path: String) {
        startActivity(Intent(this, SessionActivity::class.java)
            .putExtra(SessionService.EXTRA_MODE, SessionService.MODE_RUN)
            .putExtra(SessionService.EXTRA_PROGRAM, path))
    }

    private fun installPackage(id: String) {
        val entry = catalog?.firstOrNull { it.id == id } ?: return
        if (busy || LinuxRuntimeInstaller.isBusy() || pkgStage != null || SessionState.running) return
        pkgId = id; pkgStage = getString(R.string.store_starting); pkgPercent = -1
        Thread({
            val problem = DesktopCatalog.install(this, entry) { stage, percent ->
                ui.post { pkgStage = stage; pkgPercent = percent }
            }
            ui.post {
                pkgStage = null; pkgId = null
                if (problem != null) android.widget.Toast.makeText(this, getString(R.string.main_pkg_problem, entry.name, problem), android.widget.Toast.LENGTH_LONG).show()
                refreshPackages()
                refresh()
            }
        }, "install-pkg").start()
    }

    private fun removePackage(id: String) {
        val entry = catalog?.firstOrNull { it.id == id } ?: return
        if (busy || LinuxRuntimeInstaller.isBusy() || pkgStage != null || SessionState.running) return
        pkgId = id; pkgStage = getString(if (entry.kind == "appimage") R.string.main_removing_named else R.string.main_forgetting_named, entry.name); pkgPercent = -1
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
    private fun ModeSheetHost(mode: String, tab: String, extras: com.droiddeck.launcher.ui.SheetExtras) {
        if (settingsMode != mode) return
        com.droiddeck.launcher.ui.ModeSheetRows(
            ModeSettings(
                mode = mode, resolution = resolution,
                panelSize = com.droiddeck.launcher.session.SessionDisplay.panelSize(this),
                hdr = hdrOn, hdrReason = hdrReason, fpsLimit = fpsLimit,
                upscaler = upscaler, upscaleSharpness = upscaleSharpness,
                gpuDrivers = drivers.summary(),
                touchMode = touchMode,
                suspendPolicy = suspendPolicy,
                steamDownloadsInBackground = steamDownloadsInBackground,
                zramCompression = if (mode == SessionService.MODE_STEAM) zramCompression else null,
                zramSupported = zramSupported,
                pipSupported = com.droiddeck.launcher.session.SessionPipController.supported(this),
                pipAutoEnter = pipAutoEnter,
                unfoldedControls = if (mode == SessionService.MODE_STEAM &&
                    com.droiddeck.launcher.session.SessionDisplay.foldable(this)) unfoldedControls else null,
                oscMode = if (mode == SessionService.MODE_STEAM) oscMode else null,
                backActionsInverted = backActionsInverted,
                directAudio = if (mode == SessionService.MODE_STEAM) directAudio else null,
                picturePosition = if (mode == SessionService.MODE_STEAM) picturePosition else null,
                stretch16x9 = if (mode == SessionService.MODE_STEAM) stretch16x9 else null,
                mic = if (mode == SessionService.MODE_STEAM) mic else null,
                gameStorage = if (mode == SessionService.MODE_STEAM) gameStorage else null,
                storageOptions = storageOptions,
                storageDiagnostics = mode == SessionService.MODE_STEAM && storageDiagnostics,
                fexPreset = if (mode == SessionService.MODE_STEAM) fexPreset else null,
                forceSsbs = forceSsbs,
                syncBackend = if (mode == SessionService.MODE_STEAM) SessionPrefs.syncBackendOf(fastSync, fsyncFirst, syncFallback) else null,
                steamClientArch = if (mode == SessionService.MODE_STEAM) steamClientArch else null,
                steamRepairQueued = steamRepairQueued,
                mangoapp = mangoapp,
                steamController = if (mode == SessionService.MODE_STEAM) steamController else null,
                runSteamAtStartup = mode == SessionService.MODE_STEAM && runSteamAtStartup,
                bootMovie = if (mode == SessionService.MODE_STEAM) bootMovie else null,
                bootMovieSound = bootMovieSound,
                bootMovieCustomName = bootMovieCustomName,
                bootMovieImporting = bootMovieImporting,
                bootMovieProgress = bootMovieProgress,
                wifiDiscovery = if (mode == SessionService.MODE_STEAM) wifiDiscovery else null,
                wifiDiscoveryPermission = wifiDiscoveryPermission,
                wifiDiscoveryLocation = wifiDiscoveryLocation,
                wifiDiscoveryAsked = wifiDiscoveryAsked,
                wifiDiscoveryBlocked = wifiDiscoveryBlocked,
                deckyInstalled = if (mode == SessionService.MODE_STEAM) decky.deckyInstalled else null,
                deckyLatestRelease = if (mode == SessionService.MODE_STEAM) decky.deckyReleases.firstOrNull() else null,
                deckyChecking = decky.deckyChecking, deckyStage = decky.deckyStage, deckyPercent = decky.deckyPercent,
                deckyDownloadingBinary = decky.deckyDownloadingBinary,
                deckyEnabled = decky.deckySupervisor, deckySessionRunning = SessionState.running,
            ),
            ModeSettingsActions(
                onWifiDiscovery = { on ->
                    if (!on) {
                        SessionPrefs.setWifiDiscoveryEnabled(this, false)
                        refreshWifiDiscovery()
                    } else if (WifiDiscovery.permissionGranted(this)) {
                        SessionPrefs.setWifiDiscoveryEnabled(this, true)
                        refreshWifiDiscovery()
                    } else if (wifiDiscoveryBlocked) {
                        openWifiDiscoverySettings()
                    } else {
                        SessionPrefs.setWifiDiscoveryAsked(this)
                        wifiLocationPermission.launch(WifiDiscovery.permissions)
                    }
                },
                onWifiDiscoverySettings = { openWifiDiscoverySettings() },
                onResolution = { value -> SessionPrefs.setResolutionChoice(this, mode, value); resolution = value },
                onHdr = { on -> SessionPrefs.setHdr(this, mode, on); hdrOn = on },
                onGpuDrivers = { openComponents(focusContent = true, tab = com.droiddeck.launcher.ui.GPU_TAB) },
                onFpsLimit = { fps -> SessionPrefs.setFpsLimit(this, mode, fps); fpsLimit = fps },
                onUpscaler = { m -> SessionPrefs.setUpscaler(this, m); upscaler = m },
                onUpscaleSharpness = { pct -> SessionPrefs.setUpscaleSharpness(this, pct); upscaleSharpness = pct },
                onTouch = { t -> SessionPrefs.setTouchMode(this, t); touchMode = t },
                onSuspendPolicy = { policy -> SessionPrefs.setSuspendPolicy(this, mode, policy); suspendPolicy = policy },
                onSteamDownloadsInBackground = { enabled ->
                    SessionPrefs.setSteamDownloadsInBackground(this, enabled)
                    steamDownloadsInBackground = enabled
                },
                onZramCompression = { on -> SessionPrefs.setZramCompression(this, on); zramCompression = on },
                onPipAutoEnter = { on -> SessionPrefs.setPipAutoEnter(this, on); pipAutoEnter = on },
                onUnfoldedControls = { on -> SessionPrefs.setUnfoldedControls(this, on); unfoldedControls = on },
                onOsc = { o -> SessionPrefs.setOscMode(this, o); oscMode = o },
                onBackActionsInverted = { inverted ->
                    SessionPrefs.setBackActionsInverted(this, inverted)
                    backActionsInverted = inverted
                },
                onDirectAudio = { on -> SessionPrefs.setDirectAudio(this, on); directAudio = on },
                onPicturePosition = { value -> SessionPrefs.setPicturePosition(this, value); picturePosition = value },
                onStretch16x9 = { on -> SessionPrefs.setStretch16x9(this, on); stretch16x9 = on },
                onMic = { on ->
                    SessionPrefs.setMicEnabled(this, on)
                    mic = on
                    if (on && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
                    }
                },
                onGameStorage = { path, label -> setGameStorage(path, label) },
                onPickGameStorageFolder = {
                    pickGameStorage.launch(InAppFilePicker.buildDirIntent(this, getString(R.string.main_pick_game_storage), gameStorage.ifEmpty { null }))
                },
                onStorageDiagnostics = { on ->
                    SessionPrefs.setStorageDiagnosticsEnabled(this, on)
                    storageDiagnostics = on
                },
                onFexPreset = { id -> SessionPrefs.setFexPreset(this, id); fexPreset = id },
                onForceSsbs = { on -> SessionPrefs.setForceSsbs(this, on); forceSsbs = on },
                onSyncBackend = { id ->
                    SessionPrefs.setSyncBackend(this, id)
                    fastSync = SessionPrefs.fastSync(this)
                    fsyncFirst = SessionPrefs.fsyncFirst(this)
                    syncFallback = SessionPrefs.syncFallback(this)
                },
                onSteamRepair = { steamRepairQueued = SteamRepair.queue(this) },
                onSteamClientArch = { id -> SessionPrefs.setSteamClientArch(this, id); steamClientArch = id; refresh() },
                onMangoapp = { on -> SessionPrefs.setMangoapp(this, on); mangoapp = on },
                onSteamController = { id -> SessionPrefs.setSteamController(this, id); steamController = id },
                onRunSteamAtStartup = { on ->
                    SessionPrefs.setRunSteamAtStartup(this, on)
                    runSteamAtStartup = on
                },
                onBootMovie = { id -> SessionPrefs.setBootMovie(this, id); loadBootMovie() },
                onBootMovieSound = { on -> SessionPrefs.setBootMovieSound(this, on); bootMovieSound = on },
                onImportBootMovie = {
                    pickBootMovie.launch(InAppFilePicker.buildIntent(this, com.droiddeck.launcher.session.BootMovie.PICKER_EXTENSIONS, getString(R.string.boot_movie_pick)))
                },
                onRemoveBootMovie = { com.droiddeck.launcher.session.BootMovie.removeCustom(this); loadBootMovie() },
                onDeckyInstall = { release -> decky.installDecky(release) },
                onDeckyCheck = { decky.refreshDecky() },
                onDeckyEnabled = { enabled -> DeckyManager.setSupervisorEnabled(this, enabled); decky.deckySupervisor = enabled },
                onDeckyUninstall = {
                    DeckyManager.uninstall(this, wipeData = false)
                    decky.deckyInstalled = null
                    decky.deckySupervisor = false
                },
                onPickDeckyPluginZip = {
                    pickDeckyPluginZip.launch(InAppFilePicker.buildIntent(this, ZIP_EXT, getString(R.string.main_pick_decky_zip)))
                },
                onDismiss = {},
            ),
            tab, extras,
        )
    }

    @Composable
    private fun PerformanceHost() {
        PerformancePage(
            cores = CpuCores.all.map { c -> CoreRow(c, "cpu$c" + (CpuCores.maxGhz(c)?.let { String.format(java.util.Locale.US, " · %.1f GHz", it) } ?: "")) },
            clientOverride = clientOverride, clientCores = clientCores, gameCores = gameCores,
            tuSysmem = tuSysmem, zinkLazy = zinkLazy, glThread = glThread, noGlError = noGlError, noXalia = noXalia,
            fastSync = fastSync,
            syncFallback = syncFallback,
            fsyncFirst = fsyncFirst,
            gamescopeRealtime = gamescopeRealtime,
            gpuClockPin = gpuClockPin,
            prootNoSeccomp = prootNoSeccomp, prootFastPath = prootFastPath, guestHostname = guestHostname, phantomWarning = phantomWarning,
            onClientOverride = { on -> SessionPrefs.setClientCpusOverride(this, on); clientOverride = on },
            onTuSysmem = { on -> SessionPrefs.setTuSysmem(this, on); tuSysmem = on },
            onZinkLazy = { on -> SessionPrefs.setZinkLazy(this, on); zinkLazy = on },
            onGlThread = { on -> SessionPrefs.setGlThread(this, on); glThread = on },
            onNoGlError = { on -> SessionPrefs.setNoGlError(this, on); noGlError = on },
            onNoXalia = { on -> SessionPrefs.setNoXalia(this, on); noXalia = on },
            onFastSync = { on -> SessionPrefs.setFastSync(this, on); fastSync = on },
            onSyncFallback = { on -> SessionPrefs.setSyncFallback(this, on); syncFallback = on },
            onFsyncFirst = { on -> SessionPrefs.setFsyncFirst(this, on); fsyncFirst = on },
            onGamescopeRealtime = { on -> SessionPrefs.setGamescopeRealtime(this, on); gamescopeRealtime = on },
            onGpuClockPin = { on -> SessionPrefs.setGpuClockPin(this, on); gpuClockPin = on },
            onProotNoSeccomp = { on -> SessionPrefs.setProotNoSeccomp(this, on); prootNoSeccomp = on },
            onProotFastPath = { on -> SessionPrefs.setProotFastPath(this, on); prootFastPath = on },
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

    private fun updateAddedGames(folders: List<String>, then: () -> Unit = {}) {
        if (folders.isEmpty()) return
        Thread({
            val fresh = folders.mapNotNull { f ->
                com.droiddeck.launcher.frontend.AddedGames.single(this, f)?.let { com.droiddeck.launcher.frontend.Library.addedGame(this, it) }
            }
            fresh.forEach { com.droiddeck.launcher.frontend.AutoComponents.refreshLater(this, it) }
            ui.post {
                val paths = folders.toSet()
                steamGames = (steamGames.filterNot { it.library == Library.ADDED && it.gameFiles?.path in paths } + fresh).distinctBy { it.gameId }
                then()
            }
        }, "added-games-update").start()
    }

    private val steamArtRefreshes = HashMap<Int, Int>()

    private fun refreshSteamGameArt(appId: Int) {
        val version = (steamArtRefreshes[appId] ?: 0) + 1
        steamArtRefreshes[appId] = version
        Thread({
            val fresh = com.droiddeck.launcher.frontend.Library.steamGames(this).firstOrNull { it.appId == appId } ?: return@Thread
            ui.post {
                if (steamArtRefreshes[appId] != version) return@post
                steamGames = steamGames.map { if (it.appId == appId && it.library != Library.ADDED) fresh else it }
            }
        }, "steam-art-update").start()
    }

    private fun removeAddedGame(folder: String, appId: Long?, name: String) {
        steamGames = steamGames.filterNot { it.library == Library.ADDED && it.gameFiles?.path == folder }
        Thread({ com.droiddeck.launcher.frontend.AddedGames.remove(this, java.io.File(folder), appId, name) }, "added-game-remove").start()
    }

    private fun refreshAddedGames() {
        sgdbKeyKnown()
        fetchAddedGameArt()
    }

    private fun sgdbKeyKnown() {
        Thread({
            val set = com.droiddeck.launcher.frontend.SteamGridDb.userKey(this).isNotEmpty()
            ui.post { sgdbUserKey = set }
        }, "sgdb-key-state").start()
    }

    private fun fetchAddedGameArt() {
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

    private var pipAutoEnter by mutableStateOf(false)
    private var unfoldedControls by mutableStateOf(false)

    private fun loadModeSettings(mode: String) {
        pipAutoEnter = SessionPrefs.pipAutoEnter(this)
        unfoldedControls = SessionPrefs.unfoldedControls(this)
        resolution = SessionPrefs.resolutionChoice(this, mode, com.droiddeck.launcher.session.SessionDisplay.panelSize(this))
        fexPreset = SessionPrefs.fexPreset(this)
        forceSsbs = SessionPrefs.forceSsbs(this)
        fastSync = SessionPrefs.fastSync(this)
        fsyncFirst = SessionPrefs.fsyncFirst(this)
        syncFallback = SessionPrefs.syncFallback(this)
        steamClientArch = SessionPrefs.steamClientArch(this)
        steamRepairQueued = SteamRepair.queued(this)
        mangoapp = SessionPrefs.mangoapp(this)
        steamController = SessionPrefs.steamController(this)
        runSteamAtStartup = SessionPrefs.runSteamAtStartup(this)
        loadBootMovie()
        hdrOn = SessionPrefs.hdr(this, mode)
        fpsLimit = SessionPrefs.fpsLimit(this, mode)
        upscaler = SessionPrefs.upscaler(this)
        upscaleSharpness = SessionPrefs.upscaleSharpness(this)
        hdrReason = com.droiddeck.launcher.wayland.HdrSupport.probe(this).reason
        touchMode = SessionPrefs.touchMode(this)
        suspendPolicy = SessionPrefs.suspendPolicy(this, mode)
        steamDownloadsInBackground = SessionPrefs.steamDownloadsInBackground(this)
        oscMode = SessionPrefs.oscMode(this)
        backActionsInverted = SessionPrefs.backActionsInverted(this)
        directAudio = SessionPrefs.directAudio(this)
        picturePosition = SessionPrefs.picturePosition(this)
        stretch16x9 = SessionPrefs.stretch16x9(this)
        mic = SessionPrefs.micEnabled(this)
        refreshWifiDiscovery()
        gameStorage = SessionPrefs.gameStorage(this)
        storageDiagnostics = SessionPrefs.storageDiagnosticsEnabled(this)
        settingsMode = mode
        Thread({
            val zram = com.droiddeck.launcher.core.ZramSupport.supported()
            val zramOn = SessionPrefs.zramCompression(this)
            ui.post { zramSupported = zram; zramCompression = zramOn }
            drivers.refreshDrivers()
            val storage = GameStorage.options(this)
            val deckyInstalled = if (mode == SessionService.MODE_STEAM) DeckyManager.installed(this) else null
            val deckySupervisor = mode == SessionService.MODE_STEAM && DeckyManager.supervisorEnabled(this)
            ui.post {
                storageOptions = storage
                if (mode == SessionService.MODE_STEAM) {
                    decky.deckyInstalled = deckyInstalled
                    decky.deckySupervisor = deckySupervisor
                    decky.refreshDecky()
                }
                fetchAddedGameArt()
            }
        }, "mode-settings").start()
    }

    private fun setGameStorage(path: String, label: String) {
        if (path.isNotEmpty() && path != SessionPrefs.GAME_STORAGE_OFF) {
            val problem = GameStorage.prepare(this, path)
            if (problem != null) {
                android.widget.Toast.makeText(this, getString(R.string.main_storage_not_usable, problem), android.widget.Toast.LENGTH_LONG).show()
                return
            }
        }
        SessionPrefs.setGameStorage(this, path, label)
        gameStorage = path
    }

    private fun refreshCores() {
        clientOverride = SessionPrefs.clientCpusOverride(this)
        glThread = SessionPrefs.glThread(this)
        noGlError = SessionPrefs.noGlError(this)
        clientCores = CpuCores.parse(SessionPrefs.clientCpus(this)).ifEmpty { CpuCores.all.toSet() }
        gameCores = CpuCores.parse(SessionPrefs.gameCpus(this)).ifEmpty { CpuCores.all.toSet() }
        tuSysmem = SessionPrefs.tuSysmem(this)
        zinkLazy = SessionPrefs.zinkLazy(this)
        noXalia = SessionPrefs.noXalia(this)
        fastSync = SessionPrefs.fastSync(this)
        syncFallback = SessionPrefs.syncFallback(this)
        fsyncFirst = SessionPrefs.fsyncFirst(this)
        gamescopeRealtime = SessionPrefs.gamescopeRealtime(this)
        gpuClockPin = SessionPrefs.gpuClockPin(this)
        prootNoSeccomp = SessionPrefs.prootNoSeccomp(this)
        prootFastPath = SessionPrefs.prootFastPath(this)
        guestHostname = SessionPrefs.guestHostname(this)
        refreshPhantomStatus()
    }

    private fun refresh() {
        // Until the scan finishes, the other client's games would launch in this one.
        val listX64 = SessionPrefs.activeSteamClientArch(this) == "x86_64"
        if (listX64 != steamGamesX64) {
            steamGamesX64 = listX64
            steamGames = steamGames.filterNot(Library::fromSteamClient)
        }
        if (!busy && LinuxRuntimeInstaller.isBusy()) followRuntimeOperation { LinuxRuntimeInstaller.attach(it) }
        if (showComponents) drivers.refreshDrivers()
        addedGamesArt = SessionPrefs.addedGamesArt(this)
        desktopInstalled = DesktopCatalog.desktopInstalled(this)
        desktopPresent = DesktopCatalog.desktopPresent(this)
        offlineAccount = OfflineMode.account(this)
        offline = OfflineMode.enabled(this)
        installed = LinuxRuntimeInstaller.installedVersion(this)
        ready = LinuxRuntime.isInstalled(this)
        removalPending = LinuxRuntimeInstaller.hasRemovalPending(this)
        frameGenLabel = FrameGen.label(this)
        romsDir = SessionPrefs.romsDir(this).takeIf { it.isNotEmpty() }
        logsEnabled = SessionPrefs.logsEnabled(this)
        vacDiagnostics = SessionPrefs.vacDiagnostics(this)
        agentCommands = com.droiddeck.launcher.agent.AgentAccess.commandsEnabled(this)
        runningLabel = if (SessionState.running) when (SessionState.mode) {
            SessionService.MODE_DESKTOP -> getString(R.string.rail_desktop)
            SessionService.MODE_RUN -> Library.nameForProgram(SessionState.program)
                ?: SessionState.program?.substringAfterLast('/')?.substringBefore('.') ?: getString(R.string.main_running_program)
            else -> "Steam"
        } else null
        val scanGeneration = ++libraryScanGeneration
        val artRefreshesAtStart = HashMap(steamArtRefreshes)
        if (shortcutPicker) shortcutLibraryScanning = true
        if (SessionPrefs.steamClientArch(this) == "x86_64" && !OfflineMode.enabled(this)) {
            Thread({ com.droiddeck.launcher.session.FexCatalog.refresh(this) }, "fex-catalog").start()
        }
        Thread({
            val emus = Library.emulators(this) { id -> DesktopCatalog.installed(this, id) != null }
            ui.post { if (scanGeneration == libraryScanGeneration) emulatorList = emus }
            if (ready) {
                val quick = runCatching { Library.steamGames(this) }.getOrNull()
                if (quick != null) ui.post {
                    if (scanGeneration == libraryScanGeneration && steamGames.isNotEmpty()) {
                        val before = steamGames.map { it.gameId }.toSet()
                        val fresh = quick.filter { it.gameId !in before }
                        if (fresh.isNotEmpty()) {
                            steamGames = (quick + steamGames.filter { it.library == Library.ADDED }).distinctBy { it.gameId }
                            fresh.forEach { com.droiddeck.launcher.frontend.AutoComponents.queueGame(this, it, asked = false) }
                        }
                    }
                }
            }
            val scannedArch = SessionPrefs.activeSteamClientArch(this)
            val scannedX64 = scannedArch == "x86_64"
            val games = if (ready) Library.launchableGames(this, arch = scannedArch) else emptyList()
            val all = games.distinctBy { it.gameId }
            if (ready && scanGeneration == libraryScanGeneration) com.droiddeck.launcher.frontend.LibraryCache.save(this, all, scannedX64)
            val endedSession = SessionState.sessionId.takeIf { !SessionState.running && it != null && it != componentSweepSession }
            if (ready && (!componentSweepDone || endedSession != null)) {
                componentSweepDone = true
                endedSession?.let { componentSweepSession = it }
                ui.postDelayed({ Thread({ com.droiddeck.launcher.frontend.AutoComponents.sweep(this, all) }, "components-sweep").start() }, 10_000)
            }
            ui.post {
                if (scanGeneration == libraryScanGeneration) {
                    val current = steamGames.filter { it.library != Library.ADDED }.associateBy { it.appId }
                    steamGamesX64 = scannedX64
                    steamGames = all.map { g ->
                        val newer = current[g.appId]
                        if (newer != null && g.library != Library.ADDED && steamArtRefreshes[g.appId] != artRefreshesAtStart[g.appId]) g.withArt(newer.art, newer.hero, newer.icon) else g
                    }
                    shortcutLibraryScanning = false
                    handleGameLink(all)
                }
            }
            if (!OfflineMode.enabled(this) && CoverArt.fetchMissing(this, emus.flatMap { it.games })) {
                val refreshed = Library.emulators(this) { id -> DesktopCatalog.installed(this, id) != null }
                ui.post { emulatorList = refreshed }
            }
        }, "library").start()
    }

    private fun onRuntimeButton() {
        if (runtimeChangesBlocked()) return
        val release = available
        if (removalPending || installed != null && release?.version == installed) {
            showRemove = true
            return
        }
        if (release == null) {
            Thread({ checkCatalog() }, "catalog").start()
            return
        }
        if (installed == null && !com.droiddeck.launcher.core.DeviceSupport.adreno()) { showNonAdreno = release; return }
        install(release)
    }

    private fun startSession(intent: Intent, steamSession: Boolean = false): Boolean {
        if (busy || LinuxRuntimeInstaller.isBusy()) return false
        if (com.droiddeck.launcher.storage.StorageState.cleaning) {
            android.widget.Toast.makeText(this, R.string.storage_cleanup_busy, android.widget.Toast.LENGTH_SHORT).show()
            return false
        }
        refreshPhantomStatus()
        if (steamSession && PhantomProcessLimit.blocksSteam(phantomProcessStatus)) {
            showPhantomGate = true
            return false
        }
        val warn = installed == null && !com.droiddeck.launcher.core.DeviceSupport.adreno()
        if (warn && available != null) { showNonAdreno = available; return false }
        startActivity(intent)
        return true
    }

    private fun refreshPhantomStatus() {
        phantomProcessStatus = PhantomProcessLimit.read(this)
        phantomWarning = if (PhantomProcessLimit.blocksSteam(phantomProcessStatus)) {
            getString(
                R.string.phantom_warning, PhantomProcessLimit.title(this, phantomProcessStatus),
                PhantomProcessLimit.instructions(this, phantomProcessStatus), PhantomProcessLimit.adbCommand(),
            )
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
                android.widget.Toast.makeText(this, R.string.main_settings_main_screen, android.widget.Toast.LENGTH_LONG).show()
            } else {
                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                android.widget.Toast.makeText(this, R.string.main_open_dev_options, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun runtimeChangesBlocked(): Boolean =
        busy || LinuxRuntimeInstaller.isBusy() || SessionState.running ||
            SessionState.phase !in setOf(SessionPhase.IDLE, SessionPhase.FAILED) ||
            protons.protonBusyId != null || ProtonExtras.installInProgress || pkgStage != null ||
            components.compBusy != null || saveBusy != null || com.droiddeck.launcher.store.UserAppsState.working != null

    private fun removeDesktop() {
        if (runtimeChangesBlocked()) return
        val operation = LinuxRuntimeInstaller.beginMaintenance(this, getString(R.string.desktop_removing)) ?: return
        com.droiddeck.launcher.runtime.RuntimeInstallService.keepRemovalAlive(this)
        followRuntimeOperation { listener ->
            operation.run(listener) { progress -> DesktopCatalog.removeDesktop(this, progress) }
        }
    }

    private fun resetSettings(gameProfiles: Boolean) {
        if (SessionState.running) return
        val scopes = steamGames.map { it.profileKey }
        Thread({
            val ok = com.droiddeck.launcher.session.SettingsReset.resetAll(this, gameProfiles, scopes)
            ui.post {
                if (isDestroyed) return@post
                com.droiddeck.launcher.core.AppLanguage.setChosen(this, com.droiddeck.launcher.core.AppLanguage.SYSTEM)
                if (!ok) android.widget.Toast.makeText(this, R.string.setup_reset_settings_partial, android.widget.Toast.LENGTH_LONG).show()
                com.droiddeck.launcher.ui.Motion.refresh(this)
                recreate()
            }
        }, "reset-settings").start()
    }

    private fun removeRuntime() {
        if (runtimeChangesBlocked()) return
        val removal = LinuxRuntimeInstaller.beginUninstall(this) ?: return
        ready = false
        com.droiddeck.launcher.runtime.RuntimeInstallService.keepRemovalAlive(this)
        followRuntimeOperation { removal.run(it) }
    }

    private fun install(release: LinuxRuntimeInstaller.Release) {
        com.droiddeck.launcher.runtime.RuntimeInstallService.start(this, release)
        followRuntimeOperation { listener -> LinuxRuntimeInstaller.install(this, release, listener) }
    }

    private fun followRuntimeOperation(run: (LinuxRuntimeInstaller.ProgressListener) -> Boolean?) {
        busy = true
        failed = false
        stage = getString(if (LinuxRuntimeInstaller.isMaintaining()) R.string.desktop_removing else if (LinuxRuntimeInstaller.isRemoving()) R.string.main_removing_runtime else R.string.store_starting)
        percent = -1
        Thread({
            val ok = run(LinuxRuntimeInstaller.ProgressListener { s, p ->
                ui.post { stage = s; percent = p }
            })
            ui.post {
                busy = false
                if (ok != null) failed = !ok
                if (ok == false) LinuxRuntimeInstaller.removalError()?.let {
                    android.widget.Toast.makeText(this, it, android.widget.Toast.LENGTH_LONG).show()
                }
                refresh()
            }
        }, "runtime-operation").start()
    }

    private fun checkCatalog() {
        val release = LinuxRuntimeInstaller.fetchRelease()
        Log.i(TAG, "catalog: " + (release?.version ?: "unreachable"))
        ui.post { if (release != null) available = release }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val MEDIA_SETTLE_MS = 1500L
        private const val SESSION_SWITCH_WAIT_MS = 30_000L
        private const val SESSION_SWITCH_POLL_MS = 250L
        private const val KEY_PENDING_SWITCH = "pending_switch"
        private const val KEY_SWITCHING_TO = "switching_to"
        private const val KEY_SWITCH_DEADLINE = "switch_deadline"
        private val SESSION_ENDED = setOf(SessionPhase.IDLE, SessionPhase.FAILED)
        private val ZIP_EXT = listOf("zip")
        private val WCP_EXT = listOf("wcp")
    }
}
