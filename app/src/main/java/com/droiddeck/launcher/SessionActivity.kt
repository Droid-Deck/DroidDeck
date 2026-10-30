package com.droiddeck.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import android.hardware.input.InputManager
import android.hardware.display.DisplayManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.Display
import android.view.SurfaceHolder
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.core.TextureFiltering
import com.droiddeck.launcher.files.InAppFilePicker
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.agent.AgentGuest
import com.droiddeck.launcher.frontend.LibraryCache
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAchievements
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.StoreLaunchRequests
import com.droiddeck.launcher.stores.epic.EpicClient
import com.droiddeck.launcher.ui.SessionAchievements
import com.droiddeck.launcher.gpu.Cardboard
import com.droiddeck.launcher.gpu.FrameGen
import com.droiddeck.launcher.gpu.HeadTracker
import com.droiddeck.launcher.glasses.Glasses
import com.droiddeck.launcher.glasses.GlassesScreen
import com.droiddeck.launcher.glasses.GlassesRendering
import com.droiddeck.launcher.glasses.SpatialTracking
import com.droiddeck.launcher.glasses.TrackingRecovery
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.droiddeck.launcher.gpu.SteamVr
import com.droiddeck.launcher.gpu.ScreenEffectLooks
import com.droiddeck.launcher.gpu.ScreenEffects
import com.droiddeck.launcher.gpu.Lossless
import com.droiddeck.launcher.gpu.TurnipDriver
import com.droiddeck.launcher.input.EvdevKeys
import com.droiddeck.launcher.input.HomeGuideInput
import com.droiddeck.launcher.input.InputSpace
import com.droiddeck.launcher.input.KeyboardHost
import com.droiddeck.launcher.input.ControllerPrefs
import com.droiddeck.launcher.input.SessionClipboard
import com.droiddeck.launcher.input.OnScreenControls
import com.droiddeck.launcher.input.PadBridge
import com.droiddeck.launcher.input.PadMouse
import com.droiddeck.launcher.input.PadState
import com.droiddeck.launcher.input.ScreenKeys
import com.droiddeck.launcher.input.SteamControllerBle
import com.droiddeck.launcher.input.DirectTouchHold
import com.droiddeck.launcher.input.GestureTouchStreamGate
import com.droiddeck.launcher.input.PointerGestures
import com.droiddeck.launcher.input.SecondScreenDisplay
import com.droiddeck.launcher.input.SecondScreenDisplays
import com.droiddeck.launcher.input.SecondScreenMode
import com.droiddeck.launcher.input.TouchpadGestures
import com.droiddeck.launcher.input.TouchscreenGestures
import com.droiddeck.launcher.input.ScrollTouchGestures
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.LoadingState
import com.droiddeck.launcher.hud.FusionHudConfig
import com.droiddeck.launcher.hud.FusionHudHost
import com.droiddeck.launcher.session.PerfMode
import com.droiddeck.launcher.session.SteamRepair
import com.droiddeck.launcher.session.GameEnvironmentStore
import com.droiddeck.launcher.session.GameProfileFollowState
import com.droiddeck.launcher.session.GameProfileInventoryCache
import com.droiddeck.launcher.session.GameProfileRefreshState
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionEvents
import com.droiddeck.launcher.session.SessionArtifacts
import com.droiddeck.launcher.session.SessionLogShare
import com.droiddeck.launcher.session.SessionPhase
import com.droiddeck.launcher.session.SessionPaths
import com.droiddeck.launcher.wayland.HdrSupport
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.session.ProtonDefault
import com.droiddeck.launcher.session.SelectedGameProfile
import com.droiddeck.launcher.session.WinComponents
import com.droiddeck.launcher.ui.CursorOverlay
import androidx.compose.ui.graphics.asImageBitmap
import com.droiddeck.launcher.ui.DrawerActions
import com.droiddeck.launcher.ui.HudText
import com.droiddeck.launcher.ui.LoadingOverlay
import com.droiddeck.launcher.ui.SessionDrawer
import com.droiddeck.launcher.ui.SessionPausedOverlay
import com.droiddeck.launcher.ui.SecondScreenPresentation
import com.droiddeck.launcher.ui.DroidDeckTheme
import com.droiddeck.launcher.wayland.CompositorHost
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.io.File
import com.droiddeck.launcher.session.ComponentsManager
import com.droiddeck.launcher.session.LatestRequestCoordinator
import com.droiddeck.launcher.ui.SessionDrawerPage
import com.droiddeck.launcher.ui.sessionDrawerPages
import com.droiddeck.launcher.ui.step
import kotlin.math.abs

class SessionActivity : ComponentActivity(), SurfaceHolder.Callback {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        com.droiddeck.launcher.core.AppLanguage.applyTo(this, newBase)
    }

    private lateinit var pip: com.droiddeck.launcher.session.SessionPipController
    private var pipUi by mutableStateOf(false)
    private var pipAutoEnter by mutableStateOf(false)
    private var unfoldedControls by mutableStateOf(false)
    private var unfoldedControlsWithController by mutableStateOf(false)
    private var picturePosition by mutableStateOf("center")

    private fun applyGameAlignment() {
        if (!CompositorHost.isStarted) return
        val active = SessionState.mode == SessionService.MODE_STEAM &&
            !pipUi && !isInPictureInPictureMode && !cardboard.enabled
        val alignment = if (!active) ALIGN_CENTER else when (picturePosition) {
            "top" -> ALIGN_PICTURE_TOP
            "bottom" -> ALIGN_PICTURE_BOTTOM
            else -> ALIGN_CENTER
        }
        WaylandCompositor.nativeSetScaleMode(SCALE_FIT, alignment)
    }
    private var unfoldedPanel: com.droiddeck.launcher.ui.DeckControlsPanel? = null
    private lateinit var sessionRoot: FrameLayout
    private val guestKeysDown = mutableSetOf<Int>()
    private lateinit var surfaceView: SurfaceView
    private lateinit var sessionOverlay: ComposeView
    private lateinit var loading: LoadingState
    private lateinit var hud: FusionHudHost
    private lateinit var memoryHud: com.droiddeck.launcher.session.MemoryHud
    private var padBridge: PadBridge? = null
    private var joyConVr: com.droiddeck.launcher.input.JoyConVr? = null
    private var handCamera: com.droiddeck.launcher.handtracking.HandCamera? = null
    private var padMotion: com.droiddeck.launcher.input.PadMotion? = null
    private var resumed = false
    private val deckPadListener: () -> Unit = { uiHandler.post { updatePadMotion(); openDeckRing("deck pad changed"); applyUnfoldedControls() } }

    private fun openDeckRing(why: String) {
        val deck = SessionState.deckPad
        val opened = if (deck) padBridge?.start() else null
        Log.i(TAG, "deck ring ($why): deck pad $deck" + when (opened) {
            null -> ", left for first input"
            true -> ", ring open"
            false -> ", ring NOT open"
        })
    }

    private fun updatePadMotion() {
        if (resumed && !pipUi && SessionState.deckPad && !SteamControllerBle.connected) padMotion?.start()
        else padMotion?.stop()
    }
    private var steamController: SteamControllerBle? = null
    private var onScreenControls: OnScreenControls? = null
    private var steamTouchControls: com.droiddeck.launcher.input.SteamTouchControls? = null
    private var steamTouchAvailable by mutableStateOf(false)
    private val steamTouchRetry = Runnable { updateOnScreenControls() }
    private var steamTouchOptions: com.droiddeck.launcher.input.SteamTouchConfig.Options? = null
    private var routedTouchMode = SessionPrefs.TOUCH_AUTO
    private var touchRoute: com.droiddeck.launcher.input.TouchRouter.Route? = null
    private var keyboard: KeyboardHost? = null
    private var desktopKeyboardButton: com.droiddeck.launcher.input.DesktopKeyboardButton? = null
    private var lastUserPressMs = 0L
    private var pressDownX = 0f
    private var pressDownY = 0f
    private var pressTravel = 0f
    private var pressMoved = false
    private var controllerSettings by mutableStateOf<ControllerPrefs.Settings?>(null)
    private val sessionClipboard by lazy {
        SessionClipboard(this) {
            window.decorView.hasWindowFocus() || secondScreenPresentation?.window?.decorView?.hasWindowFocus() == true
        }
    }
    private var watching = true
    private lateinit var touchpad: TouchpadGestures
    private lateinit var directTouchHold: DirectTouchHold
    private lateinit var twoFingerGestures: com.droiddeck.launcher.input.TwoFingerGestures
    private lateinit var threeFingerTap: com.droiddeck.launcher.input.ThreeFingerTap
    private lateinit var touchscreenGestures: TouchscreenGestures
    private var gestureConfig = com.droiddeck.launcher.input.TouchGestureConfig()
    private var gestureGame: String? = null
    private var gestureSettingsOpen by mutableStateOf(false)
    private var steamImportAsk by mutableStateOf<com.droiddeck.launcher.session.SteamImportPrompt.Ask?>(null)
    private var steamImportAnswered: String? = null
    private var directHoldBehavior by mutableStateOf(com.droiddeck.launcher.input.TouchGestureConfig.MOUSE_BEHAVIOR_CLICK)
    private var screenKeyPickerOpen by mutableStateOf(false)
    private var screenKeyBind: List<Int>? = null
    private var screenKeyDir: Int? = null
    private var screenKeyTextFocus = false
    private var screenKeyEdit by mutableStateOf<com.droiddeck.launcher.input.ScreenKey?>(null)
    private var screenLayoutsOpen by mutableStateOf(false)
    private var screenKeysShown by mutableStateOf(true)
    private var screenLayoutNames by mutableStateOf(listOf<String>())
    private var screenKeys: ScreenKeys? = null
    private var recognizedGesture by mutableStateOf<TouchscreenGestures.Gesture?>(null)
    private var gesturePointerX = 0f
    private var gesturePointerY = 0f
    private var gesturePointerReady = false
    private val gestureTouchStreamGate = GestureTouchStreamGate()
    private val clearRecognizedGesture = Runnable { recognizedGesture = null }
    private val gestureKeysDown = mutableSetOf<Int>()
    private lateinit var scrollTouch: ScrollTouchGestures
    private lateinit var padMouse: PadMouse
    @Volatile private var padMouseOn = false
    private var padMouseCheckedAt = 0L
    private var scrollTouchGesture = false
    private var scrollRestX = 0f
    private var scrollRestY = 0f
    private var touchMode by mutableStateOf(SessionPrefs.TOUCH_AUTO)
    private var cursorPos by mutableStateOf(androidx.compose.ui.geometry.Offset(-100f, -100f))
    private var cursorVisible by mutableStateOf(false)
    private var floodColor by mutableStateOf<Int?>(null)
    private val cursorHide = Runnable { cursorVisible = false }
    private var lastPadInputMs = 0L
    private val cursorBuf = IntArray(WaylandCompositor.CURSOR_BUF_INTS)
    private val cursorPosBuf = IntArray(2)
    private var cursorSerial = 0
    private var clientHidesCursor = false
    private var pointerLocked = false
    private val pointerLockListener = WaylandCompositor.PointerLockListener { locked, _, _ ->
        uiHandler.post {
            if (pointerLocked == locked) return@post
            gesturePointerReady = false
            pointerLocked = locked
            Log.i(TAG, "pointer lock " + if (locked) "taken by a program" else "released")
            if (locked) openEvdevMouse() else closeEvdevMouse()
            padMouseCheckedAt = 0L
            if (::padMouse.isInitialized) padMouseMode()
            // Touchpad deltas are measured from the middle while locked (moveLockedPointer).
            if (locked && ::touchpad.isInitialized) {
                drawnRect()?.let { touchpad.place(it.centerX(), it.centerY()) }
                cursorVisible = false
            }
            updatePointerCapture()
        }
    }
    private var cursorImage by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    private var cursorHotX by mutableStateOf(0)
    private var cursorHotY by mutableStateOf(0)
    private var cursorImageScale by mutableStateOf(1f)
    private var cursorResyncIdle = 0
    private val cursorResync = object : Runnable {
        override fun run() {
            if (syncClientCursor() && clientHidesCursor) {
                cursorVisible = false
                return
            }
            if (touchDrivesPointer()) return
            cursorResyncIdle = if (alignCursorToGuest()) 0 else cursorResyncIdle + 1
            if (!cursorVisible || pointerLocked) return
            if (cursorResyncIdle > CURSOR_RESYNC_IDLE_TICKS) return
            uiHandler.postDelayed(this, 60)
        }
    }

    private fun alignCursorToGuest(): Boolean {
        if (pointerLocked || !CompositorHost.isStarted) return false
        if (!WaylandCompositor.nativeCursorInputPos(cursorPosBuf)) return false
        if (surfaceView.width <= 0 || surfaceView.height <= 0) return false
        val x = cursorPosBuf[0] / InputSpace.WIDTH.toFloat() * surfaceView.width
        val y = cursorPosBuf[1] / InputSpace.HEIGHT.toFloat() * surfaceView.height
        if (kotlin.math.abs(x - cursorPos.x) < 0.75f && kotlin.math.abs(y - cursorPos.y) < 0.75f) return false
        cursorPos = androidx.compose.ui.geometry.Offset(x, y)
        return true
    }
    private val uiHandler = Handler(Looper.getMainLooper())
    private var pendingBackAction: Runnable? = null
    private val backQamInput = com.droiddeck.launcher.input.BackQamInput(
        setPressed = { padBridge?.setBackQamPressed(it) },
        onTap = { performBackAction(double = it) },
        doubleTapMs = { backDoubleTapMs.toLong() },
        onCancel = { padBridge?.cancelBackQam() },
    )
    private var drawerDirectionKey = KeyEvent.KEYCODE_UNKNOWN
    private var drawerDirectionDownTime = 0L
    private var drawerDirectionDeviceId = -1
    private var drawerDirectionRepeatCount = 0
    private val drawerFirstRepeatDelayMs = ViewConfiguration.getKeyRepeatTimeout().toLong()
    private val drawerSlowRepeatIntervalMs = maxOf(
        ViewConfiguration.getKeyRepeatDelay().toLong(), DRAWER_SLOW_REPEAT_FLOOR_MS,
    )
    private val drawerDirectionRepeat = object : Runnable {
        override fun run() {
            if (drawerDirectionKey == KeyEvent.KEYCODE_UNKNOWN) return

            val now = SystemClock.uptimeMillis()
            val heldMs = now - drawerDirectionDownTime
            val progress = ((heldMs - drawerFirstRepeatDelayMs).toFloat() / DRAWER_REPEAT_ACCELERATION_MS)
                .coerceIn(0f, 1f)
            val intervalMs = (
                drawerSlowRepeatIntervalMs +
                    (DRAWER_FAST_REPEAT_INTERVAL_MS - drawerSlowRepeatIntervalMs) * progress
                ).toLong()

            dispatchDrawerKey(KeyEvent.ACTION_DOWN, ++drawerDirectionRepeatCount, now)
            uiHandler.postDelayed(this, intervalMs)
        }
    }
    private val resumeKeysDown = mutableSetOf<Pair<Int, Int>>()
    private val drawerShortcutKeysDown = mutableSetOf<Pair<Int, Int>>()

    private var drawerOpenState by mutableStateOf(false)
    private var typingReturnMode: SecondScreenMode? = null
    private var drawerDocked by mutableStateOf(false)
    private var drawerLayerOpen = false
    private var drawerBackRequests by mutableIntStateOf(0)
    private var drawerOpen: Boolean
        get() = drawerOpenState
        set(value) {
            if (value == drawerOpenState) return
            drawerLayerOpen = false
            if (value) drawerDocked = dockDrawer() && openDockedMenu()
            drawerOpenState = value
            if (!value && drawerDocked) closeDockedMenu()
        }
    private var quitFrom by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    private var quitFlooded = false
    private var leaving by mutableStateOf(false)
    private var closeAtOnce = false
    private var drawerComponents by mutableStateOf<ComponentsManager.Snapshot?>(null)
    private var drawerDefaultProton by mutableStateOf<String?>(null)
    private val drawerComponentMenu by lazy { ComponentsMenu(this, uiHandler) }
    private var drawerGameProfileId by mutableStateOf<Long?>(null)

    private fun noSteamSession(): Boolean = SessionState.mode == SessionService.MODE_RUN &&
        SessionState.program in setOf(com.droiddeck.launcher.session.NoSteamLaunch.PROTON_RUN, com.droiddeck.launcher.session.NoSteamLaunch.EPIC)
    private val drawerGameInventory = GameProfileInventoryCache<Library.SteamGame>()
    private var drawerGameInventoryRevision = 0L
    private var drawerGameFexPresetSaving by mutableStateOf(false)
    private var drawerGameNoSteamSaving by mutableStateOf(false)
    private val drawerGameProfileFollowState = GameProfileFollowState()
    private data class DrawerGameProfileRefresh(
        val followsSteam: Boolean,
        val requestedAppId: Long?,
        val inventoryRevision: Long,
    )
    private data class DrawerGameProfileResult(
        val profiles: List<Pair<Long, String>>,
        val selectedAppId: Long?,
        val profile: SelectedGameProfile?,
        val error: Exception? = null,
    )
    private var drawerGameProfileRefresh by mutableStateOf(GameProfileRefreshState<DrawerGameProfileResult>())
    private val drawerGameProfileRefreshes = LatestRequestCoordinator<DrawerGameProfileRefresh>()
    private var drawerPage by mutableStateOf(SessionDrawerPage.CONTROLLER)
    private var drawerControllerActive by mutableStateOf(false)
    private var backActionsInverted by mutableStateOf(false)
    private var backDoubleTapMs by mutableStateOf(500)
    private var lowLatency by mutableStateOf(true)
    private var evdevMouseOn by mutableStateOf(false)
    private var directCursorOn by mutableStateOf(false)
    private var mouseWriter: com.droiddeck.launcher.input.FakeInputWriter? = null
    private var evdevMouseActive = false
    private var lastMouseX = Float.NaN
    private var lastMouseY = Float.NaN
    private var padType by mutableStateOf(SessionPrefs.CONTROLLER_DECK)
    private var pcKeyboardOpen by mutableStateOf(false)
    private var achievementsInFront by mutableStateOf<AchievementsInFront?>(null)
    private var achievementsOpen by mutableStateOf(false)
    private var hudOn by mutableStateOf(true)
    private var hudConfig by mutableStateOf<Map<String, String>>(emptyMap())
    private var hudColor by mutableStateOf(SessionPrefs.HUD_COLOR_MINT)
    private var hudOpacity by mutableStateOf(SessionPrefs.HUD_OPACITY_DEFAULT)
    private var upscaler by mutableStateOf(0)
    private var upscaleSharpness by mutableStateOf(75)
    private var effects by mutableStateOf(ScreenEffects.OFF)
    private var cardboard by mutableStateOf(Cardboard.OFF)
    private var headTracker: HeadTracker? = null
    private var steamVrStatus by mutableStateOf<SteamVr.Status?>(null)
    private var steamVrSwitchedPicture by mutableStateOf(false)
    private var steamVrSeenRunning = false
    private val steamVrRecovery by lazy { com.droiddeck.launcher.gpu.SteamVrRecovery(this) {
        resumed && !ended && SessionState.running && !isInPictureInPictureMode
    } }
    private var activeCardboard = Cardboard.OFF
    private var glasses by mutableStateOf(Glasses.state.value)
    private var glassesSettings by mutableStateOf(Glasses.settings.value)
    private var glassesScreen: GlassesScreen? = null
    private var glassesOutputActive by mutableStateOf(false)
    private var pausedFrame by mutableStateOf<com.droiddeck.launcher.ui.PausedFrame?>(null)
    private var pausedFrameWanted = false
    private var pauseAnimated by mutableStateOf(false)
    private var trackingRecovery by mutableStateOf<TrackingRecovery?>(null)
    private var glasses3dForVr = false
    private var ended = false
    private var glassesPresent = false
    private var steamVrAutoTried = false
    private var steamVrInstallTried = false
    @Volatile private var steamVrRequestGeneration = 0L
    private var headTracking by mutableStateOf(false)
    private var glassesSlot by mutableStateOf(0)
    private var glassesKey: List<Any?> = emptyList()
    private val glassesScope = kotlinx.coroutines.MainScope()
    private val hasRotationSensor by lazy {
        (getSystemService(SENSOR_SERVICE) as android.hardware.SensorManager).let {
            it.getDefaultSensor(android.hardware.Sensor.TYPE_GAME_ROTATION_VECTOR)
                ?: it.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)
        } != null
    }
    private val cardboardPrefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SessionPrefs.CARDBOARD_KEY) uiHandler.post { applyCardboard(SessionPrefs.cardboard(this)) }
    }
    private var textureAnisotropy by mutableStateOf(0)
    private var textureLodBias by mutableStateOf(TextureFiltering.LOD_BIAS_OFF)
    private var drawerTextureAnisotropy by mutableStateOf<Int?>(null)
    private var drawerTextureLodBias by mutableStateOf<String?>(null)
    private var lossless by mutableStateOf(Lossless.State.NONE)
    private val pickLossless = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) InAppFilePicker.pickedFile(r.data)?.let { importLossless(it) }
    }
    private var frameGen by mutableStateOf(FrameGen.Mode.OFF)
    private var fexPreset by mutableStateOf("")
    private var suspendPolicy by mutableStateOf(SessionPrefs.SUSPEND_MANUAL)
    private var steamDownloadsInBackground by mutableStateOf(false)
    private var oscMode by mutableStateOf(SessionPrefs.OSC_AUTO)
    private var onScreenButtonsVisible by mutableStateOf(false)
    private var secondScreenMode by mutableStateOf(SessionState.secondScreenMode)
    private var secondScreenDisplays by mutableStateOf<List<SecondScreenDisplay>>(emptyList())
    private var selectedSecondScreenDisplay by mutableStateOf(SessionState.secondScreenDisplay)
    private var started = false
    private var sessionActivityDisplayId = Display.DEFAULT_DISPLAY
    private lateinit var displayManager: DisplayManager
    private var secondScreenPresentation: SecondScreenPresentation? = null
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            notifyActivityDisplayStateChanged(displayId)
            refreshSecondScreenDisplays()
        }
        override fun onDisplayRemoved(displayId: Int) {
            if (refreshActivityDisplayId() && started) {
                SessionService.setActivityDisplay(this@SessionActivity, sessionActivityDisplayId)
            }
            refreshSecondScreenDisplays()
        }
        override fun onDisplayChanged(displayId: Int) {
            notifyActivityDisplayStateChanged(displayId)
            followPanelRefresh(displayId)
            refreshSecondScreenDisplays()
        }
    }

    @Suppress("DEPRECATION")
    private fun currentActivityDisplayId(): Int = windowManager.defaultDisplay.displayId

    private fun refreshActivityDisplayId(): Boolean {
        val current = if (Build.VERSION.SDK_INT >= 30) display?.displayId ?: currentActivityDisplayId()
            else currentActivityDisplayId()
        val changed = sessionActivityDisplayId != current
        sessionActivityDisplayId = current
        SessionState.activityDisplayId = current
        return changed
    }

    private fun notifyActivityDisplayStateChanged(changedDisplayId: Int) {
        val changed = refreshActivityDisplayId()
        if (started && (changed || changedDisplayId == sessionActivityDisplayId)) {
            SessionService.setActivityDisplay(this, sessionActivityDisplayId)
        }
    }

    // A higher refresh rate allowed in Android's settings does not change the display while this window
    // still asks for a lower mode, so the mode is chosen again when the setting changes.
    private val refreshSettingObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            applyOutputPacing()
        }
    }

    private fun followPanelRefresh(displayId: Int) {
        if (!CompositorHost.isStarted) return
        val output = glassesScreen?.display
            ?: (if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay) ?: return
        if (output.displayId != displayId) return
        applyOutputPacing()
    }
    private var isHomeApp by mutableStateOf(false)
    private var androidApps by mutableStateOf<List<HomeApp.LaunchableApp>>(emptyList())

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            logInputDevice("connected", deviceId)
            joyConVr?.refresh()
            padBridge?.syncConnectedControllers()
            refreshControls()
            updatePointerCapture()
            updateHandCamera()
        }
        override fun onInputDeviceRemoved(deviceId: Int) {
            Log.i(TAG, "input device $deviceId disconnected")
            backQamInput.onDeviceRemoved(deviceId)
            joyConVr?.refresh()
            padBridge?.onDeviceRemoved(deviceId)
            if (::padMouse.isInitialized) padMouse.reset()
            refreshControls()
            updatePointerCapture()
            updateHandCamera()
        }
        override fun onInputDeviceChanged(deviceId: Int) {
            joyConVr?.refresh()
            refreshControls()
        }
    }

    private var runtimeRemovalBlocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (com.droiddeck.launcher.runtime.LinuxRuntimeInstaller.isRemoving() ||
            com.droiddeck.launcher.runtime.LinuxRuntimeInstaller.isMaintaining()) {
            runtimeRemovalBlocked = true
            android.widget.Toast.makeText(this, R.string.session_wait_removal, android.widget.Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        // A cancelled install keeps its thread until the download stops; a new launch would reuse its stop flag and close itself.
        if (savedInstanceState == null && SessionState.installing != null && !SessionState.running) {
            runtimeRemovalBlocked = true
            android.widget.Toast.makeText(this, R.string.main_wait_desktop_install, android.widget.Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (!SessionState.running && SessionState.phase in setOf(SessionPhase.IDLE, SessionPhase.FAILED)) {
            val mode = intent.getStringExtra(SessionService.EXTRA_MODE) ?: SessionService.MODE_STEAM
            SessionState.programArgs = intent.getStringArrayExtra(SessionService.EXTRA_PROGRAM_ARGS)?.toList().orEmpty()
            SessionEvents.begin(this, mode, SessionPaths.label(mode, intent.getStringExtra(SessionService.EXTRA_PROGRAM)))
        }
        if (!SessionState.running && SessionState.phase in setOf(SessionPhase.IDLE, SessionPhase.FAILED)) {
            SessionState.program = intent.getStringExtra(SessionService.EXTRA_PROGRAM)
            SessionState.programArgs = intent.getStringArrayExtra(SessionService.EXTRA_PROGRAM_ARGS)?.toList().orEmpty()
            SessionState.steamUi = intent.getStringExtra(SessionService.EXTRA_STEAM_UI)
            SessionState.steamUrl = intent.getStringExtra(SessionService.EXTRA_STEAM_URL)
            SessionState.steamVr = intent.getBooleanExtra(EXTRA_STEAMVR, false)
        } else if (intent.getBooleanExtra(EXTRA_STEAMVR, false) && SessionState.mode == SessionService.MODE_STEAM) {
            SessionState.steamVr = true
        }
        if (SessionState.steamVr) Glasses.prepareSteamVrStart()
        if (intent.action == SessionService.ACTION_AGENT_START) {
            SessionEvents.record("agent.start_requested", mapOf("mode" to SessionState.mode))
        }
        com.droiddeck.launcher.gpu.SteamVrHealth.begin(this, SessionState.sessionId)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyOutputPacing()
        if (!SessionState.running) SessionState.followScreen = followChoice()
        applyFollowOrientation(resources.configuration)
        goFullscreen()
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        refreshSecondScreenDisplays()

        if (!LinuxRuntime.isInstalled(this)) Log.i(TAG, "the Linux runtime is not installed; the loading screen installs it")

        val root = object : FrameLayout(this) {
            override fun dispatchCapturedPointerEvent(event: MotionEvent): Boolean =
                onCapturedPointer(event) || super.dispatchCapturedPointerEvent(event)
            override fun dispatchPointerCaptureChanged(hasCapture: Boolean) {
                super.dispatchPointerCaptureChanged(hasCapture)
                Log.i(TAG, "pointer capture " + if (hasCapture) "on" else "off")
            }
        }
        sessionRoot = root
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        root.addView(surfaceView)
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) uiHandler.post { applyUnfoldedControls() }
        }

        val bridge = PadBridge(File(LinuxRuntime.sessionRoot(this), "dev/input"))
        bridge.setSelectSteam(ControllerPrefs.read(this).selectSteam)
        bridge.setBuiltinPad(SessionState.mode != SessionService.MODE_STEAM || SessionPrefs.builtinPad(this))
        padBridge = bridge
        bridge.setOnChordInput(backQamInput::onChordInput)
        SessionState.padBridge = bridge
        if (Build.VERSION.SDK_INT >= 31) {
            val vr = com.droiddeck.launcher.input.JoyConVr(this, File(filesDir, ".wayland-rt/${com.droiddeck.launcher.input.JoyConVr.FILE}")) { taken, released ->
                if (joyConVr == null) return@JoyConVr
                taken.forEach(bridge::onDeviceRemoved)
                if (released.isNotEmpty()) bridge.syncConnectedControllers()
                updateHandCamera()
            }
            bridge.setExcluded(vr::owns)
            joyConVr = vr
        }
        SteamControllerBle.listener = { uiHandler.post { updatePadMotion(); refreshControls() } }
        steamController = SteamControllerBle(this).also { it.start() }
        padMotion = com.droiddeck.launcher.input.PadMotion(this) {
            @Suppress("DEPRECATION")
            (if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay)?.rotation ?: android.view.Surface.ROTATION_0
        }
        SessionState.deckPadListener = deckPadListener
        openDeckRing("activity created")
        bridge.setOnPlayerInput {
            lastPadInputMs = android.os.SystemClock.uptimeMillis()
            uiHandler.removeCallbacks(cursorHide)
            cursorVisible = false
        }
        onScreenControls = OnScreenControls(this, bridge, onKeyboard = ::togglePcKeyboard).also { controls ->
            controls.onTouchMiss = miss@{ event ->
                if (!gestureTouchStreamGate.accepts(GestureTouchStreamGate.Source.ON_SCREEN_CONTROLS, event)) return@miss
                event.offsetLocation(controls.left - surfaceView.left.toFloat(), controls.top - surfaceView.top.toFloat())
                onTouchEvent(event)
            }
            root.addView(controls)
        }
        screenKeysShown = com.droiddeck.launcher.input.ControllerPrefs.screenKeysShown(this)
        screenKeys = ScreenKeys(this) { code, down -> if (CompositorHost.isStarted) sendGuestKey(code, down) }.also { keys ->
            keys.onTouchMiss = miss@{ event ->
                if (!gestureTouchStreamGate.accepts(GestureTouchStreamGate.Source.SCREEN_KEYS, event)) return@miss
                event.offsetLocation(keys.left - surfaceView.left.toFloat(), keys.top - surfaceView.top.toFloat())
                onTouchEvent(event)
            }
            keys.onAdd = { screenKeyPickerOpen = true }
            keys.onSelect = { codes -> screenKeyEdit = screenKeys?.stateOf(codes) }
            keys.onEditingChanged = { if (!it) screenKeyEdit = null }
            keys.steamStyle = { steamTouchControls?.style }
            keys.visibility = if (screenKeysShown) View.VISIBLE else View.GONE
            root.addView(keys)
        }
        keyboard = KeyboardHost(this).also { host ->
            host.onImeChanged = { updateDesktopKeyboardButton() }
            root.addView(host)
        }
        desktopKeyboardButton = com.droiddeck.launcher.input.DesktopKeyboardButton(this) { keyboard?.show() }
            .also { root.addView(it) }
        touchpad = TouchpadGestures(PointerGestures.slop(this), pointerListener)
        directTouchHold = DirectTouchHold(PointerGestures.slop(this), pointerListener, directTouchSink)
        threeFingerTap = com.droiddeck.launcher.input.ThreeFingerTap(PointerGestures.slop(this) * 2)
        twoFingerGestures = com.droiddeck.launcher.input.TwoFingerGestures(PointerGestures.slop(this) * 2,
            object : com.droiddeck.launcher.input.TwoFingerGestures.Listener {
                override fun cancelTouch() {
                    WaylandCompositor.nativeSendTouch(3, -1, 0, 0)
                    directTouchHold.cancel()
                    scrollRestX = 0f
                    scrollRestY = 0f
                }
                override fun replay(event: MotionEvent) {
                    drawnRect()?.let { forwardTouch(event, it) }
                }
                override fun rightClick(x: Float, y: Float) {
                    pointerListener.onLongPress()
                    pointerListener.onButton(PointerGestures.BTN_RIGHT, true, x, y)
                    surfaceView.postDelayed({ pointerListener.onButton(PointerGestures.BTN_RIGHT, false, x, y) }, 50L)
                }
                override fun scrollStart(x: Float, y: Float) = scrollListener.onMove(x, y)
                override fun scroll(dx: Float, dy: Float) = fingerScroll(dx, dy)
                override fun scrollEnd() = fingerScrollEnd()
            })
        touchscreenGestures = TouchscreenGestures(
            ViewConfiguration.get(this).scaledTouchSlop.toFloat(), gestureOutput,
        )
        refreshGestureConfig()
        // A real touch target lets FrameLayout split fingers between the
        // game surface and on-screen buttons, in either finger-down order.
        root.isMotionEventSplittingEnabled = true
        surfaceView.setOnTouchListener { _, event ->
            if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
                false
            } else if (!gestureTouchStreamGate.accepts(GestureTouchStreamGate.Source.SURFACE, event)) {
                false
            } else onTouchEvent(event)
        }
        padMouse = PadMouse(padMouseListener)
        bridge.setTouchRoute(object : PadBridge.TouchRoute {
            override fun active(): Boolean = if (Looper.myLooper() == Looper.getMainLooper()) padMouseMode() else padMouseOn
            override fun accept(state: PadState) {
                if (Looper.myLooper() == Looper.getMainLooper()) padMouse.onTouchPad(state)
                else PadState().also { it.copyFrom(state) }.let { copy -> uiHandler.post { padMouse.onTouchPad(copy) } }
            }
        })
        scrollTouch = ScrollTouchGestures(PointerGestures.slop(this),
            android.view.ViewConfiguration.get(this).scaledMinimumFlingVelocity.toFloat(), scrollListener)
        val noCursor = android.view.PointerIcon.getSystemIcon(this, android.view.PointerIcon.TYPE_NULL)
        root.pointerIcon = noCursor
        surfaceView.pointerIcon = noCursor

        loading = LoadingState(this, steam = loadingMode() == SessionService.MODE_STEAM)
        if (!SessionState.running) {
            val repair = intent.getBooleanExtra(EXTRA_REPAIR_RUNTIME, false)
            intent.removeExtra(EXTRA_REPAIR_RUNTIME)
            val needRuntime = repair || com.droiddeck.launcher.runtime.LinuxRuntimeInstaller.installedVersion(this) == null
            val needResume = !needRuntime && com.droiddeck.launcher.runtime.DesktopCatalog.interrupted(this).isNotEmpty()
            val needDesktop = intent.getStringExtra(SessionService.EXTRA_MODE) == SessionService.MODE_DESKTOP &&
                (repair || !com.droiddeck.launcher.runtime.DesktopCatalog.desktopInstalled(this))
            val program = intent.getStringExtra(SessionService.EXTRA_PROGRAM)
            val protonUser = (intent.getStringExtra(SessionService.EXTRA_MODE) ?: SessionService.MODE_STEAM) == SessionService.MODE_STEAM ||
                program == com.droiddeck.launcher.runtime.BattleNet.PROGRAM || program == com.droiddeck.launcher.session.NoSteamLaunch.PROTON_RUN
            val needProton = protonUser && com.droiddeck.launcher.runtime.DesktopCatalog.protonSeedNeeded(this)
            if (needRuntime || needResume || needDesktop || needProton) installThenStart(needRuntime, needDesktop, needProton, needResume)
        }
        hud = FusionHudHost(this)
        hud.onConfigChanged = { hudConfig = FusionHudConfig.load(this) }
        hud.passesTouchAt = ::controlTakesTouchAt
        hud.attach(root, root.childCount)
        memoryHud = com.droiddeck.launcher.session.MemoryHud(this)
        hud.onPresentingWindowChanged = {
            if (FrameGen.engine(this) != FrameGen.ENGINE_OFF) CompositorHost.rearmFrameGen { applyFrameGen() }
        }
        if (SessionState.running && SessionState.firstFrameSeen) {
            loading.visible = false
            hud.start()
            memoryHud.start()
        }
        readPrefs()

        intent.getIntExtra(com.droiddeck.launcher.ui.EXTRA_FLOOD, 0).takeIf { it != 0 && loading.visible }?.let {
            floodColor = it
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(it))
        }
        sessionOverlay = createSessionOverlay()
        root.addView(sessionOverlay)
        setContentView(root)
        glassesScope.launch { Glasses.state.collect {
            glasses = it
            onGlassesChanged()
            updateJoyConTracking()
            updateHandCamera()
        } }
        glassesScope.launch { Glasses.settings.collect {
            glassesSettings = it
            onGlassesChanged()
            updateJoyConTracking()
            updateHandCamera()
        } }
        glassesScope.launch { com.droiddeck.launcher.handtracking.HandTracking.settings.collect { updateHandCamera() } }
        glassesScope.launch {
            while (true) {
                updateTrackingRecovery()
                kotlinx.coroutines.delay(100)
            }
        }
        glassesScope.launch {
            while (true) {
                if (SessionState.running && SessionState.mode == SessionService.MODE_STEAM &&
                    (SessionState.steamVr || steamVrStatus == SteamVr.Status.RUNNING)) {
                    val lost = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.droiddeck.launcher.gpu.SteamVrHealth.deviceLost()
                    }
                    if (lost) steamVrRecovery.detected()
                }
                kotlinx.coroutines.delay(2000)
            }
        }
        pip = com.droiddeck.launcher.session.SessionPipController(this, ::pipBounds, ::showPipUi)
        surfaceView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> pip.refresh() }
        pip.refresh()
        handleHomeGuideIntent(intent)

        updateOnScreenControls()
        WaylandCompositor.setFirstFrameListener(firstFrameListener)
        WaylandCompositor.setPointerLockListener(pointerLockListener)
        SessionEvents.markReadyIfPossible()
        SessionState.endListener = endListener
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (pipUi) return
                when {
                    ::loading.isInitialized && loading.ended -> finish()
                    drawerOpen -> drawerOpen = false
                    achievementsOpen -> closeAchievements()
                    pcKeyboardOpen -> pcKeyboardOpen = false
                    else -> routeBackAction()
                }
            }
        })
        watchSession()
    }

    private fun createSessionOverlay(): ComposeView = ComposeView(this).apply {
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
        setContent {
            androidx.compose.runtime.SideEffect {
                com.droiddeck.launcher.glasses.GlassesMenuMirror.setSessionMenuVisible(this@SessionActivity,
                    (drawerOpen || achievementsOpen || pcKeyboardOpen) && !pipUi && steamVrStatus != SteamVr.Status.RUNNING)
            }
            androidx.compose.runtime.DisposableEffect(Unit) {
                onDispose { com.droiddeck.launcher.glasses.GlassesMenuMirror.setSessionMenuVisible(this@SessionActivity, false) }
            }
            androidx.compose.runtime.LaunchedEffect(drawerOpen, pcKeyboardOpen, achievementsOpen, pipUi) { updatePointerCapture() }
            androidx.compose.runtime.LaunchedEffect(pipUi) { hud.setHidden(pipUi) }
            androidx.compose.runtime.LaunchedEffect(AgentGuest.steamKeyboard) { updateOnScreenControls() }
            androidx.compose.runtime.LaunchedEffect(AgentGuest.desktopTextField) { onDesktopTextField(AgentGuest.desktopTextField) }
            androidx.compose.runtime.LaunchedEffect(pipUi, pcKeyboardOpen) { updateDesktopKeyboardButton() }
            val keyboardRequestsSeen = androidx.compose.runtime.remember { intArrayOf(AgentGuest.keyboardRequests) }
            androidx.compose.runtime.LaunchedEffect(AgentGuest.keyboardRequests) {
                if (AgentGuest.keyboardRequests != keyboardRequestsSeen[0]) {
                    keyboardRequestsSeen[0] = AgentGuest.keyboardRequests
                    openBottomKeyboard()
                }
            }
            val keyboardDismissalsSeen = androidx.compose.runtime.remember { intArrayOf(AgentGuest.keyboardDismissals) }
            androidx.compose.runtime.LaunchedEffect(AgentGuest.keyboardDismissals) {
                if (AgentGuest.keyboardDismissals != keyboardDismissalsSeen[0]) {
                    keyboardDismissalsSeen[0] = AgentGuest.keyboardDismissals
                    secondScreenPresentation?.closeFieldKeyboard()
                }
            }
            androidx.compose.runtime.LaunchedEffect(picturePosition, SessionState.running, pipUi, cardboard.enabled) { applyGameAlignment() }
            DroidDeckTheme {
            if (pipUi) {
                if (SessionState.suspended) androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    androidx.compose.material3.Text(getString(R.string.pip_suspended),
                        color = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.7f)).padding(6.dp))
                }
            } else {
            com.droiddeck.launcher.ui.FocusGlideHost(androidx.compose.ui.Modifier.fillMaxSize()) {
                CursorOverlay({ cursorPos }, cursorVisible, resources.displayMetrics.density,
                    cursorImage, cursorHotX, cursorHotY, cursorImageScale)
                if (memoryHud.text.isNotEmpty()) HudText(memoryHud.text, hudColor, hudOpacity)
                val phonePanel = glassesOutputActive && !loading.visible && !drawerOpen && !pcKeyboardOpen
                if (phonePanel) GlassesPhonePanel(glasses, steamVrStatus == SteamVr.Status.RUNNING, headTracking)
                if (trackingRecovery != null && !drawerOpen && !pcKeyboardOpen && !loading.visible) {
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { com.droiddeck.launcher.ui.TrackingRecoveryView(it) },
                        modifier = Modifier.fillMaxSize(),
                        update = { it.update(trackingRecovery, !glassesOutputActive && glasses.usable && glasses.sbs) },
                    )
                }
                if (!phonePanel && GlassesRendering.usesGlasses(glasses, glassesOutputActive, steamVrStatus == SteamVr.Status.RUNNING) &&
                    headTracking && !drawerOpen && !pcKeyboardOpen && !loading.visible) com.droiddeck.launcher.ui.GlassesRecenterButton(
                    controllerSettings?.tint ?: android.graphics.Color.rgb(90, 160, 255), controllerSettings?.opacity ?: 100,
                    onRecenterJoyCons = if (steamVrStatus == SteamVr.Status.RUNNING &&
                        glasses.usesSpatialControllers(glassesSettings) && glassesSettings.joyConTracking)
                        joyConVr?.let { { it.recenter() } } else null,
                ) { headTracker?.recenter() }
                if (!phonePanel && glasses.present && !drawerOpen && !pcKeyboardOpen && !loading.visible) com.droiddeck.launcher.ui.GlassesCornerButton(glassesSlot,
                    controllerSettings?.tint ?: android.graphics.Color.rgb(90, 160, 255), controllerSettings?.opacity ?: 100) {
                    drawerPage = com.droiddeck.launcher.ui.SessionDrawerPage.GLASSES
                    drawerOpen = true
                }
                if (loading.visible) LoadingOverlay(
                    loading.step, loading.percent, loading.elapsed, loading.hint, loading.ended,
                    topic = loading.topic, readable = loading.readable,
                    title = loadingTitle(), steam = loadingMode() == SessionService.MODE_STEAM,
                    onCancel = { SessionService.stop(this@SessionActivity); finish() },
                    endedDetail = loading.endedDetail,
                    onRetry = { retrySession() },
                    onRepair = if (repairOffered) ({ repairRuntime() }) else null,
                    onShareLogs = { shareCurrentSessionLogs() },
                    onClose = { finish() },
                    flood = floodColor?.let { androidx.compose.ui.graphics.Color(it) },
                    onFlooded = {
                        floodColor = null
                        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
                    },
                )
                LaunchedEffect(AgentGuest.focusRevision) {
                    val nextGame = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        if (SessionState.mode == SessionService.MODE_RUN) SessionState.program?.let { "program:$it" }
                        else {
                            val snapshot = AgentGuest.focus(this@SessionActivity)
                            val focused = snapshot?.optLong("focusedApp")?.takeIf { it > 0 && it != 769L }
                            val windows = snapshot?.optJSONArray("focusableApps")
                            focused?.toString() ?: windows?.let { values ->
                                (0 until values.length()).map { values.optLong(it) }
                                    .filter { it > 0 && it != 769L }.distinct().singleOrNull()?.toString()
                            }
                        }
                    }
                    if (nextGame != gestureGame) { gestureGame = nextGame; refreshGestureConfig() }
                }
                LaunchedEffect(drawerOpen, pcKeyboardOpen, achievementsOpen, gestureSettingsOpen, pipUi, SessionState.suspended) {
                    if (drawerOpen || pcKeyboardOpen || achievementsOpen || gestureSettingsOpen || pipUi || SessionState.suspended) {
                        backQamInput.cancel()
                        com.droiddeck.launcher.input.HomeButtonShortcuts.cancel(this@SessionActivity)
                        cancelTouchGestures()
                        screenKeys?.stopEditing()
                    }
                }
                com.droiddeck.launcher.ui.GestureFeedback(recognizedGesture)
                LaunchedEffect(Unit) {
                    val root = LinuxRuntime.rootDir(this@SessionActivity)
                    while (true) {
                        val ask = if (SessionState.running && SessionState.mode == SessionService.MODE_STEAM) {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.droiddeck.launcher.session.SteamImportPrompt.read(root) }
                        } else null
                        steamImportAsk = ask?.takeIf { it.token != steamImportAnswered }
                        kotlinx.coroutines.delay(1500)
                    }
                }
                steamImportAsk?.takeIf { !loading.visible }?.let { ask ->
                    androidx.compose.runtime.key(ask.token) {
                        com.droiddeck.launcher.ui.SteamImportDialog(ask.games) { now ->
                            val root = LinuxRuntime.rootDir(this@SessionActivity)
                            Thread({
                                if (com.droiddeck.launcher.session.SteamImportPrompt.answer(root, ask.token, now)) runOnUiThread {
                                    steamImportAnswered = ask.token
                                    if (steamImportAsk?.token == ask.token) steamImportAsk = null
                                }
                            }, "steam-import-answer").start()
                        }
                    }
                }
                if (gestureSettingsOpen) com.droiddeck.launcher.ui.TouchGestureDialog(
                    game = gestureGame,
                    onDismiss = { gestureSettingsOpen = false },
                    onSaved = { gestureSettingsOpen = false; refreshGestureConfig() },
                )
                if (screenKeyPickerOpen) {
                    val dir = screenKeyDir
                    com.droiddeck.launcher.ui.ScreenKeyPicker(
                        initial = if (dir != null) {
                            listOfNotNull((screenKeyEdit?.codes?.getOrNull(dir) ?: 0).takeIf { it != 0 })
                        } else {
                            screenKeyBind ?: emptyList()
                        },
                        onDismiss = { screenKeyPickerOpen = false; screenKeyBind = null; screenKeyDir = null },
                        onAdd = { codes ->
                            val bind = screenKeyBind
                            if (dir != null && bind != null) {
                                screenKeys?.setDirection(bind, dir, codes.lastOrNull() ?: 0)?.let { screenKeyEdit = it }
                            } else if (bind != null) {
                                screenKeys?.setCodes(bind, codes)?.let { screenKeyEdit = it }
                            } else {
                                screenKeys?.add(codes)
                                screenKeyEdit = screenKeys?.stateOf(codes)
                            }
                            screenKeyPickerOpen = false
                            screenKeyBind = null
                            screenKeyDir = null
                        },
                    )
                }
                screenKeyEdit?.let { key ->
                    com.droiddeck.launcher.ui.ScreenKeyEditor(
                        codes = key.codes, keyY = key.y,
                        size = key.size, opacity = key.opacity, shape = key.shape, toggle = key.toggle,
                        labels = key.labels,
                        text = key.text, hasImage = key.image.isNotEmpty(),
                        onResize = { screenKeys?.resize(key.codes, it); screenKeyEdit = screenKeys?.stateOf(key.codes) },
                        onOpacity = { screenKeys?.setOpacity(key.codes, it); screenKeyEdit = screenKeys?.stateOf(key.codes) },
                        onShape = { screenKeys?.setShape(key.codes, it); screenKeyEdit = screenKeys?.stateOf(key.codes) },
                        onToggle = { on -> screenKeys?.setToggle(key.codes, on); screenKeyEdit = screenKeys?.stateOf(key.codes) },
                        onLabels = { on -> screenKeys?.setLabels(key.codes, on); screenKeyEdit = screenKeys?.stateOf(key.codes) },
                        onDirection = { index -> screenKeyBind = key.codes; screenKeyDir = index; screenKeyPickerOpen = true },
                        onText = { screenKeys?.setText(key.codes, it); screenKeyEdit = screenKeys?.stateOf(key.codes) },
                        onImage = { screenKeys?.setImage(key.codes, it); screenKeyEdit = screenKeys?.stateOf(key.codes) },
                        onClearImage = { screenKeys?.setImage(key.codes, ""); screenKeyEdit = screenKeys?.stateOf(key.codes) },
                        onBind = { screenKeyBind = key.codes; screenKeyDir = null; screenKeyPickerOpen = true },
                        onTextFocus = { screenKeyTextFocus = true; keyboard?.hide() },
                        onTextBlur = { screenKeyTextFocus = false },
                        onRemove = { screenKeys?.remove(key.codes); screenKeyEdit = null },
                        onDismiss = { screenKeyEdit = null; screenKeyTextFocus = false; screenKeys?.deselect() },
                    )
                }
                if (screenLayoutsOpen) com.droiddeck.launcher.ui.ScreenLayoutPanel(
                    layouts = screenLayoutNames,
                    onSave = { name ->
                        screenKeys?.let { keys ->
                            com.droiddeck.launcher.input.ControllerPrefs.saveScreenLayout(this@SessionActivity, name, keys.export())
                        }
                        screenLayoutNames = layoutNames()
                    },
                    onLoad = { name ->
                        com.droiddeck.launcher.input.ControllerPrefs.screenLayouts(this@SessionActivity)[name]?.let { screenKeys?.load(it) }
                        screenLayoutsOpen = false
                    },
                    onDelete = { name ->
                        com.droiddeck.launcher.input.ControllerPrefs.deleteScreenLayout(this@SessionActivity, name)
                        screenLayoutNames = layoutNames()
                    },
                    onImport = { text ->
                        val width = (screenKeys?.width ?: 0).takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
                        val keys = com.droiddeck.launcher.input.importScreenLayout(text, width, resources.displayMetrics.density)
                        if (keys != null) {
                            screenKeys?.load(com.droiddeck.launcher.input.ControllerPrefs.encodeScreenKeys(keys))
                            screenLayoutsOpen = false
                        }
                    },
                    exportText = {
                        val width = (screenKeys?.width ?: 0).takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
                        val keys = com.droiddeck.launcher.input.ControllerPrefs.decodeScreenKeys(screenKeys?.export().orEmpty())
                        com.droiddeck.launcher.input.exportScreenLayout(keys, width, resources.displayMetrics.density)
                    },
                    onDismiss = { screenLayoutsOpen = false },
                )
                androidx.compose.runtime.LaunchedEffect(drawerOpen) {
                    if (drawerOpen) {
                        syncLossless()
                        releasePads()
                        androidx.compose.runtime.withFrameNanos { }
                        val requested = focusDrawer()
                        Log.i(TAG, "drawer root focus requested=$requested docked=$drawerDocked")
                    } else {
                        releaseDrawerDirection()
                    }
                }
                val glassesVr = glasses.usable && glasses.canControl
                val steamVrRequested = SessionState.steamVr
                val pollSteamVr = SteamVr.shouldPoll(steamVrStatus, cardboard.enabled,
                    steamVrSwitchedPicture, glassesVr, steamVrRequested) || (drawerOpen && drawerPage == SessionDrawerPage.VR)
                LaunchedEffect(pollSteamVr) {
                    while (pollSteamVr) {
                        val request = steamVrRequestGeneration
                        val session = SessionState.sessionId
                        val now = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            SteamVr.status(this@SessionActivity)
                        }
                        if (request == steamVrRequestGeneration && session == SessionState.sessionId) onSteamVrStatus(now)
                        kotlinx.coroutines.delay(3000)
                    }
                }
                LaunchedEffect(drawerOpen, achievementsOpen, AgentGuest.focusRevision, StoreAchievements.revision) {
                    if (!drawerOpen && !achievementsOpen) return@LaunchedEffect
                    val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { resolveAchievementsInFront() }
                            .onFailure { Log.w(TAG, "achievements in front: ${it.javaClass.simpleName}") }.getOrNull()
                    }
                    achievementsInFront = found
                    if (found == null) achievementsOpen = false
                }
                androidx.compose.runtime.LaunchedEffect(pcKeyboardOpen) { if (pcKeyboardOpen) releasePads(); updateOnScreenControls() }
                LaunchedEffect(pcKeyboardOpen, drawerOpen, achievementsOpen) {
                    if (pcKeyboardOpen || drawerOpen || achievementsOpen) padMouse.reset()
                }
                if (pcKeyboardOpen) com.droiddeck.launcher.ui.PcKeyboard(
                    sendKey = { code, down -> if (CompositorHost.isStarted) sendGuestKey(code, down) },
                    onAndroidKeyboard = { pcKeyboardOpen = false; keyboard?.toggle() },
                    onClose = { pcKeyboardOpen = false },
                )
                if (!drawerDocked) SessionMenu(docked = false)
                LaunchedEffect(achievementsOpen) {
                    if (achievementsOpen) {
                        releasePads()
                        withFrameNanos { }
                        sessionOverlay.requestFocus()
                    }
                }
                achievementsInFront?.takeIf { achievementsOpen }?.let { inFront ->
                    SessionAchievements(inFront.game, inFront.title, onClose = ::closeAchievements)
                }
                com.droiddeck.launcher.ui.AchievementNotificationOverlay()
                // Auto and Native resume on their own as the session comes back; only a failed resume needs the button.
                val autoResume = suspendPolicy == SessionPrefs.SUSPEND_AUTO || suspendPolicy == SessionPrefs.SUSPEND_NATIVE
                LaunchedEffect(SessionState.manualPause, SessionState.suspended) {
                    if (!SessionState.manualPause && !SessionState.suspended) pauseAnimated = false
                }
                SessionPausedOverlay(
                    suspended = SessionState.suspended && (!autoResume || SessionState.resumeFailed || SessionState.manualPause),
                    engaged = SessionState.resumeRequested || SessionState.resuming,
                    failures = SessionState.resumeFailures,
                    frame = pausedFrame,
                    animateIn = pauseAnimated,
                    title = pausedTitle(),
                    onResume = { SessionService.resume(this@SessionActivity) },
                    onFrameNeeded = ::requestPausedFrame,
                    onHidden = {
                        pausedFrame = null
                        pausedFrameWanted = false
                        pauseAnimated = false
                    },
                )
                if (leaving) com.droiddeck.launcher.ui.FloodSpread(
                    com.droiddeck.launcher.ui.LocalPalette.current.signal,
                    ball = if (loading.visible) com.droiddeck.launcher.ui.ThrobberSpot.ball else null,
                ) { completeLeave() }
                quitFrom?.let { from ->
                    val error = androidx.compose.material3.MaterialTheme.colorScheme.error
                    com.droiddeck.launcher.ui.LaunchFlood(
                        from, onProgress = {},
                        fromColors = error to androidx.compose.ui.graphics.lerp(error, androidx.compose.ui.graphics.Color.Black, 0.12f),
                        cornerAtRest = 20.dp,
                    ) { finishFlooded() }
                }
            }
        }
        }
        }
    }

    @androidx.compose.runtime.Composable
    private fun SessionMenu(docked: Boolean) {
        SessionDrawer(drawerOpen, drawerPage, drawerControllerActive, onPageChange = { drawerPage = it }, docked = docked,
            backRequest = drawerBackRequests, onLayerChange = { drawerLayerOpen = it }, a = DrawerActions(
            steam = SessionState.mode == SessionService.MODE_STEAM,
            direct = noSteamSession(),
            title = if (SessionState.mode == SessionService.MODE_RUN)
                com.droiddeck.launcher.frontend.Library.nameForProgram(SessionState.program) ?: getString(R.string.session_title_game) else null,
            isHomeApp = isHomeApp,
            androidApps = androidApps,
            hudOn = hudOn,
            hudConfig = hudConfig,
            upscaler = upscaler, upscaleSharpness = upscaleSharpness,
            effects = effects,
            cardboard = cardboard,
            cardboardSensor = hasRotationSensor,
            onCardboard = { c -> SessionPrefs.setCardboard(this@SessionActivity, c); applyCardboard(c) },
            onCardboardRecenter = { headTracker?.recenter() },
            glassesPresent = glasses.present,
            glassesOutput = glassesOutputActive,
            onGlassesRecenter = {
                headTracker?.recenter()
                com.droiddeck.launcher.glasses.GlassesMenuMirror.recenter()
            },
            steamVr = steamVrStatus,
            immersiveSteamVr = com.droiddeck.launcher.session.QuestExperimental.isSupported(),
            onSteamVr = { action -> steamVrAction(action) },
            textureAnisotropy = if (drawerGameProfileId == null) textureAnisotropy else drawerTextureAnisotropy,
            textureLodBias = if (drawerGameProfileId == null) textureLodBias else drawerTextureLodBias,
            frameGen = frameGen,
            lossless = lossless,
            oscMode = oscMode, onScreenButtonsVisible = onScreenButtonsVisible, suspendPolicy = suspendPolicy,
            steamDownloadsInBackground = steamDownloadsInBackground, touchMode = touchMode,
            micAvailable = SessionState.micAvailable && !SessionState.suspended,
            micEnabled = SessionState.micEnabled,
            onMicEnabled = { SessionService.setMicEnabled(this@SessionActivity, it) },
            touchAuto = getString(if (usingTouchpad()) R.string.session_touch_auto_touchpad else R.string.session_touch_auto_direct),
            fexPreset = fexPreset,
            secondScreenMode = secondScreenMode,
            secondScreenDisplays = secondScreenDisplays,
            selectedSecondScreenDisplay = selectedSecondScreenDisplay,
            onHud = { on -> SessionPrefs.setHudEnabled(this@SessionActivity, on); hudOn = on; hud.refresh() },
            onHudSetting = { key, value -> hudConfig = FusionHudConfig.set(this@SessionActivity, key, value); hud.applyConfig() },
            onHudReset = { hudConfig = FusionHudConfig.reset(this@SessionActivity); hud.applyConfig() },
            onHudExport = { exportHudDiagnostics() },
            onUpscaler = { m ->
                SessionPrefs.setUpscaler(this@SessionActivity, m); upscaler = m
                WaylandCompositor.nativeSetUpscaler(m)
                WaylandCompositor.nativeSetLookName(ScreenEffectLooks.match(effects, m)?.name)
            },
            onUpscaleSharpness = { pct ->
                SessionPrefs.setUpscaleSharpness(this@SessionActivity, pct); upscaleSharpness = pct
                WaylandCompositor.nativeSetUpscaleSharpness(pct)
            },
            onEffects = { e ->
                SessionPrefs.setScreenEffects(this@SessionActivity, e); effects = e
                e.push(upscaler)
            },
            onTextureAnisotropy = { value ->
                drawerGameProfileId?.let { appId ->
                    SessionPrefs.setGameTextureAnisotropy(this@SessionActivity, appId.toString(), value)
                    drawerTextureAnisotropy = value
                } ?: value?.let {
                    SessionPrefs.setTextureAnisotropy(this@SessionActivity, it)
                    textureAnisotropy = it
                }
            },
            onTextureLodBias = { value ->
                drawerGameProfileId?.let { appId ->
                    SessionPrefs.setGameTextureLodBias(this@SessionActivity, appId.toString(), value)
                    drawerTextureLodBias = value
                } ?: value?.let {
                    SessionPrefs.setTextureLodBias(this@SessionActivity, it)
                    textureLodBias = it
                }
            },
            onFrameGenPick = { mode ->
                FrameGen.set(this@SessionActivity, mode)
                readPrefs()
                applyFrameGen()
            },
            onImportLossless = {
                pickLossless.launch(InAppFilePicker.buildIntent(
                    this@SessionActivity, listOf("dll"), getString(R.string.lsfg_pick_title)))
            },
            onKeyboard = { drawerOpen = false; pcKeyboardOpen = false; keyboard?.toggle() },
            onHardwareKeyboard = ::togglePcKeyboard,
            controller = controllerSettings,
            onRumble = { on -> updateControllerPrefs { ControllerPrefs.setRumble(this@SessionActivity, on) } },
            onSteamButton = { on -> updateControllerPrefs { ControllerPrefs.setSteamButton(this@SessionActivity, on) } },
            onQamButton = { on -> updateControllerPrefs { ControllerPrefs.setQamButton(this@SessionActivity, on) } },
            onKeyboardButton = { on -> updateControllerPrefs { ControllerPrefs.setKeyboardButton(this@SessionActivity, on) } },
            onSteamMenu = if (SessionState.mode == SessionService.MODE_STEAM) ({ sendSteamGuide() }) else null,
            onQam = if (SessionState.mode == SessionService.MODE_STEAM) ({ triggerSteamQam() }) else null,
            onAchievements = achievementsInFront?.let { { drawerOpen = false; achievementsOpen = true } },
            achievementsProgress = achievementsInFront?.progress,
            padType = if (SessionState.mode == SessionService.MODE_STEAM && SessionState.deckPad) padType else null,
            onPadType = { id ->
                SessionPrefs.setSteamController(this@SessionActivity, id)
                com.droiddeck.launcher.session.SteamDeckPad.showPad(SessionPrefs.padType(id))
                com.droiddeck.launcher.input.FakeInputWriter.setPadType(SessionPrefs.padType(id))
                com.droiddeck.launcher.session.InputSysfsComponent.padTypeChanged(SessionPrefs.padType(id))
                padType = id
            },
            backActionsInverted = backActionsInverted,
            backDoubleTapMs = backDoubleTapMs,
            onBackDoubleTapMs = { ms ->
                SessionPrefs.setBackDoubleTapMs(this@SessionActivity, ms)
                backDoubleTapMs = ms
            },
            onBackActionsInverted = { inverted ->
                SessionPrefs.setBackActionsInverted(this@SessionActivity, inverted)
                backActionsInverted = inverted
            },
            lowLatency = lowLatency,
            onLowLatency = { on ->
                SessionPrefs.setLowLatency(this@SessionActivity, on)
                lowLatency = on
                applyLowLatency()
            },
            evdevMouse = evdevMouseOn,
            onEvdevMouse = { on ->
                SessionPrefs.setEvdevMouse(this@SessionActivity, on)
                evdevMouseOn = on
                if (on) { if (pointerLocked) openEvdevMouse() } else releaseEvdevMouse()
            },
            directCursor = directCursorOn,
            onDirectCursor = { on ->
                SessionPrefs.setDirectCursor(this@SessionActivity, on)
                directCursorOn = on
                if (!on && directTouchCursorHidden()) cursorVisible = false
            },
            onOsc = { v ->
                SessionPrefs.setOscMode(this@SessionActivity, v)
                readPrefs()
                updateOnScreenControls()
                if (v == SessionPrefs.OSC_STEAM_TOUCH && com.droiddeck.launcher.input.SteamTouchDevice.current == null)
                    Toast.makeText(this@SessionActivity, R.string.osc_style_next_session, Toast.LENGTH_LONG).show()
            },
            onEditSteamTouch = if (steamTouchAvailable && oscMode == SessionPrefs.OSC_STEAM_TOUCH && onScreenButtonsVisible) ({
                drawerOpen = false
                steamTouchControls?.startEditing()
            }) else null,
            onSuspendPolicy = { policy ->
                SessionPrefs.setSuspendPolicy(this@SessionActivity, SessionState.mode, policy)
                suspendPolicy = policy
                SessionService.suspendPolicyChanged(this@SessionActivity)
            },
            onSteamDownloadsInBackground = { enabled ->
                SessionPrefs.setSteamDownloadsInBackground(this@SessionActivity, enabled)
                steamDownloadsInBackground = enabled
                SessionService.suspendPolicyChanged(this@SessionActivity)
            },
            onTouch = { v -> SessionPrefs.setTouchMode(this@SessionActivity, v); readPrefs() },
            onGestureSettings = { drawerOpen = false; gestureSettingsOpen = true },
            touchActive = drawerTouchMode(),
            directHoldBehavior = directHoldBehavior,
            onDirectHoldBehavior = { value ->
                val base = com.droiddeck.launcher.input.GesturePrefs.read(this@SessionActivity, gestureGame)
                com.droiddeck.launcher.input.GesturePrefs.save(this@SessionActivity, gestureGame, base.copy(directHoldBehavior = value))
                refreshGestureConfig()
            },
            onScreenKeys = { drawerOpen = false; screenKeys?.startEditing() },
            screenKeysShown = screenKeysShown,
            onScreenKeysShown = { shown ->
                screenKeysShown = shown
                com.droiddeck.launcher.input.ControllerPrefs.setScreenKeysShown(this@SessionActivity, shown)
                screenKeys?.stopEditing()
                screenKeys?.visibility = if (shown) View.VISIBLE else View.GONE
            },
            onScreenLayouts = {
                drawerOpen = false
                screenLayoutNames = layoutNames()
                screenLayoutsOpen = true
            },
            onFexPreset = { v -> SessionPrefs.setFexPreset(this@SessionActivity, v); readPrefs() },
            onSecondScreenMode = { mode ->
                if (docked) drawerOpen = false
                typingReturnMode = null
                if (mode != SecondScreenMode.NONE) SessionPrefs.setSecondScreenPanel(this@SessionActivity, mode.id)
                selectSecondScreenMode(mode)
            },
            onSecondScreenDisplay = ::selectSecondScreenDisplay,
            onLaunchAndroidApp = { app, displayId ->
                drawerOpen = false
                try {
                    HomeApp.launch(this@SessionActivity, app, displayId)
                } catch (_: Exception) {
                    val label = secondScreenDisplays.firstOrNull { it.id == displayId }?.label
                    val message = when {
                        displayId == null || displayId == Display.DEFAULT_DISPLAY -> getString(R.string.android_app_open_failed_primary, app.label)
                        label != null -> getString(R.string.android_app_open_failed_on, app.label, label)
                        else -> getString(R.string.android_app_open_failed_display, app.label, displayId)
                    }
                    Toast.makeText(this@SessionActivity, message, Toast.LENGTH_SHORT).show()
                }
            },
            pipSupported = com.droiddeck.launcher.session.SessionPipController.supported(this@SessionActivity),
            pipAutoEnter = pipAutoEnter,
            onPip = { pip.enter() },
            onPause = ::pauseNow,
            onPipAutoEnter = { on ->
                SessionPrefs.setPipAutoEnter(this@SessionActivity, on)
                pipAutoEnter = on
                pip.refresh(true)
            },
            unfoldedControls = unfoldedControls,
            onUnfoldedControls = ::chooseUnfoldedControls,
            unfoldedControlsWithController = unfoldedControlsWithController,
            onUnfoldedControlsWithController = ::chooseUnfoldedControlsWithController,
            onBackground = { drawerOpen = false; pip.background(); moveTaskToBack(true) },
            onShareLogs = { drawerOpen = false; shareCurrentSessionLogs() },
            onStop = { drawerOpen = false; SessionService.stop(this@SessionActivity); finish() },
            onStopFrom = { onScreen ->
                drawerOpen = false
                if (onScreen == null || com.droiddeck.launcher.ui.Motion.scale == 0f || quitFrom != null) {
                    if (quitFrom == null) { SessionService.stop(this@SessionActivity); finish() }
                } else {
                    val at = IntArray(2).also { sessionOverlay.getLocationOnScreen(it) }
                    quitFrom = onScreen.translate(-at[0].toFloat(), -at[1].toFloat())
                }
            },
            onClose = { drawerOpen = false },
            components = drawerComponents,
            onComponentsRefresh = {
                refreshDrawerComponents()
                drawerComponentMenu.refreshComponentCatalog(force = false)
            },
            componentCatalog = drawerComponentMenu.compCatalog,
            componentCatalogAt = drawerComponentMenu.compCatalogAt,
            componentCatalogChecking = drawerComponentMenu.compChecking,
            componentDownloads = drawerComponentMenu.compDownloads,
            componentDownloadRevision = drawerComponentMenu.compDownloadRevision,
            onComponentCatalogRefresh = { drawerComponentMenu.refreshComponentCatalog() },
            onComponentDownload = drawerComponentMenu::downloadComponent,
            gameProfiles = drawerGameProfileRefresh.data?.profiles.orEmpty(),
            selectedGameProfileId = drawerGameProfileId,
            selectedGameProfile = drawerGameProfileRefresh.data
                ?.takeIf { it.selectedAppId == drawerGameProfileId }?.profile,
            gameProfilesLoaded = drawerGameProfileRefresh.loaded,
            gameProfileRefreshFailed = drawerGameProfileRefresh.failed,
            onSelectedGameProfile = ::selectDrawerGameProfile,
            onGameProfilesPageOpened = {
                drawerGameProfileFollowState.resumeFollowingSteam()
                drawerGameInventoryRevision++
            },
            onSelectedGameProfileRefresh = ::refreshDrawerGameProfile,
            gameFexPresetSaving = drawerGameFexPresetSaving,
            onGameFexPreset = ::setDrawerGameFexPreset,
            gameNoSteamSaving = drawerGameNoSteamSaving,
            onGameNoSteam = ::setDrawerGameNoSteam,
            onComponentSwap = { pid, comp, value -> swapDrawerComponent(pid, comp, value) },
            onGameComponent = ::setDrawerGameComponent,
            defaultProtonId = drawerDefaultProton,
            onDefaultProton = ::chooseDrawerDefaultProton,
            onGameProton = ::setDrawerGameProton,
        ))
    }

    private fun refreshHomeApp() {
        isHomeApp = HomeApp.isDefault(this)
        androidApps = if (isHomeApp) HomeApp.launchableApps(this) else emptyList()
    }

    private fun sendSteamGuide() {
        drawerOpen = false
        padBridge?.applyTouch { state -> state.press(com.droiddeck.launcher.input.PadState.GUIDE, true) }
        uiHandler.postDelayed({
            padBridge?.applyTouch { state -> state.press(com.droiddeck.launcher.input.PadState.GUIDE, false) }
        }, 90)
    }

    @androidx.compose.runtime.Composable
    private fun GlassesPhonePanel(glasses: com.droiddeck.launcher.glasses.GlassesState, vr: Boolean, headTracking: Boolean) {
        val battery by androidx.compose.runtime.produceState<String?>(null) {
            while (true) {
                val b = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                val level = b?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = b?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
                val tenths = b?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
                value = if (level < 0) null else getString(R.string.glasses_phone_battery, level * 100 / scale.coerceAtLeast(1), tenths / 10)
                kotlinx.coroutines.delay(30_000)
            }
        }
        val pads = com.droiddeck.launcher.input.VrControllers.settings.collectAsState().value
        com.droiddeck.launcher.ui.GlassesPhonePanel(glasses.model, battery, com.droiddeck.launcher.ui.GlassesPhoneActions(
            onRecenter = if (headTracking) ({ headTracker?.recenter() }) else null,
            onRecenterJoyCons = if (vr && glasses.usesSpatialControllers(glassesSettings) && glassesSettings.joyConTracking)
                joyConVr?.let { { it.recenter() } } else null,
            onSteamVrMenu = if (vr && SessionState.mode == SessionService.MODE_STEAM) ({ sendSteamGuide() }) else null,
            onSwitchSticks = if (vr && pads.emulate != com.droiddeck.launcher.input.VrControllers.Emulate.OFF) ({ switchVrSticks() }) else null,
            controllers = getString(when (pads.emulate) {
                com.droiddeck.launcher.input.VrControllers.Emulate.OFF -> R.string.vr_controllers_off
                com.droiddeck.launcher.input.VrControllers.Emulate.INDEX -> R.string.vr_controllers_index
                com.droiddeck.launcher.input.VrControllers.Emulate.TOUCH -> R.string.vr_controllers_touch
                com.droiddeck.launcher.input.VrControllers.Emulate.VIVE -> R.string.vr_controllers_vive
            }),
            onNextControllers = if (vr) ({
                val all = com.droiddeck.launcher.input.VrControllers.Emulate.entries
                com.droiddeck.launcher.input.VrControllers.setEmulate(this, all[(pads.emulate.ordinal + 1) % all.size])
            }) else null,
            onKeyboard = { keyboard?.toggle() },
            onSessionMenu = { drawerOpen = true },
            onStopSteamVr = if (vr) ({ steamVrAction("stop") }) else null,
            brightness = glasses.brightness,
            brightnessMax = glasses.brightnessMax,
            onBrightness = if (glasses.canControl && glasses.hardwareControls) Glasses::setBrightness else null,
        ))
    }

    /** The SteamVR driver switches pad sticks between hands and game on both stick clicks together. */
    private fun switchVrSticks() {
        val chord = { down: Boolean -> padBridge?.applyTouch { state ->
            state.press(com.droiddeck.launcher.input.PadState.L3, down)
            state.press(com.droiddeck.launcher.input.PadState.R3, down)
        } }
        chord(true)
        uiHandler.postDelayed({ chord(false) }, 120)
    }

    private fun triggerSteamQam() {
        drawerOpen = false
        padBridge?.triggerQam()
    }

    private fun closeAchievements() { achievementsOpen = false }

    private fun resolveAchievementsInFront(): AchievementsInFront? {
        if (!SessionState.running || SessionState.mode != SessionService.MODE_STEAM) return null
        val snapshot = AgentGuest.focus(this) ?: return null
        val games = LibraryCache.load(this)
        val byKey = games.mapNotNull { g -> g.profileKey.toLongOrNull()?.let { it to g } }.toMap()
        val windows = snapshot.optJSONArray("focusableApps")?.let { list -> List(list.length()) { list.optLong(it) } }.orEmpty()
        val focusedId = snapshot.optLong("focusedApp").takeUnless { snapshot.isNull("focusedApp") }
        val appId = com.droiddeck.launcher.session.SessionAchievementGame.activeAppId(focusedId, windows, byKey.keys) ?: return null
        val focused = byKey[appId] ?: return null
        val store = Store.byId(focused.source) ?: return null
        val id = focused.storeId ?: return null
        val source = StoreAchievements.forSource(store.id) ?: return null
        val sidecar = StoreGameSidecar.find(this, store, id) ?: return null
        val game = StoreAchievements.Game.of(sidecar)
        val list = source.cached(this, game) ?: (source.fetch(this, game) as? StoreAchievements.Result.Found)?.list ?: return null
        if (list.isEmpty()) return null
        return AchievementsInFront(game, sidecar.title, list.count { it.unlocked } to list.size)
    }

    private fun togglePcKeyboard() {
        drawerOpen = false
        keyboard?.hide()
        onScreenControls?.releaseAll()
        pcKeyboardOpen = !pcKeyboardOpen
    }

    private fun updateControllerPrefs(change: () -> Unit) {
        change()
        controllerSettings = ControllerPrefs.read(this)
        onScreenControls?.reload()
    }

    private fun routeBackAction() {
        pendingBackAction?.let { pending ->
            uiHandler.removeCallbacks(pending)
            pendingBackAction = null
            performBackAction(double = true)
            return
        }
        val pending = Runnable {
            pendingBackAction = null
            performBackAction(double = false)
        }
        pendingBackAction = pending
        uiHandler.postDelayed(pending, backDoubleTapMs.toLong())
    }

    private fun performBackAction(double: Boolean) {
        val isSteam = SessionState.mode == SessionService.MODE_STEAM
        val opensQam = isSteam && (double xor backActionsInverted)
        if (opensQam) triggerSteamQam() else drawerOpen = !drawerOpen
    }

    private fun handleHomeGuideIntent(incoming: Intent?) {
        if (incoming?.action != SessionService.ACTION_HOME_GUIDE) return
        incoming.action = null
        setIntent(incoming)
        if (SessionState.suspended) SessionService.resume(this)
        else if (SessionState.running && SessionState.mode == SessionService.MODE_STEAM) sendSteamGuide()
    }


    private fun refreshDrawerComponents() {
        Thread({
            val applied = runCatching { ComponentsManager.applyQueued(this) }.getOrDefault(emptyList())
            val snap = runCatching { ComponentsManager.snapshot(this) }.getOrNull()
            val chosen = snap?.let { s -> runCatching { ProtonDefault.selectedId(this, s.protons.map { it.proton }) }.getOrNull() }
            uiHandler.post {
                drawerComponents = snap
                drawerDefaultProton = chosen
                if (applied.isNotEmpty()) android.widget.Toast.makeText(this, getString(R.string.main_applied, applied.joinToString(", ")), android.widget.Toast.LENGTH_LONG).show()
            }
        }, "drawer-components").start()
    }

    private fun selectDrawerGameProfile(appId: Long?) {
        drawerGameProfileFollowState.selectManually()
        drawerGameProfileId = appId
        drawerGameProfileRefresh = drawerGameProfileRefresh.retrying()
        readDrawerTextureFiltering(appId)
        refreshDrawerGameProfile()
    }

    private fun readDrawerTextureFiltering(appId: Long?) {
        val choice = appId?.let { SessionPrefs.gameTextureFiltering(this, it.toString()) }
        drawerTextureAnisotropy = choice?.anisotropy
        drawerTextureLodBias = choice?.lodBias
    }

    private fun refreshDrawerGameProfile() {
        val refresh = DrawerGameProfileRefresh(
            drawerGameProfileFollowState.followsSteam,
            drawerGameProfileId,
            drawerGameInventoryRevision,
        )
        if (!drawerGameProfileRefreshes.submit(refresh)) return
        Thread({
            var request = drawerGameProfileRefreshes.takeLatest()
            while (request != null) {
                val current = request
                val result = resolveDrawerGameProfileRefresh(current.value)
                uiHandler.post {
                    if (!drawerGameProfileRefreshes.isLatest(current)) return@post
                    applyDrawerGameProfileRefresh(current.value, result)
                }
                request = drawerGameProfileRefreshes.takeLatest()
            }
        }, "drawer-game-profile").start()
    }

    private fun resolveDrawerGameProfileRefresh(
        refresh: DrawerGameProfileRefresh,
    ): DrawerGameProfileResult {
        return try {
            val games = drawerGameInventory.get(refresh.inventoryRevision) { Library.launchableGames(this) }
            val options = games.mapNotNull { game ->
                game.profileKey.toLongOrNull()?.let { it to game.name }
            }.sortedBy { it.second.lowercase() }
            val installedAppIds = options.mapTo(mutableSetOf()) { it.first }
            val selectedAppId = if (refresh.followsSteam) {
                val activeAppId = AgentGuest.activeSteamAppId(this, installedAppIds)
                val selectedSteamAppId = runCatching { AgentGuest.selectedSteamAppId(this) }
                AgentGuest.selectedOrActiveInstalledSteamAppId(selectedSteamAppId, activeAppId, installedAppIds)
                    ?: SessionState.gameId?.toLongOrNull()
                    ?: refresh.requestedAppId
            } else {
                refresh.requestedAppId
            }
            val profile = selectedAppId?.let { resolveDrawerGameProfile(it, games) }
            DrawerGameProfileResult(options, selectedAppId, profile)
        } catch (error: Exception) {
            DrawerGameProfileResult(emptyList(), refresh.requestedAppId, null, error)
        }
    }

    private fun applyDrawerGameProfileRefresh(
        refresh: DrawerGameProfileRefresh,
        result: DrawerGameProfileResult,
    ) {
        if (result.error != null) {
            if (!drawerGameProfileRefresh.failed) {
                Log.w(TAG, "Could not refresh the selected game profile", result.error)
            }
            drawerGameProfileRefresh = drawerGameProfileRefresh.failed()
            return
        }
        drawerGameProfileRefresh = drawerGameProfileRefresh.succeeded(result)
        if (refresh.followsSteam) {
            if (drawerGameProfileFollowState.followsSteam) {
                drawerGameProfileId = result.selectedAppId
                readDrawerTextureFiltering(result.selectedAppId)
            }
        }
    }

    private fun resolveDrawerGameProfile(
        selectedAppId: Long,
        games: List<Library.SteamGame>,
    ): SelectedGameProfile? {
        val game = games.firstOrNull { it.profileKey == selectedAppId.toString() } ?: return null
        val snapshot = drawerComponents ?: ComponentsManager.snapshot(this)
        val protons = snapshot.protons.map { it.proton }
        val gameChoice = ProtonDefault.gameChoice(this, game.profileKey)
        return SelectedGameProfile.resolve(
            selectedAppId = selectedAppId,
            games = games,
            generalFexPreset = SessionPrefs.fexPreset(this),
            environment = GameEnvironmentStore.read(this),
            snapshot = snapshot,
            generalProtonId = ProtonDefault.selectedId(this, protons),
            gameProtonChoice = gameChoice,
            gameProtonId = ProtonDefault.gameSelectedId(this, game.profileKey, protons),
            gameComponents = ComponentsManager.gameComponents(this, game.profileKey),
            gameWindowsComponents = WinComponents.picks(this, game.profileKey),
        )
    }

    private fun swapDrawerComponent(protonId: String, comp: String, value: String) {
        Thread({
            val message = runCatching {
                val snap = ComponentsManager.snapshot(this)
                val queued = snap.protons.firstOrNull { it.proton.id == protonId }?.components?.get(comp)?.queued
                val active = snap.protons.firstOrNull { it.proton.id == protonId }?.components?.get(comp)?.activeFile
                val build = snap.protons.firstOrNull { it.proton.id == protonId }?.proton?.version?.let(ComponentsManager::safeName)
                val current = active ?: "orig:$build"
                if (queued != null && value == current) {
                    ComponentsManager.cancelQueued(this, protonId, comp); getString(R.string.comp_swap_cancelled)
                } else if (value.startsWith("orig:")) ComponentsManager.restore(this, protonId, comp, value.removePrefix("orig:"))
                else ComponentsManager.swap(this, protonId, value)
            }.getOrElse { e -> getString(R.string.session_swap_failed, e.message ?: e.javaClass.simpleName) }
            uiHandler.post {
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
                refreshDrawerComponents()
            }
        }, "drawer-components-swap").start()
    }

    private fun setDrawerGameComponent(appId: Long, comp: String, file: String?) {
        val name = drawerGameProfileRefresh.data?.profiles?.firstOrNull { it.first == appId }?.second ?: appId.toString()
        Thread({
            val message = runCatching {
                ComponentsManager.setGameComponent(this, appId.toString(), comp, file)
                getString(R.string.comp_game_saved_next, name)
            }.getOrElse { e ->
                getString(R.string.session_profile_save_failed, e.message ?: e.javaClass.simpleName)
            }
            uiHandler.post {
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
                refreshDrawerComponents()
                refreshDrawerGameProfile()
            }
        }, "drawer-game-component").start()
    }

    private fun chooseDrawerDefaultProton(protonId: String) {
        val proton = drawerComponents?.protons?.firstOrNull { it.proton.id == protonId }?.proton ?: return
        drawerDefaultProton = protonId
        Thread({
            val message = runCatching {
                when (ProtonDefault.request(this, proton)) {
                    ProtonDefault.Outcome.LIVE -> getString(R.string.comp_default_live, proton.name)
                    ProtonDefault.Outcome.NEXT_START -> getString(R.string.comp_default_next, proton.name)
                    ProtonDefault.Outcome.NOT_RUNNABLE -> getString(R.string.comp_default_not_runnable, proton.name)
                }
            }.getOrElse { e -> getString(R.string.comp_default_failed, e.message ?: e.javaClass.simpleName) }
            uiHandler.post {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                refreshDrawerComponents()
                refreshDrawerGameProfile()
            }
        }, "drawer-default-proton").start()
    }

    private fun setDrawerGameProton(appId: Long, protonId: String?) {
        val name = drawerGameProfileRefresh.data?.profiles?.firstOrNull { it.first == appId }?.second ?: appId.toString()
        val proton = protonId?.let { id -> drawerComponents?.protons?.firstOrNull { it.proton.id == id }?.proton ?: return }
        Thread({
            val message = runCatching {
                getString(
                    when (ProtonDefault.requestGame(this, appId.toString(), proton)) {
                        ProtonDefault.Outcome.LIVE -> R.string.comp_game_proton_live
                        ProtonDefault.Outcome.NEXT_START -> R.string.comp_game_proton_next
                        ProtonDefault.Outcome.NOT_RUNNABLE -> R.string.comp_game_proton_not_runnable
                    },
                    name,
                )
            }.getOrElse { e -> getString(R.string.session_profile_save_failed, e.message ?: e.javaClass.simpleName) }
            uiHandler.post {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                refreshDrawerGameProfile()
            }
        }, "drawer-game-proton").start()
    }

    private fun setDrawerGameFexPreset(appId: Long, preset: String?) {
        if (drawerGameFexPresetSaving) return
        drawerGameFexPresetSaving = true
        val name = drawerGameProfileRefresh.data?.profiles?.firstOrNull { it.first == appId }?.second ?: appId.toString()
        Thread({
            val message = try {
                GameEnvironmentStore.setGameFexPreset(this, appId.toString(), preset)
                getString(R.string.comp_game_saved_next, name)
            } catch (error: Exception) {
                Log.w(TAG, "Could not save the game FEX preset for $appId", error)
                getString(R.string.session_profile_save_failed, error.message ?: error.javaClass.simpleName)
            }
            uiHandler.post {
                drawerGameFexPresetSaving = false
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                refreshDrawerGameProfile()
            }
        }, "drawer-game-fex-preset").start()
    }

    private fun setDrawerGameNoSteam(appId: Long, on: Boolean) {
        if (drawerGameNoSteamSaving) return
        drawerGameNoSteamSaving = true
        val name = drawerGameProfileRefresh.data?.profiles?.firstOrNull { it.first == appId }?.second ?: appId.toString()
        Thread({
            val message = try {
                GameEnvironmentStore.setGameNoSteam(this, appId.toString(), on)
                getString(R.string.comp_game_saved_next, name)
            } catch (error: Exception) {
                Log.w(TAG, "Could not save Start without Steam for $appId", error)
                getString(R.string.session_profile_save_failed, error.message ?: error.javaClass.simpleName)
            }
            uiHandler.post {
                drawerGameNoSteamSaving = false
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                refreshDrawerGameProfile()
            }
        }, "drawer-game-no-steam").start()
    }

    private fun loadingMode(): String =
        if (SessionState.running) SessionState.mode
        else intent.getStringExtra(SessionService.EXTRA_MODE) ?: SessionService.MODE_STEAM

    private fun pausedTitle(): String = when (SessionState.mode) {
        SessionService.MODE_DESKTOP -> getString(R.string.session_desktop_paused)
        SessionService.MODE_RUN -> com.droiddeck.launcher.frontend.Library.nameForProgram(SessionState.program)
            ?.let { getString(R.string.session_named_paused, it) } ?: getString(R.string.session_program_paused)
        else -> getString(R.string.load_steam_paused)
    }

    private fun loadingTitle(): String = when (loadingMode()) {
        SessionService.MODE_DESKTOP -> getString(R.string.session_starting_desktop)
        SessionService.MODE_RUN -> {
            val program = if (SessionState.running) SessionState.program else intent.getStringExtra(SessionService.EXTRA_PROGRAM)
            com.droiddeck.launcher.frontend.Library.nameForProgram(program)?.let { getString(R.string.session_starting_named, it) }
                ?: getString(R.string.session_starting_program)
        }
        else -> if (com.droiddeck.launcher.session.SessionPrefs.activeSteamClientArch(this) == "x86_64") getString(R.string.load_starting_steam_x64)
            else getString(R.string.load_starting_steam)
    }

    private fun exportHudDiagnostics() {
        glassesScope.launch {
            val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { hud.exportDiagnostics() }
            android.widget.Toast.makeText(this@SessionActivity,
                if (file != null) getString(R.string.hud_export_done, file.path) else getString(R.string.hud_export_failed),
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun shareCurrentSessionLogs() {
        val folder = SessionPaths.current() ?: SessionPaths.lastEnded()
        if (folder == null || !folder.isDirectory) {
            Toast.makeText(this, R.string.session_no_logs, Toast.LENGTH_LONG).show()
            return
        }
        SessionLogShare.prepare(this, { folder }) { zip ->
            if (zip == null) {
                Toast.makeText(this, R.string.session_logs_archive_failed, Toast.LENGTH_LONG).show()
            } else {
                runCatching { startActivity(SessionLogShare.shareIntent(this, zip)) }
                    .onFailure {
                        Log.w(TAG, "could not share current session logs", it)
                        Toast.makeText(this, R.string.session_logs_share_failed, Toast.LENGTH_LONG).show()
                    }
            }
        }
    }

    private fun readPrefs() {
        pipAutoEnter = SessionPrefs.pipAutoEnter(this)
        unfoldedControls = SessionPrefs.unfoldedControls(this)
        unfoldedControlsWithController = SessionPrefs.unfoldedControlsWithController(this)
        picturePosition = SessionPrefs.picturePosition(this)
        hudOn = SessionPrefs.hudEnabled(this)
        hudConfig = FusionHudConfig.load(this)
        hudColor = SessionPrefs.hudColor(this)
        hudOpacity = SessionPrefs.hudOpacity(this)
        upscaler = SessionPrefs.upscaler(this)
        upscaleSharpness = SessionPrefs.upscaleSharpness(this)
        effects = SessionPrefs.screenEffects(this)
        applyCardboard(SessionPrefs.cardboard(this))
        if (SessionPrefs.cardboardSourceBeforeSteamVr(this) >= 0 && !steamVrSwitchedPicture) {
            steamVrSwitchedPicture = true
            steamVrSeenRunning = true
        }
        textureAnisotropy = SessionPrefs.textureAnisotropy(this)
        textureLodBias = SessionPrefs.textureLodBias(this)
        val nextTouchMode = SessionPrefs.touchMode(this)
        if (touchMode != nextTouchMode) {
            if (::touchpad.isInitialized) touchpad.cancel()
            if (::directTouchHold.isInitialized) directTouchHold.cancel()
            if (::twoFingerGestures.isInitialized) twoFingerGestures.cancel()
            if (::scrollTouch.isInitialized) scrollTouch.cancel()
            if (CompositorHost.isStarted) {
                WaylandCompositor.nativeSendTouch(3, -1, 0, 0)
                listOf(PointerGestures.BTN_LEFT, PointerGestures.BTN_RIGHT, PointerGestures.BTN_MIDDLE).forEach {
                    WaylandCompositor.nativeSendSceneInput(3, it, 0)
                }
            }
            if (nextTouchMode == SessionPrefs.TOUCH_OFF || !usingTouchpad()) cursorVisible = false
        }
        touchMode = nextTouchMode
        val nextRoutedTouchMode = effectiveTouchMode()
        if (routedTouchMode != nextRoutedTouchMode) gestureTouchStreamGate.reset()
        routedTouchMode = nextRoutedTouchMode
        frameGen = FrameGen.mode(this)
        fexPreset = SessionPrefs.fexPreset(this)
        suspendPolicy = SessionPrefs.suspendPolicy(this, SessionState.mode)
        steamDownloadsInBackground = SessionPrefs.steamDownloadsInBackground(this)
        oscMode = SessionPrefs.oscMode(this)
        backActionsInverted = SessionPrefs.backActionsInverted(this)
        backDoubleTapMs = SessionPrefs.backDoubleTapMs(this)
        lowLatency = SessionPrefs.lowLatency(this)
        evdevMouseOn = SessionPrefs.evdevMouse(this)
        if (!evdevMouseOn) releaseEvdevMouse()
        directCursorOn = SessionPrefs.directCursor(this)
        padType = SessionPrefs.steamController(this)
    }


    private var localInstalling = false
    private val installingRuntime get() = localInstalling || SessionState.installing != null
    private var repairOffered by mutableStateOf(false)

    private fun installThenStart(runtime: Boolean, desktop: Boolean, proton: Boolean = false, resume: Boolean = false) {
        if (SessionState.stopRequested) return
        val worker = Any()
        activeInstallWorker = worker
        localInstalling = true
        SessionState.installing = if (runtime) "runtime" else if (resume) "package" else if (desktop) "desktop" else "proton"
        SessionEvents.transition(
            SessionPhase.INSTALLING_RUNTIME,
            "${SessionState.installing}.installing",
            mapOf("component" to SessionState.installing),
        )
        val first = if (runtime || resume) RUNTIME_LINES else if (desktop) DESKTOP_LINES else PROTON_LINES
        loading.say(getString(first.downloading), first.topic, readable = true)
        loading.percent = -1
        Thread({
            var failedComponent: String? = null
            var problem: String? = null
            if (runtime) {
                problem = installRuntime()
                if (problem == null) SessionEvents.record("runtime.ready") else failedComponent = "runtime"
            }
            if (problem == null && resume && !SessionState.stopRequested) {
                if (activeInstallWorker === worker) SessionState.installing = "package"
                SessionEvents.transition(SessionPhase.INSTALLING_RUNTIME, "package.resuming", mapOf("component" to "package"))
                val cancellation = com.droiddeck.launcher.core.Downloader.Cancellation { SessionState.stopRequested }
                SessionState.installCancellation = cancellation
                problem = try {
                    com.droiddeck.launcher.runtime.DesktopCatalog.resumeInterrupted(this, cancellation, progressAsIs())
                        ?.let { getString(R.string.session_package_resume_failed, it) }
                } finally {
                    if (SessionState.installCancellation === cancellation) SessionState.installCancellation = null
                }
                if (problem == null) SessionEvents.record("package.ready") else failedComponent = "package"
            }
            if (problem == null && desktop && !SessionState.stopRequested) {
                if (activeInstallWorker === worker) SessionState.installing = "desktop"
                SessionEvents.transition(SessionPhase.INSTALLING_RUNTIME, "desktop.installing", mapOf("component" to "desktop"))
                problem = installDesktop()
                if (problem == null) SessionEvents.record("desktop.ready") else failedComponent = "desktop"
            }
            if (problem == null && proton && !SessionState.stopRequested) {
                if (activeInstallWorker === worker) SessionState.installing = "proton"
                SessionEvents.transition(SessionPhase.INSTALLING_RUNTIME, "proton.installing", mapOf("component" to "proton"))
                val protonProblem = installProton()
                if (protonProblem == null) SessionEvents.record("proton.ready")
                else SessionEvents.record("proton.skipped", mapOf("reason" to protonProblem))
            }
            uiHandler.post {
                localInstalling = false
                if (activeInstallWorker === worker) {
                    activeInstallWorker = null
                    SessionState.installing = null
                }
                if (SessionState.stopRequested) {
                    if (activeInstallWorker == null) SessionState.stopRequested = false
                    SessionEvents.transition(SessionPhase.IDLE, "session.cancelled")
                    collectStartArtifacts("session start cancelled")
                    finish()
                    return@post
                }
                if (problem != null) {
                    val code = when (failedComponent) {
                        "runtime" -> "RUNTIME_INSTALL_FAILED"
                        "package" -> "PACKAGE_RESUME_FAILED"
                        else -> "DESKTOP_INSTALL_FAILED"
                    }
                    SessionEvents.fail(code, problem)
                    collectStartArtifacts("session start failed")
                    loading.showEnded(problem)
                    focusEndedScreen()
                    return@post
                }
                loading.percent = -1
                loading.say(getString(R.string.session_starting_session), LoadingState.Topic.SESSION, readable = false)
                outputHolder().let { if (it.surface?.isValid == true) surfaceCreated(it) }
            }
        }, "runtime-install").start()
    }

    private fun collectStartArtifacts(reason: String) {
        val dir = SessionPaths.take() ?: return
        val appContext = applicationContext
        Thread({
            SessionArtifacts.collect(appContext, dir, reason)
            SessionPaths.release(appContext, dir)
        }, "session-failure-artifacts").start()
    }

    private class PackageLines(
        val downloading: Int, val downloadingMb: Int, val checking: Int, val unpacking: Int,
        val topic: LoadingState.Topic, val checkingReadable: Boolean,
    )

    private fun progressFor(what: PackageLines, mb: Long) = object : com.droiddeck.launcher.runtime.LinuxRuntimeInstaller.ProgressListener {
        override fun onProgress(stage: String, percent: Int) =
            onProgress(com.droiddeck.launcher.runtime.LinuxRuntimeInstaller.Step.OTHER, stage, percent)

        override fun onProgress(step: com.droiddeck.launcher.runtime.LinuxRuntimeInstaller.Step, stage: String, percent: Int) {
            uiHandler.post {
                loading.percent = percent
                when (step) {
                    com.droiddeck.launcher.runtime.LinuxRuntimeInstaller.Step.DOWNLOADING -> loading.say(
                        if (percent >= 0 && mb > 0) getString(what.downloadingMb, percent * mb / 100, mb) else getString(what.downloading),
                        what.topic, readable = true,
                    )
                    com.droiddeck.launcher.runtime.LinuxRuntimeInstaller.Step.VERIFYING ->
                        loading.say(getString(what.checking), what.topic, what.checkingReadable)
                    else -> loading.say(getString(what.unpacking), what.topic, readable = true)
                }
            }
        }
    }

    private fun progressAsIs() = com.droiddeck.launcher.runtime.LinuxRuntimeInstaller.ProgressListener { stage, percent ->
        uiHandler.post {
            loading.percent = percent
            loading.say(stage, LoadingState.Topic.RUNTIME, readable = true)
        }
    }

    private fun installRuntime(): String? {
        val release = com.droiddeck.launcher.runtime.LinuxRuntimeInstaller.fetchRelease()
            ?: return getString(R.string.session_runtime_catalog_unreachable)
        runCatching { com.droiddeck.launcher.runtime.RuntimeInstallService.start(this, release) }
        val ok = com.droiddeck.launcher.runtime.LinuxRuntimeInstaller.install(this, release,
            progressFor(RUNTIME_LINES, release.size / 1_000_000))
        return if (ok) null else getString(R.string.session_runtime_install_failed)
    }

    private fun installDesktop(): String? {
        uiHandler.post { loading.percent = -1; loading.say(getString(DESKTOP_LINES.downloading), DESKTOP_LINES.topic, readable = true) }
        val entry = com.droiddeck.launcher.runtime.DesktopCatalog.desktopEntry(this)
        val cancellation = com.droiddeck.launcher.core.Downloader.Cancellation { SessionState.stopRequested }
        SessionState.installCancellation = cancellation
        return try {
            val problem = com.droiddeck.launcher.runtime.DesktopCatalog.install(this, entry, cancellation,
                progressFor(DESKTOP_LINES, entry.size / 1_000_000))
            problem?.let { getString(R.string.session_desktop_install_failed, it) }
        } finally {
            if (SessionState.installCancellation === cancellation) SessionState.installCancellation = null
        }
    }

    private fun installProton(): String? {
        uiHandler.post { loading.percent = -1; loading.say(getString(PROTON_LINES.downloading), PROTON_LINES.topic, readable = true) }
        val catalog = com.droiddeck.launcher.runtime.DesktopCatalog
        val cancellation = com.droiddeck.launcher.core.Downloader.Cancellation { SessionState.stopRequested }
        SessionState.installCancellation = cancellation
        return try {
            val entry = catalog.fetch(catalog.STEAM_SEED_URL, cancellation)?.firstOrNull { it.id == catalog.PROTON_SEED_ID }
                ?: return if (cancellation.asBoolean) "cancelled" else "catalog unreachable"
            catalog.install(this, entry, cancellation, progressFor(PROTON_LINES, entry.size / 1_000_000))
        } finally {
            if (SessionState.installCancellation === cancellation) SessionState.installCancellation = null
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (holder !== outputHolder()) return
        if (installingRuntime || SessionState.stopRequested || ended) return
        if (!SessionState.running) {
            SessionEvents.transition(SessionPhase.STARTING_COMPOSITOR, "compositor.starting")
        }
        val runtimeDir = File(filesDir, ".wayland-rt").apply { mkdirs() }
        FileUtils.copyAsset(this, "wayland/keymap.xkb", File(runtimeDir, "keymap.xkb"))
        com.droiddeck.launcher.input.VrControllers.publish(this)

        val turnip = TurnipDriver(this)
        com.droiddeck.launcher.gpu.DriverSetupLog.write(this, "surface.driver-setup")
        if (!CompositorHost.isStarted) {
            CompositorHost.displayChoice = SessionPrefs.androidDriver(this)
            com.droiddeck.launcher.gpu.AutoDriverTrial.displayStarting(this, SessionPrefs.androidDriver(this))
        }
        val driverId = if (CompositorHost.isStarted) null else turnip.install()

        val previous = SessionState.outputSize
        val live = windowOutputSize(holder.surfaceFrame.width(), holder.surfaceFrame.height())
        val size = live ?: if (SessionState.running) previous else glassesOutputSize() ?: outputSize()
        SessionState.outputSize = size
        if (!SessionState.running) SessionState.refreshHz = refreshHz()
        val panel = panelBounds()
        SessionState.upscaleRatio = maxOf(panel.width(), panel.height()).toFloat() / maxOf(size.first, size.second).coerceAtLeast(1)
        Thread({
            runCatching { GameEnvironmentStore.publish(this) }
                .onFailure { Log.e(TAG, "Could not update game environment", it) }
        }, "game-env-publish").start()
        WaylandCompositor.nativeSetScaleMode(SCALE_FIT, ALIGN_CENTER)
        WaylandCompositor.nativeSetUpscaler(SessionPrefs.upscaler(this))
        WaylandCompositor.nativeSetUpscaleSharpness(SessionPrefs.upscaleSharpness(this))
        SessionPrefs.screenEffects(this).push(SessionPrefs.upscaler(this))
        activeCardboard = effectiveCardboard(SessionPrefs.cardboard(this))
        pushCardboard(activeCardboard)
        WaylandCompositor.nativeSetCardboardPoseFile(File(filesDir, ".wayland-rt/droiddeck-headpose").path)
        com.droiddeck.launcher.glasses.SpatialTracking.attachPublisher(File(filesDir, ".wayland-rt/droiddeck-spatialpose").path)
        if (!SessionState.running) {
            val waylandLog = File(SessionPaths.beginOrCurrent(this), "wayland.log")
            WaylandCompositor.setSessionLogFile(waylandLog)
            try {
                android.system.Os.setenv("BL_WAYLAND_LOG", waylandLog.path, true)
            } catch (e: Exception) {
                Log.w(TAG, "could not point the compositor's log at $waylandLog", e)
            }
        }
        if (!CompositorHost.isStarted) {
            val mode = SessionPrefs.prefMode(intent.getStringExtra(SessionService.EXTRA_MODE) ?: SessionService.MODE_STEAM)
            val probe = HdrSupport.probe(this, glassesScreen?.display)
            WaylandCompositor.nativeSetHdrDisplay(
                probe.displayId, probe.name, probe.formats, probe.hdr10,
                probe.maxLuminance, probe.maxAverageLuminance, probe.minLuminance,
                probe.ratioAvailable, probe.ratio, Build.VERSION.SDK_INT,
            )
            val wanted = SessionPrefs.hdr(this, mode)
            val on = wanted && probe.reason == null
            SessionState.hdr = on
            if (on) {
                try { android.system.Os.setenv("DROIDDECK_WAYLAND_HDR", "1", true) } catch (e: Exception) { Log.w(TAG, "DROIDDECK_WAYLAND_HDR", e) }
                WaylandCompositor.nativeSetZeroCopy(true)
            }
            WaylandCompositor.nativeSetHdrRequest(
                if (on) WaylandCompositor.HDR_MODE_ON else WaylandCompositor.HDR_MODE_OFF,
                "$mode session settings" + (if (wanted && !on) " (refused: ${probe.reason})" else ""),
                on, on,
            )
            Log.i(TAG, "hdr: " + (if (on) "on" else if (wanted) "wanted but ${probe.reason}" else "off") + " · display ${probe.formats.ifEmpty { "SDR" }}")
        }
        com.droiddeck.launcher.gpu.DriverSetupLog.write(this, "compositor.start driver=$driverId")
        CompositorHost.startOrAttach(
            this, holder.surface, runtimeDir.path,
            driverId?.let { turnip.driverPath(it) }, driverId?.let { turnip.libraryName(it) },
            applicationInfo.nativeLibraryDir, size.first, size.second, refreshHz(), outputFpsLimit(),
        )
        onOutputAttached(holder)
        if (SessionState.running && size != previous) {
            Log.i(TAG, "output resized: ${previous.first}x${previous.second} -> ${size.first}x${size.second} on a new surface")
            WaylandCompositor.nativeResizeOutput(size.first, size.second)
        }
        if (!SessionState.running) SessionEvents.record("compositor.started")
        if (!SessionState.running) {
            SessionEvents.transition(SessionPhase.STARTING_GUEST, "guest.starting")
            SessionService.start(
                this, intent.getStringExtra(SessionService.EXTRA_MODE) ?: SessionService.MODE_STEAM,
                intent.getStringExtra(SessionService.EXTRA_PROGRAM),
                intent.getStringExtra(SessionService.EXTRA_STEAM_UI),
                intent.getStringExtra(SessionService.EXTRA_STEAM_URL),
                intent.getStringArrayExtra(SessionService.EXTRA_PROGRAM_ARGS),
                intent.getStringExtra(SessionService.EXTRA_GAME_ID),
                sessionActivityDisplayId,
            )
        }
        applyFrameGen()
        applyGameAlignment()
    }

    private var surfaceW = 0
    private var surfaceH = 0

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (holder !== outputHolder()) return
        val resized = surfaceW != 0 && (width != surfaceW || height != surfaceH)
        surfaceW = width
        surfaceH = height
        if (resized) {
            cancelTouchGestures()
            Log.i(TAG, "surface resized to ${width}x$height - rebinding the compositor")
            CompositorHost.resize(holder.surface) { applyFrameGen() }
            resizeOutputForWindow(width, height)
        }
    }

    private fun windowOutputSize(width: Int, height: Int): Pair<Int, Int>? {
        if (isInPictureInPictureMode || width <= 0 || height <= 0) return null
        return if (SessionState.followScreen)
            com.droiddeck.launcher.session.SessionDisplay.followSize(width, height, controlsBelowWanted())
        else if (glassesScreen == null) outputSize() else null
    }

    private fun resizeOutputForWindow(width: Int, height: Int) {
        if (!CompositorHost.isStarted) return
        val size = windowOutputSize(width, height) ?: return
        if (size == SessionState.outputSize) return
        Log.i(TAG, "output resized: ${SessionState.outputSize.first}x${SessionState.outputSize.second} -> ${size.first}x${size.second}")
        SessionState.outputSize = size
        val panel = panelBounds()
        SessionState.upscaleRatio = maxOf(panel.width(), panel.height()).toFloat() / maxOf(size.first, size.second).coerceAtLeast(1)
        WaylandCompositor.nativeResizeOutput(size.first, size.second)
    }

    private fun followChoice(): Boolean {
        val mode = displayMode()
        return SessionPrefs.resolutionChoice(this, mode, com.droiddeck.launcher.session.SessionDisplay.panelSize(this)) ==
            com.droiddeck.launcher.session.SessionDisplay.FOLLOW_SCREEN
    }

    private fun applyFollowOrientation(config: android.content.res.Configuration) {
        val wanted = SessionPrefs.windowOrientation(this, SessionState.followScreen, config.smallestScreenWidthDp)
        if (requestedOrientation != wanted) requestedOrientation = wanted
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (refreshActivityDisplayId() && started) {
            SessionService.setActivityDisplay(this, sessionActivityDisplayId)
        }
        cancelTouchGestures()
        applyFollowOrientation(newConfig)
        applyUnfoldedControls()
        resizeOutputForWindow(surfaceW, surfaceH)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Hiding the phone surface after a handoff delivers a late destruction
        // callback. It must not reset the new output's size or attachment.
        if (holder !== outputHolder()) return
        cancelTouchGestures()
        surfaceW = 0
        surfaceH = 0
        glassesOutputActive = false
        CompositorHost.detach(holder.surface)
    }

    private fun cancelTouchGestures() {
        if (::touchscreenGestures.isInitialized) touchscreenGestures.cancel()
        if (::touchpad.isInitialized) touchpad.cancel()
        if (::scrollTouch.isInitialized) scrollTouch.cancel()
        scrollTouchGesture = false
        gesturePointerReady = false
    }

    private fun panelBounds(): android.graphics.Rect = if (Build.VERSION.SDK_INT >= 30) {
        windowManager.maximumWindowMetrics.bounds
    } else {
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        android.graphics.Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    private fun displayMode(): String = SessionPrefs.displayMode(this,
        intent.getStringExtra(SessionService.EXTRA_MODE) ?: SessionService.MODE_STEAM, intent.getStringExtra(SessionService.EXTRA_PROGRAM))

    private fun outputSize(): Pair<Int, Int> {
        val mode = displayMode()
        val panel = com.droiddeck.launcher.session.SessionDisplay.panelSize(this)
        val program = intent.getStringExtra(SessionService.EXTRA_PROGRAM)
        val choice = if (intent.getStringExtra(SessionService.EXTRA_MODE) == SessionService.MODE_RUN &&
            Library.drawsAtPanel(program) && !SessionPrefs.resolutionChosen(this, mode))
            com.droiddeck.launcher.session.SessionDisplay.MATCH_SCREEN
        else SessionPrefs.resolutionChoice(this, mode, panel)
        return com.droiddeck.launcher.session.SessionDisplay.resolveChoice(panel, choice,
            SessionPrefs.portrait(this) && !controlsBelowWanted())
    }

    private fun refreshHz(): Float {
        glassesScreen?.hz?.takeIf { it > 1f }?.let { return it }
        val display = if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay
        val wanted = window.attributes.preferredDisplayModeId
        val requested = display?.supportedModes?.firstOrNull { wanted != 0 && it.modeId == wanted }?.refreshRate
        val running = display?.refreshRate?.takeIf { it > 1f }
        val hz = listOfNotNull(requested, running).minOrNull() ?: 60f
        val mirrorCap = mirrorRefreshLimit().takeIf { it > 0 }?.toFloat() ?: Float.POSITIVE_INFINITY
        val capped = minOf(hz, PerfMode.peakRefreshCap(this), mirrorCap)
        return if (capped > 1f) capped else 60f
    }

    private fun mirrorRefreshLimit(): Int =
        GlassesRendering.mirrorRefreshLimit(glassesSettings, glasses, glassesScreen != null)

    private fun outputFpsLimit(): Int = SessionPrefs.compositorFpsLimit(mirrorRefreshLimit())

    private fun applyOutputPacing() {
        Log.i(TAG, "perf: " + PerfMode.apply(this, outputFpsLimit(), mirrorRefreshLimit()))
        if (CompositorHost.isStarted) {
            val hz = refreshHz()
            val changed = kotlin.math.abs(SessionState.refreshHz - hz) > 0.1f
            CompositorHost.updatePacing(hz, outputFpsLimit())
            pushCardboard(activeCardboard)
            if (changed && ::surfaceView.isInitialized) applyFrameGen()
        }
    }

    private fun applyFrameGen() {
        val hz = refreshHz()
        Thread({
            val problem = FrameGen.apply(this, hz)
            val state = Lossless.state(this)
            runOnUiThread {
                lossless = state
                if (problem != null) Toast.makeText(this, problem, Toast.LENGTH_LONG).show()
            }
        }, "frame-gen").start()
    }

    private fun syncLossless() {
        Thread({
            Lossless.sync(this)
            val state = Lossless.state(this)
            runOnUiThread { lossless = state }
        }, "lossless-sync").start()
    }

    private fun importLossless(dll: File) {
        Thread({
            val message = Lossless.message(this, Lossless.import(this, dll))
            val state = Lossless.state(this)
            runOnUiThread {
                lossless = state
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                if (FrameGen.engine(this) == FrameGen.ENGINE_LSFG) applyFrameGen()
            }
        }, "lossless-import").start()
    }


    private fun watchSession() {
        val handler = Handler(Looper.getMainLooper())
        var ticks = 0
        val poll = object : Runnable {
            override fun run() {
                if (!watching) return
                if (::pip.isInitialized) pip.refresh()
                if (SessionState.stopRequested && !SessionState.running && !installingRuntime &&
                    SessionState.phase != SessionPhase.STOPPING) {
                    SessionState.stopRequested = false
                    SessionEvents.transition(SessionPhase.IDLE, "session.cancelled")
                    collectStartArtifacts("session start cancelled")
                    finish()
                    return
                }
                if (loading.visible && !loading.ended) {
                    if (!installingRuntime) loading.update(this@SessionActivity, SessionState.logFile)
                    if (ticks++ % 2 == 0) loading.tick()
                }
                handler.postDelayed(this, 500)
            }
        }
        handler.post(poll)
    }

    private fun onSessionEnded(status: Int) {
        ended = true
        ++steamVrRequestGeneration
        if (isInPictureInPictureMode) {
            runOnUiThread { finish() }
            return
        }
        closeSecondScreen(reset = true)
        updateGlassesScreen()
        updateHeadTracker()
        if (status == 0) {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                SessionState.relaunch?.let { next ->
                    SessionState.relaunch = null
                    next.putExtra(EXTRA_RETURN_HOME, intent.getBooleanExtra(EXTRA_RETURN_HOME, false))
                    next.putExtra(EXTRA_LINK_TASK, intent.getIntExtra(EXTRA_LINK_TASK, -1))
                    setIntent(next)
                    recreate()
                    return@runOnUiThread
                }
                finish()
            }
            return
        }
        Thread({
            val hint = runtimeDamageHint() ?: sessionEndHint(SessionState.logFile) ?: prootFailureHint()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                showEnded(status, hint)
            }
        }, "session-end-hint").start()
    }

    private fun showEnded(status: Int, hint: EndHint?) {
        if (status == 0 && hint?.evenOnSuccess != true) {
            finish()
            return
        }
        val stopped = when (SessionState.mode) {
            SessionService.MODE_RUN -> com.droiddeck.launcher.frontend.Library.nameForProgram(SessionState.program)
                ?.let { getString(R.string.session_ended_named, it) } ?: getString(R.string.session_ended_program)
            SessionService.MODE_DESKTOP -> getString(R.string.session_ended_desktop)
            else -> getString(R.string.session_ended_named, "Steam")
        }
        repairOffered = hint?.repair == true
        loading.showEnded(
            hint?.text ?: stopped,
            getString(R.string.session_ended_detail, status,
                SessionState.logFile?.path?.let { android.text.BidiFormatter.getInstance().unicodeWrap(it, android.text.TextDirectionHeuristics.LTR) }
                    ?: getString(R.string.session_ended_no_log)),
        )
        focusEndedScreen()
    }

    private fun releasePads() {
        com.droiddeck.launcher.input.HomeButtonShortcuts.cancel(this)
        backQamInput.cancel()
        padBridge?.releaseAll()
        joyConVr?.releaseButtons()
    }

    private fun focusEndedScreen() {
        drawerOpen = false
        releasePads()
        uiHandler.post { sessionOverlay.requestFocus() }
    }

    private fun repairRuntime() {
        val again = Intent(intent).putExtra(EXTRA_REPAIR_RUNTIME, true)
        closeAtOnce = true
        finish()
        startActivity(again)
    }

    private fun retrySession() {
        val again = Intent(intent)
        closeAtOnce = true
        finish()
        startActivity(again)
    }

    private class EndHint(val text: String, val evenOnSuccess: Boolean = false, val repair: Boolean = false)

    private fun runtimeDamageHint(): EndHint? {
        val broken = runCatching { com.droiddeck.launcher.runtime.RuntimeHealth.brokenLibraryLinks(this) }.getOrDefault(emptyList())
        if (broken.isEmpty()) return null
        Log.w(TAG, "runtime has ${broken.size} broken library link(s): ${broken.take(8).joinToString()}")
        return EndHint(resources.getQuantityString(R.plurals.session_end_runtime_damaged, broken.size, broken.size, broken.first()), repair = true)
    }

    private fun prootFailureHint(): EndHint? {
        if (SessionState.failureCode != "PROOT_MISSING") return null
        val message = SessionState.failureMessage ?: return null
        return EndHint(message)
    }

    private fun sessionEndHint(log: File?): EndHint? {
        if (log == null || !log.isFile) return null
        return try {
            var enosys = 0
            var fexMissing = false
            val size = log.length()
            val text = java.io.RandomAccessFile(log, "r").use { f ->
                val start = maxOf(0L, size - 512L * 1024)
                f.seek(start)
                val bytes = ByteArray((size - start).toInt())
                f.readFully(bytes)
                String(bytes, Charsets.ISO_8859_1)
            }
            text.lineSequence().forEach { line ->
                if (line.contains("Function not implemented")) enosys++
                if (line.contains(FEX_MISSING)) fexMissing = true
            }
            val steamSetup = com.droiddeck.launcher.session.SteamClientSetupFailure.find(text.lineSequence())
            val launchFailure = com.droiddeck.launcher.session.SessionLaunchFailure.find(text.lineSequence())
            if (steamSetup != null) {
                val message = getString(if (steamSetup.download) R.string.session_end_steam_download else R.string.session_end_steam_setup)
                EndHint(listOfNotNull(message, steamSetup.curlError).joinToString("\n"))
            } else if (launchFailure == com.droiddeck.launcher.session.SessionLaunchFailure.STEAM_ILLEGAL_INSTRUCTION) {
                val message = getString(R.string.session_end_steam_sigill)
                val fallback = if (SessionState.steamClientArch == "arm64") getString(R.string.session_end_steam_try_x64) else null
                EndHint(listOfNotNull(message, fallback).joinToString("\n"))
            } else if (launchFailure == com.droiddeck.launcher.session.SessionLaunchFailure.VULKAN_DEVICE_MISSING) {
                val adreno = com.droiddeck.launcher.gpu.GpuInfo.detect().family != com.droiddeck.launcher.gpu.GpuInfo.Family.NOT_ADRENO
                EndHint(getString(if (adreno) R.string.session_end_vulkan_device else R.string.session_end_gpu_unsupported))
            } else if (fexMissing) {
                val what = com.droiddeck.launcher.frontend.Library.nameForProgram(SessionState.program) ?: getString(R.string.session_end_program)
                EndHint(getString(R.string.session_end_fex_missing, what), evenOnSuccess = true)
            } else if (enosys >= 8) {
                EndHint(resources.getQuantityString(R.plurals.session_hint_enosys, enosys, enosys,
                    getString(R.string.perf_title), getString(R.string.perf_seccomp), getString(R.string.perf_xalia)))
            } else null
        } catch (e: Exception) {
            null
        }
    }


    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> {
                val steamVolume =
                    SessionState.mode == SessionService.MODE_STEAM && SessionState.running &&
                    SessionState.firstFrameSeen && !SessionState.suspended && !ended &&
                    !pipUi && !drawerOpen && !pcKeyboardOpen && !achievementsOpen &&
                    !gestureSettingsOpen && !screenKeyTextFocus && window.decorView.hasWindowFocus()
                if (steamVolume) {
                    val audio = getSystemService(android.media.AudioManager::class.java)
                    if (com.droiddeck.launcher.input.SteamVolumeKeys.dispatch(event, !audio.isVolumeFixed) { direction ->
                            audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, direction, 0)
                        }) return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        val backToQam = !pipUi && !screenKeyTextFocus && !drawerOpen && !pcKeyboardOpen && !achievementsOpen &&
            SessionState.mode == SessionService.MODE_STEAM && SessionState.running && SessionState.firstFrameSeen &&
            !SessionState.suspended && padBridge?.canHoldQam() == true
        if (backQamInput.onKeyEvent(event, backToQam)) {
            pendingBackAction?.let(uiHandler::removeCallbacks)
            pendingBackAction = null
            return true
        }
        if (pipUi) return true
        if (screenKeyTextFocus) return super.dispatchKeyEvent(event)
        val pad = com.droiddeck.launcher.input.JoyCon.normalize(event)
        if (pad !== event) return dispatchKeyEvent(pad)
        val fromController = PadBridge.isControllerKeyEvent(event)
        if (fromController && event.action == KeyEvent.ACTION_DOWN) {
            if (drawerOpen && !drawerControllerActive) focusDrawer()
            drawerControllerActive = true
        }
        if (fromController && event.keyCode == KeyEvent.KEYCODE_BUTTON_B && ::loading.isInitialized && loading.visible && !loading.ended && !drawerOpen) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                SessionService.stop(this)
                finish()
            }
            return true
        }
        if (::loading.isInitialized && loading.ended) {
            if (event.keyCode == KeyEvent.KEYCODE_BUTTON_B || event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) finish()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        val drawerShortcutKey = event.deviceId to event.keyCode
        if (fromController && (event.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT || event.keyCode == KeyEvent.KEYCODE_BUTTON_START) &&
            ((drawerOpen && SessionState.mode == SessionService.MODE_STEAM) || drawerShortcutKey in drawerShortcutKeysDown)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                drawerShortcutKeysDown.add(drawerShortcutKey)
                if (event.repeatCount == 0) {
                    releaseDrawerDirection()
                    if (event.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT) sendSteamGuide() else triggerSteamQam()
                }
            } else if (event.action == KeyEvent.ACTION_UP) {
                drawerShortcutKeysDown.remove(drawerShortcutKey)
            }
            return true
        }
        if (drawerOpen) {
            if (fromController && (event.keyCode == KeyEvent.KEYCODE_BUTTON_L1 || event.keyCode == KeyEvent.KEYCODE_BUTTON_R1)) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    drawerPage = sessionDrawerPages(secondScreenDisplays.isNotEmpty(), glasses.present)
                        .step(drawerPage, if (event.keyCode == KeyEvent.KEYCODE_BUTTON_R1) 1 else -1)
                    releaseDrawerDirection()
                }
                return true
            }
            val handled = dispatchToDrawer(event)
            if (event.action == KeyEvent.ACTION_DOWN) {
                Log.i(TAG, "drawer key=${KeyEvent.keyCodeToString(event.keyCode)} handled=$handled viewFocused=${sessionOverlay.hasFocus()}")
            }
            if (event.keyCode == KeyEvent.KEYCODE_BUTTON_B || event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (!handled && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    if (drawerLayerOpen) drawerBackRequests++ else drawerOpen = false
                }
                releaseDrawerDirection()
            }
            return true
        }
        val resumeKey = event.keyCode == KeyEvent.KEYCODE_BUTTON_A || event.keyCode == KeyEvent.KEYCODE_BUTTON_START ||
            event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE || HomeGuideInput.isHome(event.keyCode)
        val resumeKeyId = event.deviceId to event.keyCode
        if (fromController && resumeKey && (SessionState.suspended || resumeKeyId in resumeKeysDown)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                resumeKeysDown.add(resumeKeyId)
                if (event.repeatCount == 0 && SessionState.suspended) SessionService.resume(this)
            } else if (event.action == KeyEvent.ACTION_UP) {
                resumeKeysDown.remove(resumeKeyId)
            }
            return true
        }
        if ((pcKeyboardOpen || achievementsOpen) && fromController) return super.dispatchKeyEvent(event)
        if (joyConVr?.onKeyEvent(event) == true) return true
        if (fromController && event.keyCode != KeyEvent.KEYCODE_BACK && padMouseMode() && padMouse.onKey(event)) return true
        if (event.keyCode != KeyEvent.KEYCODE_BACK && padBridge?.onKeyEvent(event) == true) return true
        if (CompositorHost.isStarted && event.keyCode != KeyEvent.KEYCODE_BACK && !fromController) {
            val down = event.action == KeyEvent.ACTION_DOWN
            if (down && event.repeatCount > 0) return true
            if (down || event.action == KeyEvent.ACTION_UP) {
                var evdev = EvdevKeys.fromKeyCode(event.keyCode)
                val ch = event.unicodeChar
                val plain = if (ch > 0) EvdevKeys.unshiftedChar(ch) else 0
                if (evdev <= 0 && ch > 0) {
                    val code = EvdevKeys.fromKeyCode(EvdevKeys.keycodeForChar(if (plain != 0) plain else ch))
                    if (code > 0) evdev = code
                }
                val shiftEvdev = if (evdev > 0 && plain != 0 && event.deviceId <= 0) 42 else 0
                if (evdev <= 0 && event.scanCode > 0) evdev = event.scanCode
                if (evdev > 0) {
                    if (down) {
                        if (shiftEvdev != 0) sendGuestKey(shiftEvdev, true)
                        sendGuestKey(evdev, true)
                    } else {
                        sendGuestKey(evdev, false)
                        if (shiftEvdev != 0) sendGuestKey(shiftEvdev, false)
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (pipUi) return true
        notePress(event)
        if (drawerOpen && PadBridge.isControllerMotionEvent(event)) {
            if (!drawerControllerActive) focusDrawer()
            drawerControllerActive = true
            if (event.actionMasked == MotionEvent.ACTION_MOVE &&
                event.isFromSource(InputDevice.SOURCE_JOYSTICK)
            ) {
                dispatchDrawerDirection(event)
            }
            return true
        }
        if (drawerDirectionKey != KeyEvent.KEYCODE_UNKNOWN) releaseDrawerDirection()
        if ((pcKeyboardOpen || achievementsOpen) && PadBridge.isControllerMotionEvent(event)) return super.dispatchGenericMotionEvent(event)
        if (joyConVr?.onMotionEvent(event) == true) return true
        if (PadBridge.isControllerMotionEvent(event) && padMouseMode() && padMouse.onMotion(event)) return true
        if (padBridge?.onMotionEvent(event) == true) return true
        if (event.isFromSource(android.view.InputDevice.SOURCE_MOUSE) && !drawerOpen && onMouse(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTrackballEvent(event: MotionEvent): Boolean =
        onCapturedPointer(event) || super.dispatchTrackballEvent(event)

    /** Whether one of the on-screen control layers takes a finger going down at this screen point. */
    private fun controlTakesTouchAt(rawX: Float, rawY: Float): Boolean {
        val at = IntArray(2)
        fun takes(view: View, hit: (Float, Float) -> Boolean): Boolean {
            if (!view.isShown) return false
            view.getLocationOnScreen(at)
            val x = rawX - at[0]
            val y = rawY - at[1]
            return x >= 0f && y >= 0f && x < view.width && y < view.height && hit(x, y)
        }
        return onScreenControls?.let { takes(it, it::takesTouchAt) } == true ||
            steamTouchControls?.let { takes(it, it::takesTouchAt) } == true ||
            screenKeys?.let { takes(it, it::takesTouchAt) } == true ||
            unfoldedPanel?.let { takes(it, it::takesTouchAt) } == true
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (pipUi) return true
        notePress(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN && event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
            drawerControllerActive = false
            gestureTouchStreamGate.reset()
        }
        return super.dispatchTouchEvent(event)
    }

    private fun dispatchDrawerDirection(event: MotionEvent) {
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val hatActive = maxOf(abs(hatX), abs(hatY)) >= DRAWER_HAT_THRESHOLD
        val x = if (hatActive) hatX else centeredStickAxis(event, MotionEvent.AXIS_X)
        val y = if (hatActive) hatY else centeredStickAxis(event, MotionEvent.AXIS_Y)
        val directionThreshold = if (drawerDirectionKey == KeyEvent.KEYCODE_UNKNOWN) {
            DRAWER_STICK_ENTER_THRESHOLD
        } else {
            DRAWER_STICK_EXIT_THRESHOLD
        }
        val previousWasHorizontal = drawerDirectionKey == KeyEvent.KEYCODE_DPAD_LEFT ||
            drawerDirectionKey == KeyEvent.KEYCODE_DPAD_RIGHT
        val horizontalWins = if (previousWasHorizontal) {
            abs(x) >= abs(y) * DRAWER_ACTIVE_DIRECTION_MARGIN
        } else {
            abs(x) > abs(y) * DRAWER_DIRECTION_MARGIN
        }
        val keyCode = when {
            maxOf(abs(x), abs(y)) < directionThreshold -> KeyEvent.KEYCODE_UNKNOWN
            horizontalWins && x < 0f -> KeyEvent.KEYCODE_DPAD_LEFT
            horizontalWins -> KeyEvent.KEYCODE_DPAD_RIGHT
            y < 0f -> KeyEvent.KEYCODE_DPAD_UP
            else -> KeyEvent.KEYCODE_DPAD_DOWN
        }
        val now = SystemClock.uptimeMillis()
        if (keyCode == drawerDirectionKey) return

        Log.i(TAG, "drawer axis source=${event.source} x=$x y=$y direction=$keyCode")
        releaseDrawerDirection()
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return

        drawerDirectionKey = keyCode
        drawerDirectionDeviceId = event.deviceId
        drawerDirectionDownTime = now
        drawerDirectionRepeatCount = 0
        dispatchDrawerKey(KeyEvent.ACTION_DOWN, 0, now)
        uiHandler.postDelayed(drawerDirectionRepeat, drawerFirstRepeatDelayMs)
    }

    private fun centeredStickAxis(event: MotionEvent, axis: Int): Float {
        val value = event.getAxisValue(axis)
        val flat = (event.device?.getMotionRange(axis, event.source)?.flat ?: 0f)
            .coerceIn(0f, 0.95f)
        val magnitude = abs(value)
        if (magnitude <= flat) return 0f

        val centered = ((magnitude - flat) / (1f - flat)).coerceIn(0f, 1f)
        return if (value < 0f) -centered else centered
    }

    private fun releaseDrawerDirection() {
        uiHandler.removeCallbacks(drawerDirectionRepeat)
        if (drawerDirectionKey == KeyEvent.KEYCODE_UNKNOWN) return
        dispatchDrawerKey(KeyEvent.ACTION_UP, 0, SystemClock.uptimeMillis())
        drawerDirectionKey = KeyEvent.KEYCODE_UNKNOWN
        drawerDirectionDeviceId = -1
        drawerDirectionRepeatCount = 0
    }

    private fun dispatchDrawerKey(action: Int, repeatCount: Int, eventTime: Long) {
        val event = KeyEvent(
            drawerDirectionDownTime, eventTime, action, drawerDirectionKey,
            repeatCount, 0, drawerDirectionDeviceId, 0, 0, InputDevice.SOURCE_DPAD,
        )
        if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) {
            Log.i(TAG, "drawer synthetic key=${KeyEvent.keyCodeToString(drawerDirectionKey)} viewFocused=${sessionOverlay.hasFocus()}")
        }
        dispatchToDrawer(event)
    }

    private fun dockedMenu(): SecondScreenPresentation? = secondScreenPresentation?.takeIf { drawerDocked && it.menuShown }

    private fun dispatchToDrawer(event: KeyEvent): Boolean {
        val bottom = dockedMenu()
        if (bottom != null && event.keyCode != KeyEvent.KEYCODE_BACK) return bottom.dispatchMenuKey(event)
        return super.dispatchKeyEvent(event)
    }

    private fun focusDrawer(): Boolean = dockedMenu()?.focusMenu() ?: sessionOverlay.requestFocus()

    private fun dockDrawer(): Boolean {
        if (pipUi || !started || glassesScreen != null || !::displayManager.isInitialized) return false
        if (!SessionPrefs.dualScreenLauncher(this)) return false
        val target = com.droiddeck.launcher.input.DualScreen.display(this, displayManager, shownOnDisplayId) ?: return false
        if (secondScreenDisplays.none { it.id == target.displayId }) return false
        val shown = secondScreenPresentation?.display?.displayId
        return shown == null || shown == target.displayId
    }

    private fun openDockedMenu(): Boolean {
        val target = com.droiddeck.launcher.input.DualScreen.display(this, displayManager, shownOnDisplayId) ?: return false
        return try {
            val presentation = secondScreenPresentation ?: secondScreenPresentation(target).also {
                selectedSecondScreenDisplay = target.displayId
                secondScreenPresentation = it
            }
            if (!presentation.isShowing) presentation.show()
            presentation.showMenu(true)
            updateKeyboardRoute()
            presentation.menuShown
        } catch (e: Exception) {
            Log.w(TAG, "could not open the menu on ${target.name}", e)
            if (secondScreenMode == SecondScreenMode.NONE) closeSecondScreen(reset = false)
            false
        }
    }

    private fun closeDockedMenu() {
        drawerDocked = false
        val presentation = secondScreenPresentation ?: return
        presentation.showMenu(false)
        if (secondScreenMode == SecondScreenMode.NONE) closeSecondScreen(reset = false) else updateKeyboardRoute()
    }

    private fun layoutNames(): List<String> =
        com.droiddeck.launcher.input.ControllerPrefs.screenLayouts(this).keys.sorted()

    private fun refreshGestureConfig() {
        gestureConfig = com.droiddeck.launcher.input.GesturePrefs.read(this, gestureGame)
        directHoldBehavior = gestureConfig.directHoldBehavior
        gestureTouchStreamGate.reset()
        if (::touchscreenGestures.isInitialized) touchscreenGestures.configure(gestureConfig)
        if (::directTouchHold.isInitialized) directTouchHold.configure(gestureConfig)
        gesturePointerReady = false
        recognizedGesture = null
    }

    private val gestureOutput = object : TouchscreenGestures.Output {
        override fun moveTo(x: Float, y: Float) {
            val rect = drawnRect() ?: return
            if (pointerLocked) {
                if (gesturePointerReady) moveBy(x - gesturePointerX, y - gesturePointerY, true)
            } else movePointer(x, y)
            gesturePointerX = if (pointerLocked) x else x.coerceIn(rect.left, rect.right)
            gesturePointerY = if (pointerLocked) y else y.coerceIn(rect.top, rect.bottom)
            gesturePointerReady = true
            cursorVisible = false
        }
        override fun moveBy(dx: Float, dy: Float, relative: Boolean) {
            val rect = drawnRect() ?: return
            if (relative || pointerLocked) {
                val scale = sceneScale(rect)
                sendSceneDelta(dx * scale, dy * scale)
            } else moveTo(gesturePointerX + dx, gesturePointerY + dy)
        }
        override fun button(code: Int, down: Boolean) {
            sendSceneButton(code, down)
        }
        override fun prepareRelativePan() {
            if (pointerLocked) return
            val rect = drawnRect() ?: return
            // Gamescope retains the last absolute position while a game can warp
            // its own cursor to the center. Its first unlocked motion must start
            // from that same origin, before the game switches to pointer lock.
            moveTo(rect.centerX(), rect.centerY())
        }
        override fun key(code: Int, down: Boolean) {
            if (down) gestureKeysDown.add(code) else gestureKeysDown.remove(code)
            if (code !in guestKeysDown) WaylandCompositor.nativeSendKey(code, if (down) 1 else 0)
        }
        override fun wheel(steps: Int) { sendSceneWheel(steps) }
        override fun showKeyboard() { uiHandler.post { touchscreenGestures.cancel(); togglePcKeyboard() } }
        override fun gesture(gesture: TouchscreenGestures.Gesture) {
            recognizedGesture = gesture
            uiHandler.removeCallbacks(clearRecognizedGesture)
            uiHandler.postDelayed(clearRecognizedGesture, 1200)
        }
    }

    private val pointerListener = object : PointerGestures.Listener {
        override fun onMove(x: Float, y: Float) {
            if (pointerLocked) moveLockedPointer(x, y) else movePointer(x, y)
        }
        override fun onButton(button: Int, pressed: Boolean, x: Float, y: Float) {
            if (!pointerLocked) movePointer(x, y)
            sendSceneButton(button, pressed)
        }
        override fun onWheel(steps: Int) { sendSceneWheel(steps) }
        override fun onScroll(dx: Float, dy: Float) = fingerScroll(dx, dy)
        override fun onScrollEnd() = fingerScrollEnd()
        override fun onZoom(steps: Int) {
            WaylandCompositor.nativeSendKey(KEY_LEFTCTRL, 1)
            sendSceneWheel(steps)
            WaylandCompositor.nativeSendKey(KEY_LEFTCTRL, 0)
        }
        override fun onLongPress() {
            @Suppress("DEPRECATION")
            (getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator)?.vibrate(15)
        }
    }

    private val scrollListener = object : ScrollTouchGestures.Listener {
        override fun onMove(x: Float, y: Float) {
            val rect = drawnRect() ?: return
            val cx = x.coerceIn(rect.left, rect.right)
            val cy = y.coerceIn(rect.top, rect.bottom)
            WaylandCompositor.nativeSendPointer(1, InputSpace.x(cx, surfaceView.width), InputSpace.y(cy, surfaceView.height))
            cursorVisible = false
        }
        override fun onButton(button: Int, pressed: Boolean) {
            WaylandCompositor.nativeSendSceneInput(3, button, if (pressed) 1 else 0)
        }
        override fun onScroll(dx: Float, dy: Float) {
            val rect = drawnRect() ?: return
            val out = SessionState.outputSize
            scrollRestX += dx * out.first / rect.width()
            scrollRestY += dy * out.second / rect.height()
            val h = scrollRestX.toInt()
            val v = scrollRestY.toInt()
            if (h == 0 && v == 0) return
            scrollRestX -= h
            scrollRestY -= v
            WaylandCompositor.nativeSendSceneInput(11, ScrollTouchGestures.wheelUnits(v, out.second), ScrollTouchGestures.wheelUnits(h, out.first))
        }
        override fun onLongPress() = pointerListener.onLongPress()
    }

    private val padMouseListener = object : PadMouse.Listener {
        override fun active(): Boolean = padMouseMode()
        override fun onScrollStart() {
            scrollRestX = 0f
            scrollRestY = 0f
            val rect = drawnRect() ?: return
            val x = rect.left + rect.width() * PAD_SCROLL_POINTER_X
            WaylandCompositor.nativeSendPointer(1, InputSpace.x(x, surfaceView.width), InputSpace.y(rect.centerY(), surfaceView.height))
            cursorVisible = false
        }
        override fun onScroll(dx: Float, dy: Float) = scrollListener.onScroll(dx, dy)
        override fun onKey(evdev: Int, pressed: Boolean) = sendGuestKey(evdev, pressed)
        override fun onKeyboard() {
            uiHandler.post { togglePcKeyboard() }
        }
        override fun onNavigate() {
            cursorVisible = false
        }
        override fun scrollSpeed(): Float = (drawnRect()?.height() ?: 0f) * PAD_SCROLL_HEIGHTS_PER_S
    }

    private fun padMouseMode(): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - padMouseCheckedAt < PAD_MOUSE_CHECK_MS) return padMouseOn
        padMouseCheckedAt = now
        val on = !pointerLocked && EpicClient.launcherFocused(this) && !EpicClient.playing(this)
        if (padMouseOn && !on) padMouse.reset()
        if (!padMouseOn && on) padBridge?.releaseAll()
        padMouseOn = on
        return on
    }

    private fun scrollTouchMode(): Boolean =
        (routedTouchMode == SessionPrefs.TOUCH_AUTO || routedTouchMode == SessionPrefs.TOUCH_DIRECT) && padMouseMode()

    private fun fingerScroll(dx: Float, dy: Float) {
        val rect = drawnRect() ?: return
        val out = SessionState.outputSize
        scrollRestX += dx * out.first / rect.width() * AXIS_UNITS_PER_PIXEL
        scrollRestY += dy * out.second / rect.height() * AXIS_UNITS_PER_PIXEL
        val h = scrollRestX.toInt()
        val v = scrollRestY.toInt()
        if (h == 0 && v == 0) return
        scrollRestX -= h
        scrollRestY -= v
        WaylandCompositor.nativeSendSceneInput(12, v, h)
    }

    private fun fingerScrollEnd() {
        scrollRestX = 0f
        scrollRestY = 0f
        WaylandCompositor.nativeSendSceneInput(12, 0, 0)
    }

    private fun placeTouchpadAtGuest(rect: android.graphics.RectF) {
        if (pointerLocked || !CompositorHost.isStarted || surfaceView.width <= 0 || surfaceView.height <= 0) return
        if (!WaylandCompositor.nativeCursorInputPos(cursorPosBuf)) return
        val x = cursorPosBuf[0] / InputSpace.WIDTH.toFloat() * surfaceView.width
        val y = cursorPosBuf[1] / InputSpace.HEIGHT.toFloat() * surfaceView.height
        if (rect.contains(x, y)) touchpad.place(x, y)
    }

    private fun moveLockedPointer(x: Float, y: Float) {
        val rect = drawnRect() ?: return
        val scale = sceneScale(rect)
        sendSceneDelta((x - rect.centerX()) * scale, (y - rect.centerY()) * scale)
        touchpad.place(rect.centerX(), rect.centerY())
        cursorVisible = false
    }

    private fun movePointer(x: Float, y: Float) {
        val rect = drawnRect() ?: return
        val cx = x.coerceIn(rect.left, rect.right)
        val cy = y.coerceIn(rect.top, rect.bottom)
        WaylandCompositor.nativeSendPointer(1, InputSpace.x(cx, surfaceView.width), InputSpace.y(cy, surfaceView.height))
        showCursor(cx, cy)
    }

    private fun showCursor(x: Float, y: Float) {
        cursorPos = androidx.compose.ui.geometry.Offset(x, y)
        syncClientCursor()
        uiHandler.removeCallbacks(cursorResync)
        cursorResyncIdle = 0
        if (!touchDrivesPointer()) uiHandler.postDelayed(cursorResync, 60)
        if (clientHidesCursor) { cursorVisible = false; return }
        if (directTouchCursorHidden()) { cursorVisible = false; return }
        if (android.os.SystemClock.uptimeMillis() - lastPadInputMs < CURSOR_PAD_HOLD_MS) return
        cursorVisible = true
        uiHandler.removeCallbacks(cursorHide)
        if (SessionState.mode == SessionService.MODE_STEAM && SessionState.steamUi != "desktop") uiHandler.postDelayed(cursorHide, 2500)
    }

    private fun touchDrivesPointer(): Boolean =
        routedTouchMode == SessionPrefs.TOUCH_DIRECT ||
            (routedTouchMode == SessionPrefs.TOUCH_AUTO && !usingTouchpad())

    private fun directTouchCursorHidden(): Boolean = !directCursorOn && touchDrivesPointer()

    private fun syncClientCursor(): Boolean {
        if (!CompositorHost.isStarted) return false
        if (WaylandCompositor.nativeCursorSerial() == cursorSerial) return false
        val n = WaylandCompositor.nativeCursorSnapshot(cursorBuf)
        if (n < 6) return false
        val serial = cursorBuf[0]
        if (serial == 0 || serial == cursorSerial) return false
        cursorSerial = serial
        clientHidesCursor = cursorBuf[1] != 0
        if (clientHidesCursor) return true
        val w = cursorBuf[2]
        val h = cursorBuf[3]
        if (w <= 0 || h <= 0 || n < 6 + w * h) return false
        val bitmap = try {
            android.graphics.Bitmap.createBitmap(cursorBuf, 6, w, w, h, android.graphics.Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            Log.w(TAG, "cursor bitmap failed", e)
            return false
        }
        cursorImage = bitmap.asImageBitmap()
        cursorHotX = cursorBuf[4]
        cursorHotY = cursorBuf[5]
        val width = surfaceView.width.takeIf { it > 0 }
        val height = surfaceView.height.takeIf { it > 0 }
        val out = SessionState.outputSize
        if (width != null && height != null)
            cursorImageScale = minOf(width / out.first.toFloat(), height / out.second.toFloat())
        return true
    }

    private val shownOnDisplayId: Int get() =
        if (Build.VERSION.SDK_INT >= 30) display?.displayId ?: windowManager.defaultDisplay.displayId
        else windowManager.defaultDisplay.displayId

    private fun refreshSecondScreenDisplays() {
        if (!::displayManager.isInitialized) return
        val primaryId = shownOnDisplayId
        val candidates = SecondScreenDisplays.available(displayManager, primaryId)
            .filter { glassesScreen == null || it.id != glasses.displayId }
        val oldIds = secondScreenDisplays.map { it.id }.toSet()
        secondScreenDisplays = candidates
        val newIds = candidates.map { it.id }.toSet()
        if (secondScreenMode != SecondScreenMode.NONE && oldIds.isNotEmpty() && selectedSecondScreenDisplay !in newIds) {
            closeSecondScreen(reset = true)
            return
        }
        if (selectedSecondScreenDisplay !in newIds) selectedSecondScreenDisplay = candidates.firstOrNull()?.id ?: -1
        if (secondScreenMode != SecondScreenMode.NONE && candidates.isEmpty()) closeSecondScreen(reset = true)
        else if (started && secondScreenMode != SecondScreenMode.NONE && secondScreenPresentation == null) showSecondScreen(secondScreenMode)
        updateKeyboardRoute()
    }

    private fun openDualScreenControls() {
        if (SessionState.secondScreenAutoOpened || secondScreenMode != SecondScreenMode.NONE || pipUi) return
        if (!SessionPrefs.dualScreenSessionControls(this)) return
        val target = com.droiddeck.launcher.input.DualScreen.display(this, displayManager, shownOnDisplayId) ?: return
        if (secondScreenDisplays.none { it.id == target.displayId }) return
        SessionState.secondScreenAutoOpened = true
        selectedSecondScreenDisplay = target.displayId
        selectSecondScreenMode(SecondScreenMode.opening(SessionPrefs.secondScreenPanel(this), SessionState.deckPad))
    }

    private fun selectSecondScreenDisplay(displayId: Int) {
        if (secondScreenDisplays.none { it.id == displayId }) return
        selectedSecondScreenDisplay = displayId
        SessionState.secondScreenDisplay = displayId
        if (secondScreenMode != SecondScreenMode.NONE) showSecondScreen(secondScreenMode)
    }

    private fun selectSecondScreenMode(mode: SecondScreenMode) {
        if (mode == SecondScreenMode.NONE) {
            closeSecondScreen(reset = true)
            return
        }
        if (secondScreenDisplays.isEmpty()) {
            secondScreenMode = SecondScreenMode.NONE
            return
        }
        if (selectedSecondScreenDisplay !in secondScreenDisplays.map { it.id }) {
            selectedSecondScreenDisplay = secondScreenDisplays.first().id
        }
        showSecondScreen(mode)
    }

    private fun showSecondScreen(mode: SecondScreenMode) {
        if (pipUi || !started) return
        val target = displayManager.getDisplay(selectedSecondScreenDisplay)
        if (target == null || !target.isValid || (target.flags and Display.FLAG_PRESENTATION) == 0) {
            closeSecondScreen(reset = false)
            return
        }
        var presentation = secondScreenPresentation
        if (presentation?.display?.displayId != target.displayId) {
            dropDockedDrawer()
            presentation?.closeControls()
            presentation = secondScreenPresentation(target)
            secondScreenPresentation = presentation
        }
        try {
            if (!presentation.isShowing) presentation.show()
            presentation.showMode(mode)
            secondScreenMode = mode
            SessionState.secondScreenMode = mode
            SessionState.secondScreenDisplay = selectedSecondScreenDisplay
            updateKeyboardRoute()
        } catch (e: Exception) {
            Log.w(TAG, "could not show second-screen controls on ${target.name}", e)
            closeSecondScreen(reset = true)
        }
    }

    private fun secondScreenPresentation(target: Display) = SecondScreenPresentation(
        this, target,
        sendPointer = ::moveSecondScreenPointer,
        sendButton = { button, pressed ->
            WaylandCompositor.nativeSendSceneInput(3, button, if (pressed) 1 else 0)
        },
        sendWheel = { steps -> WaylandCompositor.nativeSendSceneInput(4, steps, 0) },
        onSteamMenu = ::tapSteamButton,
        onQam = ::tapQam,
        onClose = { selectSecondScreenMode(SecondScreenMode.NONE) },
        pad = padBridge,
        onKeyboardClosed = ::bottomKeyboardClosed,
        onMenu = if (dockDrawer()) ({ drawerOpen = true }) else null,
        menu = {
            DroidDeckTheme { SessionMenu(docked = true) }
        },
    )

    private fun tapSteamButton() {
        if (SessionState.mode == SessionService.MODE_STEAM) {
            padBridge?.applyTouch { st -> st.press(com.droiddeck.launcher.input.PadState.GUIDE, true) }
            Handler(Looper.getMainLooper()).postDelayed({
                padBridge?.applyTouch { st -> st.press(com.droiddeck.launcher.input.PadState.GUIDE, false) }
            }, 90)
        }
    }

    private fun tapQam() {
        if (SessionState.mode == SessionService.MODE_STEAM) padBridge?.triggerQam()
    }

    private fun chooseUnfoldedControls(on: Boolean) {
        SessionPrefs.setUnfoldedControls(this, on)
        unfoldedControls = on
        applyUnfoldedControls()
    }

    private fun chooseUnfoldedControlsWithController(on: Boolean) {
        SessionPrefs.setUnfoldedControlsWithController(this, on)
        unfoldedControlsWithController = on
        applyUnfoldedControls()
    }

    private fun refreshControls() {
        applyUnfoldedControls()
        updateOnScreenControls()
        screenKeys?.refreshAppearance()
    }

    private fun controlsBelowWanted(): Boolean {
        if (!::sessionRoot.isInitialized || glassesScreen != null || cardboard.enabled) return false
        return SessionPrefs.controlsBelowWanted(this, SessionState.mode, sessionRoot.width, sessionRoot.height,
            pipUi || isInPictureInPictureMode)
    }

    private fun applyUnfoldedControls() {
        if (!::sessionRoot.isInitialized) return
        val width = sessionRoot.width
        val height = sessionRoot.height
        val wanted = controlsBelowWanted()
        val gameHeight = if (wanted) com.droiddeck.launcher.session.SessionDisplay.unfoldedGameHeight(width, height)
            else FrameLayout.LayoutParams.MATCH_PARENT
        val params = surfaceView.layoutParams as FrameLayout.LayoutParams
        if (params.height != gameHeight) {
            surfaceView.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, gameHeight, Gravity.TOP)
            Log.i(TAG, if (wanted) "unfolded controls: game ${width}x$gameHeight, controls ${width}x${height - gameHeight}"
                else "unfolded controls: game full screen")
        }
        var panel = unfoldedPanel
        if (panel != null && panel.deckControls != SessionState.deckPad) {
            sessionRoot.removeView(panel)
            unfoldedPanel = null
            panel = null
        }
        if (!wanted) {
            if (panel != null) { sessionRoot.removeView(panel); unfoldedPanel = null; updateOnScreenControls() }
            return
        }
        if (panel == null) {
            panel = com.droiddeck.launcher.ui.DeckControlsPanel(this, onClose = { chooseUnfoldedControls(false) },
                onSteamMenu = ::tapSteamButton, onQam = ::tapQam, pad = padBridge,
                portraitDock = !com.droiddeck.launcher.session.SessionDisplay.foldable(this))
            sessionRoot.addView(panel, sessionRoot.indexOfChild(surfaceView) + 1)
            unfoldedPanel = panel
            updateOnScreenControls()
        }
        panel.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, height - gameHeight, Gravity.BOTTOM)
    }

    private fun moveSecondScreenPointer(x: Float, y: Float, width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        val nx = (x / width).coerceIn(0f, 1f)
        val ny = (y / height).coerceIn(0f, 1f)
        val rect = drawnRect() ?: return
        val px = rect.left + nx * rect.width()
        val py = rect.top + ny * rect.height()
        WaylandCompositor.nativeSendPointer(1, InputSpace.x(px, surfaceView.width), InputSpace.y(py, surfaceView.height))
        showCursor(px, py)
    }

    private fun openBottomKeyboard() {
        if (!started) return
        if (secondScreenPresentation?.showsKeyboard != true && dockDrawer()) {
            val target = com.droiddeck.launcher.input.DualScreen.display(this, displayManager, shownOnDisplayId) ?: return
            if (drawerOpen) drawerOpen = false
            typingReturnMode = secondScreenMode
            selectedSecondScreenDisplay = target.displayId
            selectSecondScreenMode(SecondScreenMode.KEYBOARD_TRACKPAD)
        }
        val presentation = secondScreenPresentation ?: return
        if (!presentation.showsKeyboard) return
        val display = presentation.display?.displayId ?: return
        presentation.requestKeyboard(forField = true)
        com.droiddeck.launcher.ui.SessionTyping.focusDisplay(this, display)
    }

    private fun bottomKeyboardClosed() {
        com.droiddeck.launcher.ui.SessionTyping.returnFocus(this, taskId)
        val mode = typingReturnMode ?: return
        typingReturnMode = null
        uiHandler.post { if (secondScreenMode == SecondScreenMode.KEYBOARD_TRACKPAD) selectSecondScreenMode(mode) }
    }

    private fun updateKeyboardRoute() {
        AgentGuest.routeKeyboard(this, SessionState.mode == SessionService.MODE_STEAM &&
            (secondScreenPresentation?.showsKeyboard == true || dockDrawer()))
    }

    private fun dropDockedDrawer() {
        if (!drawerDocked) return
        drawerDocked = false
        drawerLayerOpen = false
        drawerOpenState = false
    }

    private fun closeSecondScreen(reset: Boolean) {
        dropDockedDrawer()
        val focused = secondScreenPresentation?.window?.decorView?.hasWindowFocus() == true
        secondScreenPresentation?.closeControls()
        secondScreenPresentation = null
        if (focused && started) com.droiddeck.launcher.ui.SessionTyping.returnFocus(this, taskId)
        else com.droiddeck.launcher.ui.SessionTyping.close()
        updateKeyboardRoute()
        if (reset) {
            secondScreenMode = SecondScreenMode.NONE
            SessionState.secondScreenMode = SecondScreenMode.NONE
        }
    }

    private fun effectiveTouchMode(): String {
        val options = steamTouchOptions
        if (options == null || steamTouchControls?.visibility != View.VISIBLE) return SessionPrefs.touchMode(this)
        return when {
            options.inputMode == com.droiddeck.launcher.input.SteamTouchConfig.INPUT_CONTROLLER -> SessionPrefs.TOUCH_OFF
            SessionPrefs.touchMode(this) == SessionPrefs.TOUCH_GESTURES -> SessionPrefs.TOUCH_GESTURES
            options.mouseMode == com.droiddeck.launcher.input.SteamTouchConfig.MOUSE_RELATIVE -> SessionPrefs.TOUCH_PAD
            else -> SessionPrefs.TOUCH_DIRECT
        }
    }

    private fun applyTouchRouting() {
        val next = effectiveTouchMode()
        if (::touchpad.isInitialized) {
            val speed = steamTouchOptions?.trackpadSensitivity?.takeIf { steamTouchControls?.visibility == View.VISIBLE } ?: 1f
            touchpad.sensitivity = 1.15f * speed
        }
        if (next == routedTouchMode) return
        if (::touchscreenGestures.isInitialized) touchscreenGestures.cancel()
        gesturePointerReady = false
        if (::touchpad.isInitialized) touchpad.cancel()
        if (::directTouchHold.isInitialized) directTouchHold.cancel()
        if (::twoFingerGestures.isInitialized) twoFingerGestures.cancel()
        if (::scrollTouch.isInitialized) scrollTouch.cancel()
        if (CompositorHost.isStarted &&
            (deliversTouches(routedTouchMode) || deliversTouches(next)))
            WaylandCompositor.nativeSendTouch(3, -1, 0, 0)
        if (next == SessionPrefs.TOUCH_OFF) cursorVisible = false
        gestureTouchStreamGate.reset()
        routedTouchMode = next
        if (directTouchCursorHidden()) cursorVisible = false
    }

    private fun deliversTouches(mode: String) =
        mode == SessionPrefs.TOUCH_DIRECT || mode == SessionPrefs.TOUCH_AUTO

    private fun usingTouchpad(): Boolean = when (routedTouchMode) {
        SessionPrefs.TOUCH_PAD -> true
        SessionPrefs.TOUCH_DIRECT -> false
        else -> SessionState.mode != SessionService.MODE_STEAM || SessionState.steamUi == "desktop"
    }

    private fun drawerTouchMode(): String = when (routedTouchMode) {
        SessionPrefs.TOUCH_GESTURES, SessionPrefs.TOUCH_DIRECT,
        SessionPrefs.TOUCH_OFF, SessionPrefs.TOUCH_PAD -> routedTouchMode
        else -> if (usingTouchpad()) SessionPrefs.TOUCH_PAD else SessionPrefs.TOUCH_DIRECT
    }

    private val gamePictureBounds = FloatArray(4)
    private var topPictureMapped = false

    private fun sceneScale(rect: android.graphics.RectF): Float =
        SessionState.outputSize.first / rect.width()

    private fun openEvdevMouse() {
        if (evdevMouseActive || !evdevMouseOn) return
        val writer = mouseWriter ?: com.droiddeck.launcher.input.FakeInputWriter(
            File(LinuxRuntime.sessionRoot(this), "dev/input").absolutePath,
            com.droiddeck.launcher.input.FakeInputWriter.MOUSE_SLOT,
        ).also { mouseWriter = it }
        if (!writer.open()) return
        evdevMouseActive = true
        lastMouseX = Float.NaN
        lastMouseY = Float.NaN
        Log.i(TAG, "raw mouse channel active (pointer locked)")
    }

    private fun closeEvdevMouse() {
        if (!evdevMouseActive) return
        mouseWriter?.softRelease()
        evdevMouseActive = false
        Log.i(TAG, "raw mouse channel idle")
    }

    private fun releaseEvdevMouse() {
        evdevMouseActive = false
        mouseWriter?.destroy()
        mouseWriter = null
        Log.i(TAG, "raw mouse channel removed")
    }

    private fun sendSceneDelta(dx: Float, dy: Float) {
        val writer = if (evdevMouseActive) mouseWriter else null
        if (writer != null) writer.writeMouseDelta(dx, dy)
        else WaylandCompositor.sendPointerDelta(dx, dy)
    }

    private fun sendSceneButton(code: Int, down: Boolean) {
        val writer = if (evdevMouseActive) mouseWriter else null
        if (writer != null) writer.writeMouseButton(code.toShort(), down)
        else WaylandCompositor.nativeSendSceneInput(3, code, if (down) 1 else 0)
    }

    private fun sendSceneWheel(steps: Int) {
        val writer = if (evdevMouseActive) mouseWriter else null
        if (writer != null) writer.writeMouseWheel(-steps, 0, 120)
        else WaylandCompositor.nativeSendSceneInput(4, steps, 0)
    }

    private fun sendMouseDelta(x: Float, y: Float) {
        cursorVisible = false
        val previousX = lastMouseX
        val previousY = lastMouseY
        lastMouseX = x
        lastMouseY = y
        val rect = drawnRect() ?: return
        if (previousX.isNaN() || previousY.isNaN()) return
        val scale = sceneScale(rect)
        sendSceneDelta((x - previousX) * scale, (y - previousY) * scale)
    }

    private fun drawnRect(): android.graphics.RectF? {
        val width = surfaceView.width.takeIf { it > 0 } ?: return null
        val height = surfaceView.height.takeIf { it > 0 } ?: return null
        topPictureMapped = CompositorHost.isStarted && WaylandCompositor.nativeGamePictureRect(gamePictureBounds)
        if (topPictureMapped) return android.graphics.RectF(
            gamePictureBounds[0], gamePictureBounds[1], gamePictureBounds[2], gamePictureBounds[3])
        val out = SessionState.outputSize
        val scale = minOf(width / out.first.toFloat(), height / out.second.toFloat())
        val drawnW = out.first * scale
        val drawnH = out.second * scale
        val left = (width - drawnW) / 2f
        val top = (height - drawnH) / 2f
        return android.graphics.RectF(left, top, left + drawnW, top + drawnH)
    }

    private var ignoreGameTouch = false
    private var dismissTouch = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) return onMouse(event)
        if (drawerOpen && drawerDocked && event.actionMasked == MotionEvent.ACTION_DOWN) {
            drawerOpen = false
            dismissTouch = true
        }
        if (dismissTouch) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) dismissTouch = false
            return true
        }
        val uiBlocking = drawerOpen || pcKeyboardOpen || achievementsOpen || gestureSettingsOpen || pipUi || SessionState.suspended
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !uiBlocking && routedTouchMode != SessionPrefs.TOUCH_GESTURES) {
            scrollTouchGesture = scrollTouchMode()
            scrollRestX = 0f
            scrollRestY = 0f
        }
        val snapshot = com.droiddeck.launcher.input.TouchRouter.Snapshot(
            uiBlocking = uiBlocking,
            mode = routedTouchMode,
            scrollLayer = scrollTouchGesture,
            steamSession = SessionState.mode == SessionService.MODE_STEAM,
            steamDesktopUi = SessionState.steamUi == "desktop",
        )
        val route = com.droiddeck.launcher.input.TouchRouter.decide(snapshot)
        if (route != touchRoute) {
            Log.i(TAG, "touch route $touchRoute -> $route (mode=$routedTouchMode steamUi=${SessionState.steamUi} scrollLayer=$scrollTouchGesture)")
            touchRoute = route
        }
        return when (route) {
            com.droiddeck.launcher.input.TouchRouter.Route.BLOCKED,
            com.droiddeck.launcher.input.TouchRouter.Route.OFF -> true
            com.droiddeck.launcher.input.TouchRouter.Route.GESTURES -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) gesturePointerReady = false
                touchscreenGestures.onTouch(event)
            }
            com.droiddeck.launcher.input.TouchRouter.Route.SCROLL -> scrollTouch.onTouch(event)
            else -> {
                if (com.droiddeck.launcher.input.TouchRouter.steamKeyboardZone(snapshot)) {
                    val wasActive = threeFingerTap.active
                    if (threeFingerTap.onTouch(event)) padBridge?.triggerSteamKeyboard()
                    if (threeFingerTap.active && !wasActive) {
                        if (route == com.droiddeck.launcher.input.TouchRouter.Route.TOUCHPAD) touchpad.cancel()
                        else {
                            directTouchHold.cancel()
                            WaylandCompositor.nativeSendTouch(3, -1, 0, 0)
                        }
                    }
                    if (threeFingerTap.active || wasActive) return true
                }
                if (route == com.droiddeck.launcher.input.TouchRouter.Route.TOUCHPAD) {
                    val rect = drawnRect() ?: return false
                    if (touchpad.bounds != rect) {
                        val fresh = touchpad.bounds.width() <= 1f
                        touchpad.bounds = rect
                        if (fresh) touchpad.place(rect.centerX(), rect.centerY())
                    }
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        touchpad.desktop = SessionState.mode == SessionService.MODE_DESKTOP
                        placeTouchpadAtGuest(rect)
                    }
                    touchpad.onTouch(event)
                } else {
                    dispatchDirectTouch(event)
                }
            }
        }
    }

    private val directTouchSink = object : DirectTouchHold.Touch {
        override fun down(pointerId: Int, x: Float, y: Float) = sendDirectTouch(0, pointerId, x, y)
        override fun move(pointerId: Int, x: Float, y: Float) = sendDirectTouch(1, pointerId, x, y)
        override fun up(pointerId: Int, x: Float, y: Float) = sendDirectTouch(2, pointerId, x, y)
        override fun cancel() = WaylandCompositor.nativeSendTouch(3, -1, 0, 0)
    }

    private fun sendDirectTouch(action: Int, pointerId: Int, x: Float, y: Float) {
        val rect = drawnRect() ?: return
        val cx = x.coerceIn(rect.left, rect.right)
        val cy = y.coerceIn(rect.top, rect.bottom)
        WaylandCompositor.nativeSendTouch(action, pointerId,
            InputSpace.x(cx, surfaceView.width), InputSpace.y(cy, surfaceView.height))
        showCursor(cx, cy)
    }

    private fun dispatchDirectTouch(event: MotionEvent): Boolean {
        val rect = drawnRect() ?: return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN)
            ignoreGameTouch = topPictureMapped && !rect.contains(event.x, event.y)
        if (ignoreGameTouch) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                directTouchHold.cancel()
                WaylandCompositor.nativeSendTouch(3, -1, 0, 0)
            }
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) ignoreGameTouch = false
            return true
        }
        if (SessionState.mode == SessionService.MODE_DESKTOP && twoFingerGestures.onTouch(event)) return true
        return forwardTouch(event, rect)
    }

    private fun forwardTouch(event: MotionEvent, rect: android.graphics.RectF): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            showCursor(event.x.coerceIn(rect.left, rect.right), event.y.coerceIn(rect.top, rect.bottom))
            if (WaylandCompositor.nativeFocusTakesTouch()) {
                listOf(PointerGestures.BTN_LEFT, PointerGestures.BTN_RIGHT, PointerGestures.BTN_MIDDLE).forEach {
                    WaylandCompositor.nativeSendSceneInput(3, it, 0)
                }
            }
        }
        directTouchHold.onTouch(event)
        return true
    }

    private fun onMouse(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_ENTER ->
                if (evdevMouseActive) sendMouseDelta(event.x, event.y) else movePointer(event.x, event.y)
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                if (evdevMouseActive) sendMouseDelta(event.x, event.y) else movePointer(event.x, event.y)
                val button = when (event.actionButton) {
                    MotionEvent.BUTTON_SECONDARY -> PointerGestures.BTN_RIGHT
                    MotionEvent.BUTTON_TERTIARY -> PointerGestures.BTN_MIDDLE
                    else -> PointerGestures.BTN_LEFT
                }
                sendSceneButton(button, event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS)
            }
            MotionEvent.ACTION_SCROLL -> {
                val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (v != 0f) sendSceneWheel(-Math.round(v))
            }
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP -> {}
            else -> return false
        }
        return true
    }

    private fun mouseAttached(): Boolean = InputDevice.getDeviceIds().any { id ->
        val d = InputDevice.getDevice(id)
        d != null && !d.isVirtual && d.supportsSource(InputDevice.SOURCE_MOUSE)
    }

    private fun updatePointerCapture() {
        if (!::surfaceView.isInitialized) return
        val view = window.decorView
        val want = pointerLocked && resumed && hasWindowFocus() && !drawerOpen && !pcKeyboardOpen && !achievementsOpen && !pipUi && mouseAttached()
        if (want == view.hasPointerCapture()) return
        if (want) view.requestPointerCapture() else view.releasePointerCapture()
    }

    private fun onCapturedPointer(event: MotionEvent): Boolean {
        if (!pointerLocked || !event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)) return false
        notePress(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                var dx = event.x
                var dy = event.y
                for (i in 0 until event.historySize) {
                    dx += event.getHistoricalX(i)
                    dy += event.getHistoricalY(i)
                }
                sendSceneDelta(dx, dy)
            }
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                val button = when (event.actionButton) {
                    MotionEvent.BUTTON_SECONDARY -> PointerGestures.BTN_RIGHT
                    MotionEvent.BUTTON_TERTIARY -> PointerGestures.BTN_MIDDLE
                    else -> PointerGestures.BTN_LEFT
                }
                sendSceneButton(button, event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS)
            }
            MotionEvent.ACTION_SCROLL -> {
                val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (v != 0f) sendSceneWheel(-Math.round(v))
            }
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP -> {}
            else -> return false
        }
        return true
    }

    private fun logInputDevice(what: String, deviceId: Int) {
        val d = InputDevice.getDevice(deviceId) ?: return
        if (!PadBridge.isFromController(d)) return
        Log.i(TAG, String.format(java.util.Locale.ROOT, "controller %s: \"%s\" (%04x:%04x, id %d, sources 0x%x)",
            what, d.name, d.vendorId, d.productId, d.id, d.sources))
    }

    private fun steamTouch(): com.droiddeck.launcher.input.SteamTouchControls? {
        val device = com.droiddeck.launcher.input.SteamTouchDevice.current ?: return null
        steamTouchControls?.let { view ->
            if (view.device === device) return view
            view.releaseAll()
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            steamTouchControls = null
        }
        val root = onScreenControls?.parent as? android.view.ViewGroup ?: return null
        val view = com.droiddeck.launcher.input.SteamTouchControls(this, device,
            onMenu = { drawerOpen = true }, onKeyboard = ::togglePcKeyboard)
        view.visibility = View.GONE
        view.onOptions = { options ->
            steamTouchOptions = options
            applyTouchRouting()
        }
        view.onPaste = {
            sendGuestKey(29, true); sendGuestKey(47, true)
            sendGuestKey(47, false); sendGuestKey(29, false)
        }
        view.onSaved = {
            device.plugged = false
            uiHandler.postDelayed({ updateOnScreenControls() }, 700)
        }
        view.onStyle = { screenKeys?.refreshAppearance() }
        view.onTouchMiss = miss@{ event ->
            if (!gestureTouchStreamGate.accepts(GestureTouchStreamGate.Source.ON_SCREEN_CONTROLS, event)) return@miss
            event.offsetLocation(view.left - surfaceView.left.toFloat(), view.top - surfaceView.top.toFloat())
            onTouchEvent(event)
        }
        root.addView(view, root.indexOfChild(onScreenControls) + 1)
        steamTouchControls = view
        steamTouchAvailable = true
        return view
    }

    private fun updateOnScreenControls() {
        updateOnScreenPad()
        val c = onScreenControls
        glassesSlot = when {
            c == null || c.visibility != View.VISIBLE -> 0
            c.isButtonsOnly -> -1
            controllerSettings?.keyboardButton != false -> 2
            else -> 1
        }
    }

    private fun updateOnScreenPad() {
        val forced = File(Environment.getExternalStorageDirectory(), "Download/droiddeck-osc")
            .takeIf { it.isFile }
            ?.let { FileUtils.readString(it)?.trim()?.lowercase() }
            ?: SessionPrefs.oscMode(this)
        val controls = onScreenControls ?: return
        if (pipUi || unfoldedPanel != null || pcKeyboardOpen ||
            (SessionState.mode == SessionService.MODE_STEAM && !SessionPrefs.builtinPad(this))) {
            onScreenButtonsVisible = false
            controls.releaseAll()
            controls.visibility = View.GONE
            steamTouchControls?.let { it.releaseAll(); it.visibility = View.GONE }
            com.droiddeck.launcher.input.SteamTouchDevice.current?.plugged = false
            screenKeys?.followSteam = false
            applyTouchRouting()
            return
        }
        val buttonsOnly = forced == SessionPrefs.OSC_STEAM_QAM
        val wantsSteam = forced == SessionPrefs.OSC_STEAM_TOUCH && SessionState.mode == SessionService.MODE_STEAM
        val steam = if (wantsSteam) steamTouch() else null
        if (wantsSteam && steam == null && !isFinishing && !isDestroyed) {
            uiHandler.removeCallbacks(steamTouchRetry)
            uiHandler.postDelayed(steamTouchRetry, 1000)
        }
        if (steam == null) {
            steamTouchControls?.let { it.releaseAll(); it.visibility = View.GONE }
            com.droiddeck.launcher.input.SteamTouchDevice.current?.plugged = false
            applyTouchRouting()
        }
        controls.setButtonsOnly(buttonsOnly)
        val show = when (forced) {
            SessionPrefs.OSC_ALWAYS -> true
            SessionPrefs.OSC_STEAM_QAM -> true
            SessionPrefs.OSC_NEVER -> false
            SessionPrefs.OSC_STEAM_TOUCH -> SessionState.mode == SessionService.MODE_STEAM ||
                (!PadBridge.anyControllerConnected() && SessionState.mode != SessionService.MODE_DESKTOP)
            else -> !PadBridge.anyControllerConnected() && !SteamControllerBle.connected &&
                SessionState.mode != SessionService.MODE_DESKTOP
        }
        onScreenButtonsVisible = show
        val drawn = show && !keyboardCoversPad()
        if (steam != null) {
            screenKeys?.followSteam = drawn
            if (controls.visibility == View.VISIBLE) { controls.releaseAll(); controls.visibility = View.GONE }
            if (!drawn) steam.releaseAll()
            steam.visibility = if (drawn) View.VISIBLE else View.GONE
            com.droiddeck.launcher.input.SteamTouchDevice.current?.plugged = show
            applyTouchRouting()
            Log.i(TAG, "on-screen controls: Steam's touch controller " + if (drawn) "shown" else "hidden")
            return
        }
        screenKeys?.followSteam = false
        if (drawn == (controls.visibility == View.VISIBLE)) return
        if (!drawn) controls.releaseAll()
        controls.visibility = if (drawn) View.VISIBLE else View.GONE
        Log.i(TAG, "on-screen controls " + when {
            !drawn -> "hidden"
            buttonsOnly -> "Steam + QAM"
            else -> "full pad"
        })
    }

    private fun desktopTextInput() =
        SessionState.mode == SessionService.MODE_DESKTOP && SessionState.running && !pipUi

    private fun notePress(event: MotionEvent) {
        val touch = event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> {
                pressDownX = event.x
                pressDownY = event.y
                pressTravel = 0f
                pressMoved = false
                if (!touch) lastUserPressMs = android.os.SystemClock.uptimeMillis()
            }
            MotionEvent.ACTION_POINTER_DOWN -> pressMoved = true
            MotionEvent.ACTION_MOVE -> if (touch || event.buttonState != 0) {
                val moved = if (event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)) {
                    pressTravel += kotlin.math.hypot(event.x, event.y)
                    pressTravel
                } else {
                    kotlin.math.hypot(event.x - pressDownX, event.y - pressDownY)
                }
                if (moved >= android.view.ViewConfiguration.get(this).scaledTouchSlop * 2f) {
                    pressMoved = true
                    if (!touch) lastUserPressMs = 0L
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE -> {
                val quick = !touch || event.eventTime - event.downTime < android.view.ViewConfiguration.getLongPressTimeout()
                if (!pressMoved && quick) lastUserPressMs = android.os.SystemClock.uptimeMillis()
            }
        }
    }

    private fun onDesktopTextField(focused: Boolean) {
        val host = keyboard
        if (host != null && desktopTextInput()) {
            val tapped = android.os.SystemClock.uptimeMillis() - lastUserPressMs < TEXT_FIELD_TAP_MS
            if (focused && tapped && resumed && !pcKeyboardOpen &&
                resources.configuration.hardKeyboardHidden != android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO) host.show()
            else if (!focused && host.shown) host.hide()
        }
        updateDesktopKeyboardButton()
    }

    private fun updateDesktopKeyboardButton() {
        val button = desktopKeyboardButton ?: return
        val show = desktopTextInput() && AgentGuest.desktopTextField && !pcKeyboardOpen &&
            !(keyboard?.let { it.shown && (Build.VERSION.SDK_INT < 30 || it.imeShowing) } ?: false)
        val visibility = if (show) View.VISIBLE else View.GONE
        if (button.visibility == visibility) return
        if (show) button.applyColors()
        button.visibility = visibility
    }

    private fun keyboardCoversPad() =
        SessionState.mode == SessionService.MODE_STEAM && AgentGuest.steamKeyboard

    private fun sendGuestKey(code: Int, down: Boolean) {
        if (down && pipUi) return
        if (down) guestKeysDown.add(code) else guestKeysDown.remove(code)
        if (down || code !in gestureKeysDown) WaylandCompositor.nativeSendKey(code, if (down) 1 else 0)
    }

    // The frozen game cannot draw again, so the paused overlay shows a copy taken while the surface still holds its frame.
    private fun capturePausedFrame(manual: Boolean = false) {
        if (!SessionState.running || pipUi || isInPictureInPictureMode || glassesOutputActive) return
        if (SessionState.suspended && pausedFrame != null) return
        if ((suspendPolicy == SessionPrefs.SUSPEND_NEVER && !manual && !SessionState.manualPause) || !::surfaceView.isInitialized) return
        if (surfaceView.holder.surface?.isValid != true) return
        val width = surfaceView.width
        val height = surfaceView.height
        if (width <= 0 || height <= 0) return
        val scale = minOf(1f, PAUSED_FRAME_MAX_EDGE / maxOf(width, height).toFloat())
        val bitmap = try {
            android.graphics.Bitmap.createBitmap(
                maxOf(1, (width * scale).toInt()), maxOf(1, (height * scale).toInt()), android.graphics.Bitmap.Config.ARGB_8888,
            )
        } catch (_: OutOfMemoryError) {
            return
        }
        val surfaceAt = IntArray(2).also { surfaceView.getLocationInWindow(it) }
        val overlayAt = IntArray(2).also { sessionOverlay.getLocationInWindow(it) }
        val left = surfaceAt[0] - overlayAt[0]
        val top = surfaceAt[1] - overlayAt[1]
        val bounds = android.graphics.Rect(left, top, left + width, top + height)
        try {
            android.view.PixelCopy.request(surfaceView, bitmap, { result ->
                if (result == android.view.PixelCopy.SUCCESS && !isDestroyed) {
                    pausedFrame = com.droiddeck.launcher.ui.PausedFrame(bitmap.asImageBitmap(), bounds)
                    pausedFrameWanted = false
                } else {
                    bitmap.recycle()
                }
            }, uiHandler)
        } catch (_: IllegalArgumentException) {
            bitmap.recycle()
        }
    }

    private fun pauseNow() {
        if (!SessionState.running || SessionState.suspended) return
        pauseAnimated = true
        drawerOpen = false
        pausedFrame = null
        capturePausedFrame(manual = true)
        SessionService.pause(this)
    }

    // Sessions that froze from picture-in-picture, or before onPause could copy a frame, have none; the
    // compositor shows the frozen frame again once its output is back, so copy it from there.
    private fun requestPausedFrame() {
        if (pausedFrame != null) return
        pausedFrameWanted = true
        if (resumed && surfaceW > 0) capturePausedFrame()
    }

    private fun pipBounds(): android.graphics.Rect {
        val rect = android.graphics.Rect()
        if (!::surfaceView.isInitialized || !surfaceView.getGlobalVisibleRect(rect)) return rect
        val (w, h) = SessionState.outputSize
        if (w <= 0 || h <= 0) return rect
        val scale = minOf(rect.width().toFloat() / w, rect.height().toFloat() / h)
        val fittedW = (w * scale).toInt()
        val fittedH = (h * scale).toInt()
        rect.inset((rect.width() - fittedW) / 2, (rect.height() - fittedH) / 2)
        return rect
    }

    private fun showPipUi(show: Boolean) {
        pipUi = show
        if (show) {
            drawerOpen = false
            pcKeyboardOpen = false
            achievementsOpen = false
            pendingBackAction?.let(uiHandler::removeCallbacks)
            pendingBackAction = null
            releaseDrawerDirection()
            onScreenControls?.releaseAll()
            screenKeys?.releaseAll()
            releasePads()
            touchpad.cancel()
            scrollTouch.cancel()
            directTouchHold.cancel()
            twoFingerGestures.cancel()
            guestKeysDown.toList().forEach { sendGuestKey(it, false) }
            keyboard?.hide()
            sessionClipboard.stop()
            padMotion?.stop()
            cursorVisible = false
            closeSecondScreen(reset = false)
            onScreenControls?.visibility = View.GONE
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (CompositorHost.isStarted) {
                WaylandCompositor.nativeSendTouch(3, -1, 0, 0)
                listOf(PointerGestures.BTN_LEFT, PointerGestures.BTN_RIGHT, PointerGestures.BTN_MIDDLE).forEach {
                    WaylandCompositor.nativeSendSceneInput(3, it, 0)
                }
                WaylandCompositor.nativeSetScaleMode(SCALE_FIT, ALIGN_CENTER)
            }
        } else if (!isFinishing && !isDestroyed) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            updateOnScreenControls()
            if (resumed) { sessionClipboard.start(); updatePadMotion() }
            if (started) refreshSecondScreenDisplays()
            if (CompositorHost.isStarted) applyFrameGen()
        }
    }

    override fun onUserLeaveHint() {
        if (::pip.isInitialized) pip.leaving()
        super.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        if (::pip.isInitialized) pip.changed(inPip)
        if (!inPip && surfaceW > 0) resizeOutputForWindow(surfaceW, surfaceH)
        applyUnfoldedControls()
        updateGlassesScreen()
    }

    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        if (::pip.isInitialized && (requestCode >= 0 || intent.component?.packageName == packageName ||
                intent.action in setOf(Intent.ACTION_CHOOSER, Intent.ACTION_SEND, Intent.ACTION_OPEN_DOCUMENT,
                    Intent.ACTION_GET_CONTENT, "android.settings.PICTURE_IN_PICTURE_SETTINGS"))) pip.background()
        super.startActivityForResult(intent, requestCode, options)
    }


    override fun onStart() {
        super.onStart()
        if (runtimeRemovalBlocked) return
        started = true
        if (::hud.isInitialized) hud.setBackgrounded(false)
        refreshActivityDisplayId()
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        for (name in arrayOf("peak_refresh_rate", "min_refresh_rate")) {
            contentResolver.registerContentObserver(android.provider.Settings.System.getUriFor(name), false, refreshSettingObserver)
        }
        refreshSecondScreenDisplays()
        if (secondScreenMode != SecondScreenMode.NONE && secondScreenPresentation == null) showSecondScreen(secondScreenMode)
        else if (::loading.isInitialized && !loading.visible) openDualScreenControls()
        startedScreens++
        SessionService.setActivityVisible(this, true, sessionActivityDisplayId)
        Glasses.rescan()
        updateGlassesScreen()
        updateHandCamera()
    }

    override fun onStop() {
        if (runtimeRemovalBlocked) { super.onStop(); return }
        started = false
        if (::hud.isInitialized) hud.setBackgrounded(true)
        updateHandCamera()
        displayManager.unregisterDisplayListener(displayListener)
        contentResolver.unregisterContentObserver(refreshSettingObserver)
        closeSecondScreen(reset = false)
        startedScreens = (startedScreens - 1).coerceAtLeast(0)
        updateGlassesScreen()
        if (startedScreens == 0) SessionService.setActivityVisible(this, false, sessionActivityDisplayId)
        super.onStop()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.action == SessionService.ACTION_AGENT_START) {
            if (SessionState.running || SessionState.phase !in setOf(SessionPhase.IDLE, SessionPhase.FAILED)) {
                Log.w(TAG, "ignoring agent start while session phase is ${SessionState.phase}")
                return
            }
            setIntent(intent)
            recreate()
            return
        }
        if (this.intent.getBooleanExtra(EXTRA_RETURN_HOME, false)) intent.putExtra(EXTRA_RETURN_HOME, true)
        if (!intent.hasExtra(EXTRA_LINK_TASK)) intent.putExtra(EXTRA_LINK_TASK, this.intent.getIntExtra(EXTRA_LINK_TASK, -1))
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_STEAMVR, false) && SessionState.mode == SessionService.MODE_STEAM) {
            SessionState.steamVr = true
            steamVrAutoTried = false
        }
        if (intent.action == SessionService.ACTION_HOME_GUIDE) {
            handleHomeGuideIntent(intent)
        } else if (intent.action == SessionService.ACTION_RESUME) {
            intent.action = null
            SessionService.resume(this)
        }
    }

    override fun onResume() {
        super.onResume()
        if (refreshActivityDisplayId() && started) {
            SessionService.setActivityDisplay(this, sessionActivityDisplayId)
        }
        applyFollowOrientation(resources.configuration)
        if (runtimeRemovalBlocked) return
        if (!SessionState.suspended) pausedFrame = null
        com.droiddeck.launcher.ui.Motion.refresh(this)
        refreshHomeApp()
        if (::memoryHud.isInitialized && SessionState.running && SessionState.firstFrameSeen) memoryHud.refresh()
        if (intent?.action == SessionService.ACTION_RESUME) {
            intent.action = null
            SessionService.resume(this)
        }
        (getSystemService(INPUT_SERVICE) as? InputManager)
            ?.registerInputDeviceListener(deviceListener, Handler(Looper.getMainLooper()))
        if (SessionState.guestPid > 1) padBridge?.syncConnectedControllers()
        joyConVr?.refresh()
        onScreenControls?.reload()
        controllerSettings = ControllerPrefs.read(this)
        resumed = true
        updateDesktopKeyboardButton()
        com.droiddeck.launcher.input.HomeButtonShortcuts.attach(this,
            enabled = {
                resumed && window.decorView.hasWindowFocus() && !ended && !drawerOpen && !pcKeyboardOpen &&
                    !achievementsOpen && !gestureSettingsOpen && !pipUi && SessionState.running && !SessionState.suspended &&
                    SessionState.mode == SessionService.MODE_STEAM && padBridge != null
            },
            setPressed = { padBridge?.setHomeGuidePressed(it) },
            onCancel = { padBridge?.cancelHomeGuide() },
        )
        updatePointerCapture()
        if (!pipUi) sessionClipboard.start()
        updatePadMotion()
        readPrefs()
        refreshControls()
        if (::pip.isInitialized) pip.resumed()
        if (CompositorHost.isStarted) applyFrameGen()
        SessionPrefs.sessionPrefs(this).registerOnSharedPreferenceChangeListener(cardboardPrefListener)
    }

    override fun onPause() {
        com.droiddeck.launcher.input.HomeButtonShortcuts.detach(this)
        if (runtimeRemovalBlocked) { super.onPause(); return }
        backQamInput.cancel()
        capturePausedFrame()
        sessionClipboard.stop()
        if (::touchscreenGestures.isInitialized) touchscreenGestures.cancel()
        if (::touchpad.isInitialized) touchpad.cancel()
        if (::directTouchHold.isInitialized) directTouchHold.cancel()
        if (::twoFingerGestures.isInitialized) twoFingerGestures.cancel()
        uiHandler.removeCallbacks(clearRecognizedGesture)
        uiHandler.removeCallbacks(cursorResync)
        gestureSettingsOpen = false
        drawerOpen = false
        pcKeyboardOpen = false
        achievementsOpen = false
        (getSystemService(INPUT_SERVICE) as? InputManager)?.unregisterInputDeviceListener(deviceListener)
        releaseDrawerDirection()
        onScreenControls?.releaseAll()
        if (::scrollTouch.isInitialized) scrollTouch.cancel()
        if (::padMouse.isInitialized) padMouse.reset()
        resumed = false
        updatePointerCapture()
        updatePadMotion()
        keyboard?.takeIf { it.shown }?.hide()
        SessionPrefs.sessionPrefs(this).unregisterOnSharedPreferenceChangeListener(cardboardPrefListener)
        updateHeadTracker()
        super.onPause()
    }

    private fun steamVrAction(action: String) {
        if (action == "start" && com.droiddeck.launcher.session.QuestExperimental.isSupported()) {
            startActivity(android.content.Intent(this, QuestXrActivity::class.java))
            return
        }
        val request = ++steamVrRequestGeneration
        val session = SessionState.sessionId
        fun currentRequest() = request == steamVrRequestGeneration && session == SessionState.sessionId && !isDestroyed && !ended
        if (action == "start") {
            SessionState.steamVr = true
            steamVrAutoTried = true
        }
        if (action == "stop") {
            SessionState.steamVr = false
            Glasses.cancelSteamVrStart()
        }
        if (glasses.present) {
            // Optional stereo starts only after RUNNING is observed. READY can
            // arrive repeatedly while the launch worker is still starting VR.
            if (action == "install") steamVrStatus = SteamVr.Status.INSTALLING
            runSteamVrAction(action, ::currentRequest) {
                val ok = SteamVr.run(this, action)
                val now = SteamVr.status(this)
                uiHandler.post {
                    if (!currentRequest()) return@post
                    if (!ok && now != SteamVr.Status.RUNNING) Glasses.cancelSteamVrStart()
                    onSteamVrStatus(now)
                    if (!ok) Toast.makeText(this, getString(R.string.cardboard_steamvr_failed), Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        if (action == "start") {
            switchPictureForSteamVr()
            steamVrSeenRunning = false
        }
        if (action == "stop") restoreSteamVrPicture()
        if (action == "install") steamVrStatus = SteamVr.Status.INSTALLING
        runSteamVrAction(action, ::currentRequest) {
            val ok = SteamVr.run(this, action)
            val now = SteamVr.status(this)
            uiHandler.post {
                if (!currentRequest()) return@post
                if (!ok && now != SteamVr.Status.RUNNING) Glasses.cancelSteamVrStart()
                if (!ok && action == "start" && now != SteamVr.Status.RUNNING) restoreSteamVrPicture()
                onSteamVrStatus(now)
                if (!ok) Toast.makeText(this, getString(R.string.cardboard_steamvr_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun runSteamVrAction(action: String, currentRequest: () -> Boolean, run: () -> Unit) {
        if (action != "start") {
            steamVrWorker.execute(run)
            return
        }
        Glasses.prepareSteamVrStart {
            uiHandler.post {
                if (SessionState.steamVr && currentRequest()) steamVrWorker.execute {
                    if (SessionState.steamVr && currentRequest()) run()
                }
            }
        }
    }

    private fun switchPictureForSteamVr() {
        if (cardboard.source != Cardboard.SOURCE_SBS) {
            SessionPrefs.setCardboardSourceBeforeSteamVr(this, cardboard.source)
            val c = cardboard.copy(source = Cardboard.SOURCE_SBS)
            SessionPrefs.setCardboard(this, c)
            applyCardboard(c)
        }
        steamVrSwitchedPicture = SessionPrefs.cardboardSourceBeforeSteamVr(this) >= 0
    }

    private fun usesSpatialControllers() =
        GlassesRendering.usesGlasses(glasses, glassesScreen != null, steamVrStatus == SteamVr.Status.RUNNING) &&
            glasses.usesSpatialControllers(glassesSettings)

    private fun updateJoyConTracking() {
        joyConVr?.setActive(steamVrStatus == SteamVr.Status.RUNNING &&
            (ControllerPrefs.joyConVr(this) ||
                (usesSpatialControllers() && glassesSettings.joyConTracking)))
    }

    private fun updateHandCamera() {
        val joyCons = joyConVr?.holding == true
        val bare = !joyCons && !controllerConnected()
        val want = started && steamVrStatus == SteamVr.Status.RUNNING && (joyCons || bare) &&
            !usesSpatialControllers() &&
            com.droiddeck.launcher.handtracking.HandTracking.wanted(this)
        handCamera?.bareHands = bare
        if (want == (handCamera != null)) return
        if (want) {
            handCamera = com.droiddeck.launcher.handtracking.HandCamera(this,
                File(filesDir, ".wayland-rt/${com.droiddeck.launcher.handtracking.HandCameraShm.FILE}"), preview = false)
                .also {
                    it.bareHands = bare
                    it.start(com.droiddeck.launcher.handtracking.HandTracking.settings.value.side)
                }
        } else {
            handCamera?.stop()
            handCamera = null
        }
    }

    private fun controllerConnected(): Boolean = InputDevice.getDeviceIds().any { id ->
        joyConVr?.owns(id) != true && com.droiddeck.launcher.input.PadBridge.isFromController(InputDevice.getDevice(id))
    }

    private fun onSteamVrStatus(now: SteamVr.Status) {
        if (ended || isDestroyed) return
        val was = steamVrStatus
        steamVrStatus = now
        updateJoyConTracking()
        updateHandCamera()
        autoStartSteamVr(now)
        if ((was == SteamVr.Status.RUNNING) != (now == SteamVr.Status.RUNNING)) applyCardboard(cardboard)
        if (now != SteamVr.Status.UNAVAILABLE) Glasses.setVrActive(now == SteamVr.Status.RUNNING)
        if (glasses.present) onGlassesSteamVr(now)
        if (GlassesRendering.usesGlasses(glasses, glassesScreen != null, now == SteamVr.Status.RUNNING)) {
            return
        }
        if (now == SteamVr.Status.RUNNING && cardboard.enabled && !steamVrSwitchedPicture &&
            cardboard.source != Cardboard.SOURCE_SBS) {
            switchPictureForSteamVr()
            steamVrSeenRunning = true
        }
        if (!steamVrSwitchedPicture) return
        if (now == SteamVr.Status.RUNNING) steamVrSeenRunning = true
        else if (steamVrSeenRunning && now != SteamVr.Status.UNAVAILABLE) restoreSteamVrPicture()
    }

    private fun restoreSteamVrPicture() {
        val before = SessionPrefs.cardboardSourceBeforeSteamVr(this)
        SessionPrefs.setCardboardSourceBeforeSteamVr(this, -1)
        steamVrSwitchedPicture = false
        steamVrSeenRunning = false
        if (before < 0 || cardboard.source == before) return
        val c = cardboard.copy(source = before)
        SessionPrefs.setCardboard(this, c)
        applyCardboard(c)
    }

    private fun applyCardboard(c: Cardboard) {
        val was = activeCardboard
        cardboard = c
        val e = effectiveCardboard(c)
        activeCardboard = e
        if (CompositorHost.isStarted) pushCardboard(e)
        if (e.enabled && !was.enabled) headTracker?.recenter()
        updateHeadTracker()
    }

    private fun pushCardboard(c: Cardboard) {
        val fov = when {
            c.viewer != Cardboard.Viewer.VITURE.id -> 0f
            c.source == Cardboard.SOURCE_SBS -> glassesSettings.vrFovDeg.toFloat()
            else -> glasses.verticalFovDeg
        }
        panelSizeMm().let { (w, h) -> c.push(w, h, fov, refreshHz()) }
    }

    private fun effectiveCardboard(c: Cardboard): Cardboard = GlassesRendering.cardboard(
        base = c,
        settings = glassesSettings,
        state = glasses,
        outputActive = glassesScreen != null,
        steamVrRunning = steamVrStatus == SteamVr.Status.RUNNING,
    )

    private fun updateHeadTracker() {
        val onGlasses = GlassesRendering.usesGlasses(glasses, glassesScreen != null,
            steamVrStatus == SteamVr.Status.RUNNING)
        SpatialTracking.setOutputUsesGlasses(onGlasses && !ended)
        val c = activeCardboard
        val centerView = onGlasses && c.source == Cardboard.SOURCE_SCREEN
        val want = !ended && (resumed || (glassesScreen != null && started)) && c.enabled && c.headTracking
        val current = headTracker
        if (current != null && (!want || current.usesGlasses != onGlasses || current.centerView != centerView)) {
            current.stop()
            headTracker = null
        }
        if (want) {
            val t = headTracker ?: HeadTracker(this, glasses = onGlasses, centerView = centerView) {
                @Suppress("DEPRECATION")
                (if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay)?.rotation ?: android.view.Surface.ROTATION_0
            }.also { headTracker = it }
            t.start()
        }
        headTracking = headTracker != null
    }

    private fun updateTrackingRecovery() {
        val onGlasses = GlassesRendering.usesGlasses(glasses, glassesScreen != null,
            steamVrStatus == SteamVr.Status.RUNNING)
        val spatialView = onGlasses && SpatialTracking.selected && glassesSettings.positionalTracking &&
            (SessionState.steamVr || steamVrStatus == SteamVr.Status.RUNNING ||
                (glassesOutputActive && glassesSettings.headTracking && glassesSettings.picture3d == com.droiddeck.launcher.glasses.GlassesSettings.PICTURE_SCREEN))
        val active = started && !ended && SessionState.running && !loading.visible && !pipUi && spatialView
        if (active) SpatialTracking.refreshStatus()
        trackingRecovery = TrackingRecovery.forSession(active, glasses.canControl, SpatialTracking.status.value)
        glassesScreen?.trackingRecovery?.update(trackingRecovery, glasses.sbs)
    }

    private fun outputHolder(): SurfaceHolder = glassesScreen?.holder ?: surfaceView.holder

    private fun onOutputAttached(holder: SurfaceHolder) {
        if (holder !== outputHolder()) return
        glassesOutputActive = glassesScreen?.holder === holder
        // Keep the phone's layout for touch/controller coordinates, but remove
        // its SurfaceView so it cannot retain the last game frame under the hint.
        surfaceView.visibility = if (glassesOutputActive) View.INVISIBLE else View.VISIBLE
        Log.i(TAG, "compositor output attached: " +
            if (glassesOutputActive) "glasses display ${glassesScreen?.display?.displayId}" else "phone")
        if (pausedFrameWanted) uiHandler.postDelayed({
            if (pausedFrameWanted && pausedFrame == null && resumed) capturePausedFrame()
        }, PAUSED_FRAME_REDRAW_MS)
    }

    private fun restorePhoneOutput(screen: GlassesScreen) {
        if (glassesScreen !== screen) return
        glassesScreen = null
        glassesOutputActive = false
        surfaceW = 0
        surfaceH = 0
        CompositorHost.detach(screen.holder.surface)
        surfaceView.visibility = View.VISIBLE
        applyOutputPacing()
        if (surfaceView.holder.surface?.isValid == true) surfaceCreated(surfaceView.holder)
    }

    private fun glassesOutputSize(): Pair<Int, Int>? {
        if (glassesScreen == null || glasses.width <= 0 || glasses.height <= 0) return null
        val panel = (if (glasses.sbs) glasses.width / 2 else glasses.width) to glasses.height
        val mode = displayMode()
        val display = com.droiddeck.launcher.session.SessionDisplay
        val choice = SessionPrefs.resolutionChoice(this, mode, panel)
        val size = display.resolveChoice(panel, choice)
        val fixed = choice != display.MATCH_SCREEN && choice != display.FOLLOW_SCREEN && display.presetHeight(choice) == null
        return if (fixed) display.resolve(panel, minOf(size.first, size.second), SessionPrefs.SHAPE_AUTO) else size
    }

    private fun onGlassesChanged() {
        val g = glasses
        val s = glassesSettings
        val key = listOf(g.present, g.usable, g.displayId, g.sbs, g.sbs90, g.supports30Hz, g.switching3d, g.canControl, g.width, g.height, g.hz, g.resolutions, g.vrActive, s)
        if (key == glassesKey) return
        glassesKey = key
        updateGlassesScreen()
        applyCardboard(cardboard)
        if (!g.switching3d) {
            if (glassesPresent && !g.present) onGlassesUnplugged()
            glassesPresent = g.present
        }
    }

    private fun onGlassesUnplugged() {
        val hadVr = steamVrAutoTried
        steamVrAutoTried = false
        glasses3dForVr = false
        if (hadVr && !cardboard.enabled) steamVrAction("stop")
    }

    private fun autoStartSteamVr(now: SteamVr.Status) {
        if (now == SteamVr.Status.RUNNING) steamVrAutoTried = true
        val action = SteamVr.autoAction(now, SessionState.steamVr, loading.visible,
            SessionState.mode == SessionService.MODE_STEAM, steamVrAutoTried, steamVrInstallTried) ?: return
        if (action == "start") steamVrAutoTried = true else steamVrInstallTried = true
        Log.i(TAG, "requested SteamVR $action (glasses=${glasses.model}, output=$glassesOutputActive, control=${glasses.canControl})")
        steamVrAction(action)
    }

    private fun updateGlassesScreen() {
        if (!::surfaceView.isInitialized) return
        val g = glasses
        val want = started && !ended && !isInPictureInPictureMode && !runtimeRemovalBlocked && g.usable && glassesSettings.gameOnGlasses
        val target = if (want) Glasses.glassesDisplay()?.takeIf { it.isValid } else null
        val current = glassesScreen
        if (current != null && current.display.displayId == target?.displayId) {
            current.prefer(Glasses.outputHz(), Glasses.outputResolution())
            applyOutputPacing()
            return
        }
        if (current != null) {
            restorePhoneOutput(current)
            runCatching { current.dismiss() }
        }
        if (target != null) {
            val screen = GlassesScreen(this, target, Glasses.outputHz(), this, Glasses.outputResolution())
            // show() can create the surface synchronously. Select its holder
            // first, otherwise surfaceCreated() rejects the glasses callback.
            glassesScreen = screen
            applyOutputPacing()
            surfaceW = 0
            surfaceH = 0
            screen.setOnDismissListener {
                if (glassesScreen === screen) {
                    restorePhoneOutput(screen)
                    refreshSecondScreenDisplays()
                    applyCardboard(cardboard)
                }
            }
            try {
                screen.show()
            } catch (e: Exception) {
                restorePhoneOutput(screen)
                runCatching { screen.dismiss() }
                Log.w(TAG, "could not show the game on ${target.name}", e)
                return
            }
            Log.i(TAG, "requested game output on ${g.model} (display ${target.displayId})")
        }
        applyOutputPacing()
        refreshSecondScreenDisplays()
        applyCardboard(cardboard)
    }

    private fun onGlassesSteamVr(now: SteamVr.Status) {
        val g = glasses
        if (now == SteamVr.Status.RUNNING) {
            if (glassesSettings.auto3d && g.can3d && !g.sbs && !glasses3dForVr) {
                glasses3dForVr = true
                Glasses.set3dForVr(true)
            }
        } else if (glasses3dForVr && now != SteamVr.Status.UNAVAILABLE) {
            glasses3dForVr = false
            Glasses.set3dForVr(false)
        }
    }

    private fun panelSizeMm(): Pair<Float, Float> {
        val dm = resources.displayMetrics
        val landscape = dm.widthPixels >= dm.heightPixels
        val longPx = maxOf(dm.widthPixels, dm.heightPixels).toFloat()
        val shortPx = minOf(dm.widthPixels, dm.heightPixels).toFloat()
        val longDpi = if (landscape) dm.xdpi else dm.ydpi
        val shortDpi = if (landscape) dm.ydpi else dm.xdpi
        return (if (longDpi > 0f) longPx / longDpi * 25.4f else 0f) to (if (shortDpi > 0f) shortPx / shortDpi * 25.4f else 0f)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val decor = window.decorView
        decor.requestUnbufferedDispatch(unbufferedSources())
        decor.viewTreeObserver.addOnGlobalFocusChangeListener { _, _ -> decor.post { decor.requestUnbufferedDispatch(unbufferedSources()) } }
    }

    private fun unbufferedSources() =
        if (lowLatency) UNBUFFERED_SOURCES or InputDevice.SOURCE_CLASS_POINTER else UNBUFFERED_SOURCES

    private fun applyLowLatency() {
        com.droiddeck.launcher.input.FakeInputWriter.setLowLatency(lowLatency)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) window.decorView.requestUnbufferedDispatch(unbufferedSources())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) com.droiddeck.launcher.input.HomeButtonShortcuts.cancel(this)
        if (!hasFocus) backQamInput.cancel()
        if (!hasFocus && ::padMouse.isInitialized) padMouse.reset()
        if (runtimeRemovalBlocked) return
        if (!hasFocus) {
            if (::touchscreenGestures.isInitialized) touchscreenGestures.cancel()
            if (::touchpad.isInitialized) touchpad.cancel()
        }
        if (hasFocus && !pipUi) {
            goFullscreen()
            refreshClipboard()
        }
        updatePointerCapture()
    }

    fun refreshClipboard() {
        if (resumed) sessionClipboard.refresh()
    }

    private fun goFullscreen() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private val endListener: (Int) -> Unit = { status -> onSessionEnded(status) }
    private val firstFrameListener = Runnable {
        SessionEvents.firstFrame()
        if (SessionState.mode == SessionService.MODE_STEAM) SteamRepair.clientShown(this)
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            loading.visible = false
            hud.start()
            memoryHud.start()
            if (started) openDualScreenControls()
        }
    }

    override fun onDestroy() {
        com.droiddeck.launcher.input.HomeButtonShortcuts.detach(this)
        backQamInput.cancel()
        steamVrRecovery.close()
        uiHandler.removeCallbacks(steamTouchRetry)
        uiHandler.removeCallbacks(cursorResync)
        releaseEvdevMouse()
        if (runtimeRemovalBlocked) { super.onDestroy(); return }
        watching = false
        releaseDrawerDirection()
        pendingBackAction?.let(uiHandler::removeCallbacks)
        pendingBackAction = null
        if (::pip.isInitialized) pip.destroyed()
        closeSecondScreen(reset = false)
        glassesScope.cancel()
        glassesScreen?.let { screen -> glassesScreen = null; runCatching { screen.dismiss() } }
        headTracker?.stop()
        if (::hud.isInitialized) hud.stop()
        if (::memoryHud.isInitialized) memoryHud.stop()
        joyConVr?.close()
        joyConVr = null
        handCamera?.stop()
        handCamera = null
        padBridge?.stop()
        steamController?.stop()
        if (SessionState.padBridge === padBridge) SessionState.padBridge = null
        padMotion?.stop()
        if (SessionState.deckPadListener === deckPadListener) SessionState.deckPadListener = null
        if (SessionState.endListener === endListener) SessionState.endListener = null
        WaylandCompositor.clearFirstFrameListener(firstFrameListener)
        WaylandCompositor.clearPointerLockListener(pointerLockListener)
        super.onDestroy()
    }

    private fun finishFlooded() {
        if (isFinishing) return
        val signal = com.droiddeck.launcher.ui.Themes.current(this).signal
        com.droiddeck.launcher.ui.QuitFlood.mark(signal.toArgb())
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(signal.toArgb()))
        quitFlooded = true
        SessionService.stop(this)
        finish()
    }

    override fun finish() {
        if (isFinishing) return
        if (!quitFlooded && !closeAtOnce && com.droiddeck.launcher.ui.Motion.scale != 0f &&
            lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            if (!leaving) {
                leaving = true
                uiHandler.postDelayed({ completeLeave() }, LEAVE_TIMEOUT_MS)
            }
            return
        }
        if (!closeAtOnce && intent.getBooleanExtra(EXTRA_RETURN_HOME, false)) {
            com.droiddeck.launcher.ui.QuitFlood.take()
            com.droiddeck.launcher.ui.LaunchOrigin.flooding = null
            closeLinkTasks()
            if (!isTaskRoot) moveTaskToBack(true)
            super.finishAndRemoveTask()
        } else super.finish()
        if (quitFlooded) overridePendingTransition(0, 0)
        else overridePendingTransition(R.anim.session_hold, R.anim.session_sink)
    }

    private fun closeLinkTasks() {
        val linkTask = intent.getIntExtra(EXTRA_LINK_TASK, -1)
        if (linkTask == -1) return
        val tasks = runCatching { getSystemService(android.app.ActivityManager::class.java)?.appTasks }.getOrNull().orEmpty()
        for (task in tasks) {
            runCatching {
                val info = task.taskInfo
                @Suppress("DEPRECATION")
                val id = if (android.os.Build.VERSION.SDK_INT >= 29) info.taskId else info.persistentId
                val base = info.baseIntent
                if (id == linkTask && base.component?.className == FrontEndActivity::class.java.name &&
                    com.droiddeck.launcher.frontend.GameLaunchIntent.accepts(base.action)) task.finishAndRemoveTask()
            }
        }
    }

    private fun completeLeave() {
        if (isFinishing) return
        val signal = com.droiddeck.launcher.ui.Themes.current(this).signal
        com.droiddeck.launcher.ui.QuitFlood.mark(signal.toArgb())
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(signal.toArgb()))
        quitFlooded = true
        finish()
    }

    companion object {
        const val EXTRA_RETURN_HOME = "returnHome"
        const val EXTRA_LINK_TASK = "linkTask"
        const val EXTRA_STEAMVR = "steamVr"
        const val EXTRA_REPAIR_RUNTIME = "repairRuntime"
        private const val LEAVE_TIMEOUT_MS = 1_500L
        private const val TEXT_FIELD_TAP_MS = 1_000L
        private const val PAUSED_FRAME_MAX_EDGE = 1920f
        private const val PAUSED_FRAME_REDRAW_MS = 150L
        private const val TAG = "SessionActivity"
        private var startedScreens = 0
        @Volatile private var activeInstallWorker: Any? = null
        private const val FEX_MISSING = "droiddeck-fex: x86 Linux programs need"
        private const val UNBUFFERED_SOURCES = InputDevice.SOURCE_CLASS_JOYSTICK or InputDevice.SOURCE_CLASS_TRACKBALL or InputDevice.SOURCE_CLASS_POSITION
        private const val CURSOR_PAD_HOLD_MS = 1200L
        private const val CURSOR_RESYNC_IDLE_TICKS = 33
        private const val PAD_MOUSE_CHECK_MS = 500L
        private const val PAD_SCROLL_POINTER_X = 0.985f
        private const val PAD_SCROLL_HEIGHTS_PER_S = 3f
        private const val AXIS_UNITS_PER_PIXEL = 3f
        private const val KEY_LEFTCTRL = 29
        private const val DRAWER_HAT_THRESHOLD = 0.5f
        private const val DRAWER_STICK_ENTER_THRESHOLD = 0.55f
        private const val DRAWER_STICK_EXIT_THRESHOLD = 0.35f
        private const val DRAWER_DIRECTION_MARGIN = 1.15f
        private const val DRAWER_ACTIVE_DIRECTION_MARGIN = 0.85f
        private const val DRAWER_SLOW_REPEAT_FLOOR_MS = 240L
        private const val DRAWER_FAST_REPEAT_INTERVAL_MS = 90L
        private const val DRAWER_REPEAT_ACCELERATION_MS = 1_400L
        private val steamVrWorker = java.util.concurrent.ThreadPoolExecutor(0, 1, 30, java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.LinkedBlockingQueue()) { Thread(it, "steamvr") }
        private const val SCALE_FIT = 1
        private const val ALIGN_CENTER = 0
        private const val ALIGN_PICTURE_TOP = 3
        private const val ALIGN_PICTURE_BOTTOM = 4
        private val RUNTIME_LINES = PackageLines(
            R.string.session_runtime_downloading, R.string.session_runtime_downloading_mb,
            R.string.session_runtime_checking, R.string.session_runtime_unpacking,
            LoadingState.Topic.RUNTIME, checkingReadable = true,
        )
        private val DESKTOP_LINES = PackageLines(
            R.string.session_desktop_downloading, R.string.session_desktop_downloading_mb,
            R.string.session_desktop_checking, R.string.session_desktop_unpacking,
            LoadingState.Topic.DESKTOP, checkingReadable = false,
        )
        private val PROTON_LINES = PackageLines(
            R.string.session_proton_downloading, R.string.session_proton_downloading_mb,
            R.string.session_proton_checking, R.string.session_proton_unpacking,
            LoadingState.Topic.STEAM, checkingReadable = false,
        )
    }
}

private class AchievementsInFront(val game: StoreAchievements.Game, val title: String, val progress: Pair<Int, Int>?)
