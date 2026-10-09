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
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.core.TextureFiltering
import com.droiddeck.launcher.files.InAppFilePicker
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.gpu.FrameGen
import com.droiddeck.launcher.gpu.ScreenEffectLooks
import com.droiddeck.launcher.gpu.ScreenEffects
import com.droiddeck.launcher.gpu.Lossless
import com.droiddeck.launcher.gpu.TurnipDriver
import com.droiddeck.launcher.input.EvdevKeys
import com.droiddeck.launcher.input.InputSpace
import com.droiddeck.launcher.input.KeyboardHost
import com.droiddeck.launcher.input.ControllerPrefs
import com.droiddeck.launcher.input.SessionClipboard
import com.droiddeck.launcher.input.OnScreenControls
import com.droiddeck.launcher.input.PadBridge
import com.droiddeck.launcher.input.PointerGestures
import com.droiddeck.launcher.input.SecondScreenDisplay
import com.droiddeck.launcher.input.SecondScreenDisplays
import com.droiddeck.launcher.input.SecondScreenMode
import com.droiddeck.launcher.input.TouchpadGestures
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.LoadingState
import com.droiddeck.launcher.session.PerfHud
import com.droiddeck.launcher.session.PerfMode
import com.droiddeck.launcher.session.SteamRepair
import com.droiddeck.launcher.session.GameEnvironmentStore
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionEvents
import com.droiddeck.launcher.session.SessionArtifacts
import com.droiddeck.launcher.session.SessionLogShare
import com.droiddeck.launcher.session.SessionPhase
import com.droiddeck.launcher.session.SessionPaths
import com.droiddeck.launcher.wayland.HdrSupport
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
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
import com.droiddeck.launcher.ui.SessionDrawerPage
import com.droiddeck.launcher.ui.sessionDrawerPages
import com.droiddeck.launcher.ui.step
import kotlin.math.abs

/**
 * The session's screen: our Wayland compositor presenting onto this activity's Surface, and the
 * input that reaches it. The session itself - gamescope, the Steam client, audio - belongs to
 * [SessionService] and keeps running when this activity does not exist, which is what lets the
 * user leave Big Picture for another app and come back to it still signed in and still
 * downloading.
 *
 * Three layers: the SurfaceView the compositor draws into, the on-screen pad (a canvas View,
 * since it is input rather than a menu), and one Compose layer on top for everything else -
 * the HUD line, the loading overlay, the drawer and its dialogs.
 */
class SessionActivity : ComponentActivity(), SurfaceHolder.Callback {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        com.droiddeck.launcher.core.AppLanguage.applyTo(this, newBase)
    }

    private lateinit var pip: com.droiddeck.launcher.session.SessionPipController
    private var pipUi by mutableStateOf(false)
    private var pipAutoEnter by mutableStateOf(false)
    private val guestKeysDown = mutableSetOf<Int>()
    private lateinit var surfaceView: SurfaceView
    private lateinit var sessionOverlay: ComposeView
    private lateinit var loading: LoadingState
    private lateinit var hud: PerfHud
    private var padBridge: PadBridge? = null
    /** The handheld's gyro and accelerometer, fed to the Deck controller while the session shows. */
    private var padMotion: com.droiddeck.launcher.input.PadMotion? = null
    /** Between onResume and onPause. */
    private var resumed = false
    private val deckPadListener: () -> Unit = { uiHandler.post { updatePadMotion(); openDeckRing("deck pad changed") } }

    /**
     * A Deck's controller is there from the moment it boots, and the client looks for it once, as
     * it starts. The ring behind the Deck (libfakeinput's /dev/hidraw16) opens on the pad's first
     * input otherwise - on a phone with nothing attached, the first touch of the on-screen
     * controls, seconds after the client had looked, found no controller and never set the touch
     * mode, so its interface took a finger as a mouse. Opened as soon as the session settles on a
     * Deck instead.
     */
    private fun openDeckRing(why: String) {
        val deck = SessionState.deckPad
        val opened = if (deck) padBridge?.start() else null
        Log.i(TAG, "deck ring ($why): deck pad $deck" + when (opened) {
            null -> ", left for first input"
            true -> ", ring open"
            false -> ", ring NOT open"
        })
    }

    /** Motion is read while the session shows and only if the pad is a Deck controller - which
     *  the service can decide after this activity has resumed (SessionState.deckPadListener). */
    private fun updatePadMotion() {
        if (resumed && !pipUi && SessionState.deckPad) padMotion?.start() else padMotion?.stop()
    }
    private var onScreenControls: OnScreenControls? = null
    private var keyboard: KeyboardHost? = null
    private var controllerSettings by mutableStateOf<ControllerPrefs.Settings?>(null)
    private val sessionClipboard by lazy {
        SessionClipboard(this) {
            window.decorView.hasWindowFocus() || secondScreenPresentation?.window?.decorView?.hasWindowFocus() == true
        }
    }
    private var watching = true
    private lateinit var touchpad: TouchpadGestures
    private var touchMode by mutableStateOf(SessionPrefs.TOUCH_AUTO)
    private var cursorPos by mutableStateOf(androidx.compose.ui.geometry.Offset(-100f, -100f))
    private var cursorVisible by mutableStateOf(false)
    /** The blue the front end flooded to on the way in; the loading screen opens on it (LaunchFlood). */
    private var floodColor by mutableStateOf<Int?>(null)
    private val cursorHide = Runnable { cursorVisible = false }
    /** When the pad or the on-screen controls were last used (uptimeMillis); see [showCursor]. */
    private var lastPadInputMs = 0L
    // The program's own pointer, from wl_pointer.set_cursor (see syncClientCursor): its image in
    // session pixels, hotspot, and whether it asked for no pointer at all.
    private val cursorBuf = IntArray(WaylandCompositor.CURSOR_BUF_INTS)
    private var cursorSerial = 0
    private var clientHidesCursor = false
    /**
     * A program holds a pointer lock (zwp_locked_pointer_v1: a mouse-look game, through gamescope).
     * While it does, a physical mouse is captured and its raw deltas go to the program as relative
     * motion. Without capture the deltas are worked out from Android's pointer position, which stops
     * at the screen edge, and so did the camera (#153).
     */
    private var pointerLocked = false
    private val pointerLockListener = WaylandCompositor.PointerLockListener { locked, _, _ ->
        uiHandler.post {
            if (pointerLocked == locked) return@post
            pointerLocked = locked
            Log.i(TAG, "pointer lock " + if (locked) "taken by a program" else "released")
            updatePointerCapture()
        }
    }
    private var cursorImage by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    private var cursorHotX by mutableStateOf(0)
    private var cursorHotY by mutableStateOf(0)
    private var cursorImageScale by mutableStateOf(1f)
    /** labwc changes the shape a moment after the move that reached an edge: look again then. */
    private val cursorResync = Runnable { if (syncClientCursor() && clientHidesCursor) cursorVisible = false }
    private val uiHandler = Handler(Looper.getMainLooper())
    private var pendingBackAction: Runnable? = null
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

    // Compose reads these; the activity writes them.
    private var drawerOpen by mutableStateOf(false)
    /** A stop growing out of the dialog's Stop (overlay px); the session ends once it covers the screen. */
    private var quitFrom by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    /** Left behind that flood: the front end opens on its blue, so no sink of our own. */
    private var quitFlooded = false
    /** Leaving behind a flood spreading out of the throbber (FloodSpread); see finish(). */
    private var leaving by mutableStateOf(false)
    /** Close without the leaving flood: a retry opens a fresh session at once. */
    private var closeAtOnce = false
    /** The drawer's Components tab: the Protons as last read (ComponentsManager). */
    private var drawerComponents by mutableStateOf<ComponentsManager.Snapshot?>(null)
    private var drawerPage by mutableStateOf(SessionDrawerPage.CONTROLLER)
    private var drawerControllerActive by mutableStateOf(false)
    private var backActionsInverted by mutableStateOf(false)
    /** The on-screen PC keyboard (ui/PcKeyboard): real key presses, Esc and F1 included. */
    private var pcKeyboardOpen by mutableStateOf(false)
    private var hudOn by mutableStateOf(true)
    private var upscaler by mutableStateOf(0)
    private var upscaleSharpness by mutableStateOf(75)
    private var effects by mutableStateOf(ScreenEffects.OFF)
    private var textureAnisotropy by mutableStateOf(0)
    private var textureLodBias by mutableStateOf(TextureFiltering.LOD_BIAS_OFF)
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
    /** Between onStart and onStop: while stopped the second screen stays closed, its choice kept. */
    private var started = false
    private lateinit var displayManager: DisplayManager
    private var secondScreenPresentation: SecondScreenPresentation? = null
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refreshSecondScreenDisplays()
        override fun onDisplayRemoved(displayId: Int) = refreshSecondScreenDisplays()
        override fun onDisplayChanged(displayId: Int) {
            followPanelRefresh(displayId)
            refreshSecondScreenDisplays()
        }
    }

    /**
     * The panel's rate, followed for the rest of the session. It is read once at start, and a
     * panel that drops from 120 to 60 Hz later (battery saver, heat, a vendor overriding the mode
     * asked for) left the compositor telling gamescope every frame took 8.3 ms, so the client and
     * its games paced against a vblank that no longer came. (WinNative 53836ca9.)
     */
    private fun followPanelRefresh(displayId: Int) {
        if (!CompositorHost.isStarted) return
        val display = (if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay) ?: return
        if (display.displayId != displayId) return
        val hz = display.refreshRate
        if (hz > 1f) WaylandCompositor.nativeSetOutputRefreshRate(hz)
    }
    private var isHomeApp by mutableStateOf(false)
    private var androidApps by mutableStateOf<List<HomeApp.LaunchableApp>>(emptyList())

    /**
     * Shows the on-screen pad when nothing is plugged in and takes it away the moment something
     * is - a user with a controller in their hands should not be looking at buttons they cannot
     * press, and a user without one must not be left with no way to answer Big Picture.
     */
    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            logInputDevice("connected", deviceId)
            updateOnScreenControls()
            updatePointerCapture()
        }
        override fun onInputDeviceRemoved(deviceId: Int) {
            Log.i(TAG, "input device $deviceId disconnected")
            updateOnScreenControls()
            updatePointerCapture()
        }
        override fun onInputDeviceChanged(deviceId: Int) = updateOnScreenControls()
    }

    private var runtimeRemovalBlocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (com.droiddeck.launcher.runtime.LinuxRuntimeInstaller.isRemoving()) {
            runtimeRemovalBlocked = true
            android.widget.Toast.makeText(this, R.string.session_wait_removal, android.widget.Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (!SessionState.running && SessionState.phase in setOf(SessionPhase.IDLE, SessionPhase.FAILED)) {
            val mode = intent.getStringExtra(SessionService.EXTRA_MODE) ?: SessionService.MODE_STEAM
            // A Flatpak app or AppImage is named by its arguments, which the name lookup reads here.
            SessionState.programArgs = intent.getStringArrayExtra(SessionService.EXTRA_PROGRAM_ARGS)?.toList().orEmpty()
            SessionEvents.begin(this, mode, SessionPaths.label(mode, intent.getStringExtra(SessionService.EXTRA_PROGRAM)))
        }
        if (!SessionState.running && SessionState.phase in setOf(SessionPhase.IDLE, SessionPhase.FAILED)) {
            SessionState.program = intent.getStringExtra(SessionService.EXTRA_PROGRAM)
            SessionState.programArgs = intent.getStringArrayExtra(SessionService.EXTRA_PROGRAM_ARGS)?.toList().orEmpty()
            SessionState.steamUi = intent.getStringExtra(SessionService.EXTRA_STEAM_UI)
            SessionState.steamUrl = intent.getStringExtra(SessionService.EXTRA_STEAM_URL)
        }
        if (intent.action == SessionService.ACTION_AGENT_START) {
            SessionEvents.record("agent.start_requested", mapOf("mode" to SessionState.mode))
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Game-tier power policy for the whole session: the panel's fastest mode (one the frame cap
        // divides, when there is a cap), and the OS told it is in gameplay. Logged so a slow device
        // says why. The cap is the session's: an activity re-created mid-session keeps it.
        if (!SessionState.running) {
            SessionState.fpsLimit = SessionPrefs.fpsLimit(
                this, SessionPrefs.prefMode(intent.getStringExtra(SessionService.EXTRA_MODE) ?: SessionService.MODE_STEAM),
            )
        }
        Log.i(TAG, "perf: " + PerfMode.apply(this, SessionState.fpsLimit))
        if (!SessionState.running) SessionState.followScreen = followChoice()
        applyFollowOrientation(resources.configuration)
        goFullscreen()
        // The device's volume keys change the stream the session plays on (the relay and
        // PulseAudio are media playback); they are never forwarded to the guest.
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        refreshSecondScreenDisplays()

        // No runtime is not a reason to leave: the loading screen installs it (installThenStart,
        // below) and the session starts when it is in.
        if (!LinuxRuntime.isInstalled(this)) Log.i(TAG, "the Linux runtime is not installed; the loading screen installs it")

        // Captured mouse events go down the focus chain, and focus sits somewhere in the overlay:
        // the root sees every one of them whichever view that is.
        val root = object : FrameLayout(this) {
            override fun dispatchCapturedPointerEvent(event: MotionEvent): Boolean =
                onCapturedPointer(event) || super.dispatchCapturedPointerEvent(event)
            override fun dispatchPointerCaptureChanged(hasCapture: Boolean) {
                super.dispatchPointerCaptureChanged(hasCapture)
                Log.i(TAG, "pointer capture " + if (hasCapture) "on" else "off")
            }
        }
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        root.addView(surfaceView)

        val bridge = PadBridge(File(LinuxRuntime.sessionRoot(this), "dev/input"))
        padBridge = bridge
        SessionState.padBridge = bridge
        padMotion = com.droiddeck.launcher.input.PadMotion(this) {
            @Suppress("DEPRECATION")
            (if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay)?.rotation ?: android.view.Surface.ROTATION_0
        }
        SessionState.deckPadListener = deckPadListener
        openDeckRing("activity created")
        // A player on the pad or the on-screen controls has no use for the mouse arrow; the next
        // touchpad or mouse move brings it back (showCursor), once the pad has been quiet a moment.
        bridge.setOnPlayerInput {
            lastPadInputMs = android.os.SystemClock.uptimeMillis()
            uiHandler.removeCallbacks(cursorHide)
            cursorVisible = false
        }
        onScreenControls = OnScreenControls(this, bridge, onKeyboard = ::togglePcKeyboard).also { root.addView(it) }
        keyboard = KeyboardHost(this).also { root.addView(it) }
        touchpad = TouchpadGestures(PointerGestures.slop(this), pointerListener)
        // One arrow, ours: Android draws a system pointer for a mouse over any window, and the
        // session already draws the pointer it is sent.
        val noCursor = android.view.PointerIcon.getSystemIcon(this, android.view.PointerIcon.TYPE_NULL)
        root.pointerIcon = noCursor
        surfaceView.pointerIcon = noCursor

        loading = LoadingState(this, steam = loadingMode() == SessionService.MODE_STEAM)
        if (!SessionState.running) {
            val needRuntime = com.droiddeck.launcher.runtime.LinuxRuntimeInstaller.installedVersion(this) == null
            val needDesktop = intent.getStringExtra(SessionService.EXTRA_MODE) == SessionService.MODE_DESKTOP &&
                !com.droiddeck.launcher.runtime.DesktopCatalog.desktopInstalled(this)
            val needProton = (intent.getStringExtra(SessionService.EXTRA_MODE) ?: SessionService.MODE_STEAM) == SessionService.MODE_STEAM &&
                com.droiddeck.launcher.runtime.DesktopCatalog.protonSeedNeeded(this)
            if (needRuntime || needDesktop || needProton) installThenStart(needRuntime, needDesktop, needProton)
        }
        hud = PerfHud(this)
        hud.onPresentingWindowChanged = {
            if (FrameGen.engine(this) != FrameGen.ENGINE_OFF) CompositorHost.rearmFrameGen { applyFrameGen() }
        }
        // A session that has already drawn is past its milestones; do not cover its picture.
        if (SessionState.running && SessionState.firstFrameSeen) {
            loading.visible = false
            hud.start()
        }
        readPrefs()

        intent.getIntExtra(com.droiddeck.launcher.ui.EXTRA_FLOOD, 0).takeIf { it != 0 && loading.visible }?.let {
            floodColor = it
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(it))
        }
        sessionOverlay = createSessionOverlay()
        root.addView(sessionOverlay)
        setContentView(root)
        pip = com.droiddeck.launcher.session.SessionPipController(this, ::pipBounds, ::showPipUi)
        surfaceView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> pip.refresh() }
        pip.refresh()
        handleHomeGuideIntent(intent)

        updateOnScreenControls()
        WaylandCompositor.setFirstFrameListener(firstFrameListener)
        WaylandCompositor.setPointerLockListener(pointerLockListener)
        SessionEvents.markReadyIfPossible()
        SessionState.endListener = endListener
        // A single Back opens the session menu; two quick presses/swipes send the Steam QAM chord.
        // The single action waits out the double-press window so the two actions stay distinct.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (pipUi) return
                // The PC keyboard closes first (B on a controller), then Back is the drawer again.
                when {
                    ::loading.isInitialized && loading.ended -> finish()
                    drawerOpen -> drawerOpen = false
                    pcKeyboardOpen -> pcKeyboardOpen = false
                    else -> routeBackAction()
                }
            }
        })
        watchSession()
    }

    /**
     * One Compose layer for every menu and overlay. Touches nothing in it consumes fall through to
     * the pad and the game underneath.
     */
    private fun createSessionOverlay(): ComposeView = ComposeView(this).apply {
        isFocusable = true
        isFocusableInTouchMode = true
        // Compose draws each control's focus ring. Android's default highlight would cover
        // this full-screen host and remain as a white veil after the drawer closes.
        defaultFocusHighlightEnabled = false
        setContent {
            // The drawer, the PC keyboard and picture in picture take the mouse back.
            androidx.compose.runtime.LaunchedEffect(drawerOpen, pcKeyboardOpen, pipUi) { updatePointerCapture() }
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
                CursorOverlay(cursorPos, cursorVisible, resources.displayMetrics.density,
                    cursorImage, cursorHotX, cursorHotY, cursorImageScale)
                if (hud.text.isNotEmpty()) HudText(hud.text)
                if (loading.visible) LoadingOverlay(
                    loading.step, loading.percent, loading.elapsed, loading.hint, loading.ended,
                    topic = loading.topic, readable = loading.readable,
                    title = loadingTitle(), steam = loadingMode() == SessionService.MODE_STEAM,
                    // As the drawer's Stop does: the service stops, and a start still installing is cancelled.
                    onCancel = { SessionService.stop(this@SessionActivity); finish() },
                    endedDetail = loading.endedDetail,
                    onRetry = { retrySession() },
                    onShareLogs = { shareCurrentSessionLogs() },
                    onClose = { finish() },
                    flood = floodColor?.let { androidx.compose.ui.graphics.Color(it) },
                    onFlooded = {
                        floodColor = null
                        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
                    },
                )
                // Opening the drawer takes the controller away from the game: release its pad.
                androidx.compose.runtime.LaunchedEffect(drawerOpen) {
                    if (drawerOpen) {
                        syncLossless()
                        padBridge?.releaseAll()
                        androidx.compose.runtime.withFrameNanos { }
                        val requested = sessionOverlay.requestFocus()
                        Log.i(TAG, "drawer root focus requested=$requested focused=${sessionOverlay.hasFocus()}")
                    } else {
                        releaseDrawerDirection()
                    }
                }
                // The PC keyboard takes it too, for moving over the keys; the drawer opens over it.
                androidx.compose.runtime.LaunchedEffect(pcKeyboardOpen) { if (pcKeyboardOpen) padBridge?.releaseAll() }
                if (pcKeyboardOpen) com.droiddeck.launcher.ui.PcKeyboard(
                    sendKey = { code, down -> if (CompositorHost.isStarted) sendGuestKey(code, down) },
                    onAndroidKeyboard = { pcKeyboardOpen = false; keyboard?.toggle() },
                    onClose = { pcKeyboardOpen = false },
                )
                SessionDrawer(drawerOpen, drawerPage, drawerControllerActive, onPageChange = { drawerPage = it }, a = DrawerActions(
                    steam = SessionState.mode == SessionService.MODE_STEAM,
                    title = if (SessionState.mode == SessionService.MODE_RUN)
                        com.droiddeck.launcher.frontend.Library.nameForProgram(SessionState.program) ?: getString(R.string.session_title_game) else null,
                    isHomeApp = isHomeApp,
                    androidApps = androidApps,
                    hudOn = hudOn,
                    upscaler = upscaler, upscaleSharpness = upscaleSharpness,
                    effects = effects, textureAnisotropy = textureAnisotropy, textureLodBias = textureLodBias,
                    frameGen = frameGen,
                    lossless = lossless,
                    oscMode = oscMode, onScreenButtonsVisible = onScreenButtonsVisible, suspendPolicy = suspendPolicy,
                    steamDownloadsInBackground = steamDownloadsInBackground, touchMode = touchMode,
                    touchAuto = getString(if (usingTouchpad()) R.string.session_touch_auto_touchpad else R.string.session_touch_auto_direct),
                    fexPreset = fexPreset,
                    secondScreenMode = secondScreenMode,
                    secondScreenDisplays = secondScreenDisplays,
                    selectedSecondScreenDisplay = selectedSecondScreenDisplay,
                    onHud = { on -> SessionPrefs.setHudEnabled(this@SessionActivity, on); hudOn = on; hud.refresh() },
                    onUpscaler = { m ->
                        SessionPrefs.setUpscaler(this@SessionActivity, m); upscaler = m
                        WaylandCompositor.nativeSetUpscaler(m)
                        // Two Looks carry a scaling mode of their own, so the mode decides which one the log names.
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
                    onTextureAnisotropy = { v -> SessionPrefs.setTextureAnisotropy(this@SessionActivity, v); textureAnisotropy = v },
                    onTextureLodBias = { v -> SessionPrefs.setTextureLodBias(this@SessionActivity, v); textureLodBias = v },
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
                    backActionsInverted = backActionsInverted,
                    onBackActionsInverted = { inverted ->
                        SessionPrefs.setBackActionsInverted(this@SessionActivity, inverted)
                        backActionsInverted = inverted
                    },
                    onOsc = { v -> SessionPrefs.setOscMode(this@SessionActivity, v); readPrefs(); updateOnScreenControls() },
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
                    onFexPreset = { v -> SessionPrefs.setFexPreset(this@SessionActivity, v); readPrefs() },
                    onSecondScreenMode = ::selectSecondScreenMode,
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
                    onPipAutoEnter = { on ->
                        SessionPrefs.setPipAutoEnter(this@SessionActivity, on)
                        pipAutoEnter = on
                        pip.refresh(true)
                    },
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
                    onComponentsRefresh = { refreshDrawerComponents() },
                    onComponentSwap = { pid, comp, value -> swapDrawerComponent(pid, comp, value) },
                ))
                if (SessionState.suspended) SessionPausedOverlay(
                    title = pausedTitle(),
                    onResume = { SessionService.resume(this@SessionActivity) },
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

    private fun triggerSteamQam() {
        drawerOpen = false
        padBridge?.triggerQam()
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
        // Wait 500 ms for a second Back press before performing the single-press action.
        uiHandler.postDelayed(pending, 500L)
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
        if (SessionState.running && SessionState.mode == SessionService.MODE_STEAM) sendSteamGuide()
    }


    /** Re-reads the Protons for the drawer's Components tab, first running swaps a closed game was holding back. */
    private fun refreshDrawerComponents() {
        Thread({
            val applied = runCatching { ComponentsManager.applyQueued(this) }.getOrDefault(emptyList())
            val snap = runCatching { ComponentsManager.snapshot(this) }.getOrNull()
            uiHandler.post {
                drawerComponents = snap
                if (applied.isNotEmpty()) android.widget.Toast.makeText(this, getString(R.string.main_applied, applied.joinToString(", ")), android.widget.Toast.LENGTH_LONG).show()
            }
        }, "drawer-components").start()
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
                    // Picking what is already in place again cancels the swap waiting for the game.
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

    /** The session this screen is bringing up: the live one when re-attached, else the intent's. */
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
        // Also when the desktop was asked for with Steam (Big Picture's Switch to Desktop): the client
        // does not open on it.
        SessionService.MODE_DESKTOP -> getString(R.string.session_starting_desktop)
        SessionService.MODE_RUN -> {
            val program = if (SessionState.running) SessionState.program else intent.getStringExtra(SessionService.EXTRA_PROGRAM)
            com.droiddeck.launcher.frontend.Library.nameForProgram(program)?.let { getString(R.string.session_starting_named, it) }
                ?: getString(R.string.session_starting_program)
        }
        else -> getString(R.string.load_starting_steam)
    }

    private fun shareCurrentSessionLogs() {
        // Once a session has ended its folder has been handed on (SessionPaths.take), and the
        // ended screen's Share logs is for that one.
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
        hudOn = SessionPrefs.hudEnabled(this)
        upscaler = SessionPrefs.upscaler(this)
        upscaleSharpness = SessionPrefs.upscaleSharpness(this)
        effects = SessionPrefs.screenEffects(this)
        textureAnisotropy = SessionPrefs.textureAnisotropy(this)
        textureLodBias = SessionPrefs.textureLodBias(this)
        val nextTouchMode = SessionPrefs.touchMode(this)
        if (touchMode != nextTouchMode) {
            if (::touchpad.isInitialized) touchpad.cancel()
            if (CompositorHost.isStarted) {
                WaylandCompositor.nativeSendTouch(3, -1, 0, 0)
                listOf(PointerGestures.BTN_LEFT, PointerGestures.BTN_RIGHT, PointerGestures.BTN_MIDDLE).forEach {
                    WaylandCompositor.nativeSendSceneInput(3, it, 0)
                }
            }
            if (nextTouchMode == SessionPrefs.TOUCH_OFF) cursorVisible = false
        }
        touchMode = nextTouchMode
        frameGen = FrameGen.mode(this)
        fexPreset = SessionPrefs.fexPreset(this)
        suspendPolicy = SessionPrefs.suspendPolicy(this, SessionState.mode)
        steamDownloadsInBackground = SessionPrefs.steamDownloadsInBackground(this)
        oscMode = SessionPrefs.oscMode(this)
        backActionsInverted = SessionPrefs.backActionsInverted(this)
    }

    // ── Compositor ──────────────────────────────────────────────────────────────────────────

    /** True while the loading screen is installing the Linux runtime or the desktop; the session waits for it. */
    @Volatile private var installingRuntime = false

    /**
     * First Play (or Desktop) on a fresh install: the runtime, and for the desktop its package,
     * are downloaded and unpacked here, on the loading screen's own line and bar, and the session
     * starts when they are in. Nothing else changes.
     *
     * The first Steam session also gets Valve's ARM64 Proton here, before the client has ever
     * started, so the compatibility tool is installed and the default at sign-in with no install
     * dialog, download or client restart. Not being able to fetch it stops nothing: the session
     * then asks the client for it after sign-in, as it always has.
     */
    private fun installThenStart(runtime: Boolean, desktop: Boolean, proton: Boolean = false) {
        if (SessionState.stopRequested) return
        installingRuntime = true
        SessionState.installing = if (runtime) "runtime" else if (desktop) "desktop" else "proton"
        SessionEvents.transition(
            SessionPhase.INSTALLING_RUNTIME,
            "${SessionState.installing}.installing",
            mapOf("component" to SessionState.installing),
        )
        val first = if (runtime) RUNTIME_LINES else if (desktop) DESKTOP_LINES else PROTON_LINES
        loading.say(getString(first.downloading), first.topic, readable = true)
        loading.percent = -1
        Thread({
            var failedComponent: String? = null
            var problem: String? = null
            if (runtime) {
                problem = installRuntime()
                if (problem == null) SessionEvents.record("runtime.ready") else failedComponent = "runtime"
            }
            if (problem == null && desktop && !SessionState.stopRequested) {
                SessionState.installing = "desktop"
                SessionEvents.transition(SessionPhase.INSTALLING_RUNTIME, "desktop.installing", mapOf("component" to "desktop"))
                problem = installDesktop()
                if (problem == null) SessionEvents.record("desktop.ready") else failedComponent = "desktop"
            }
            if (problem == null && proton && !SessionState.stopRequested) {
                SessionState.installing = "proton"
                SessionEvents.transition(SessionPhase.INSTALLING_RUNTIME, "proton.installing", mapOf("component" to "proton"))
                val protonProblem = installProton()
                if (protonProblem == null) SessionEvents.record("proton.ready")
                else SessionEvents.record("proton.skipped", mapOf("reason" to protonProblem))
            }
            uiHandler.post {
                installingRuntime = false
                SessionState.installing = null
                if (SessionState.stopRequested) {
                    SessionState.stopRequested = false
                    SessionEvents.transition(SessionPhase.IDLE, "session.cancelled")
                    collectStartArtifacts("session start cancelled")
                    finish()
                    return@post
                }
                if (problem != null) {
                    val code = if (failedComponent == "runtime") "RUNTIME_INSTALL_FAILED" else "DESKTOP_INSTALL_FAILED"
                    SessionEvents.fail(code, problem)
                    collectStartArtifacts("session start failed")
                    loading.showEnded(problem)
                    focusEndedScreen()
                    return@post
                }
                loading.percent = -1
                loading.say(getString(R.string.session_starting_session), LoadingState.Topic.SESSION, readable = false)
                // The surface may have come and gone while the download ran; start on the live one.
                if (surfaceView.holder.surface?.isValid == true) surfaceCreated(surfaceView.holder)
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

    /**
     * The loading screen's lines for one package: downloading it (with and without its size), checking it,
     * unpacking it; the checklist [topic] they belong to, and whether the checking line is shown as it is.
     */
    private class PackageLines(
        val downloading: Int, val downloadingMb: Int, val checking: Int, val unpacking: Int,
        val topic: LoadingState.Topic, val checkingReadable: Boolean,
    )

    /** Reports one package's download on the loading screen: "<what> · 332 of 791 MB", checking, unpacking. */
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

    /** Null when the runtime is in, else the loading screen's closing line. */
    private fun installRuntime(): String? {
        val release = com.droiddeck.launcher.runtime.LinuxRuntimeInstaller.fetchRelease()
            ?: return getString(R.string.session_runtime_catalog_unreachable)
        val ok = com.droiddeck.launcher.runtime.LinuxRuntimeInstaller.install(this, release,
            progressFor(RUNTIME_LINES, release.size / 1_000_000))
        return if (ok) null else getString(R.string.session_runtime_install_failed)
    }

    /** Null when the desktop package is in, else the loading screen's closing line. */
    private fun installDesktop(): String? {
        uiHandler.post { loading.percent = -1; loading.say(getString(DESKTOP_LINES.downloading), DESKTOP_LINES.topic, readable = true) }
        val entry = com.droiddeck.launcher.runtime.DesktopCatalog.desktopEntry(this)
        val problem = com.droiddeck.launcher.runtime.DesktopCatalog.install(this, entry,
            progressFor(DESKTOP_LINES, entry.size / 1_000_000))
        return problem?.let { getString(R.string.session_desktop_install_failed, it) }
    }

    /** Null when the ARM64 Proton is in, else why not; the session goes on either way. */
    private fun installProton(): String? {
        uiHandler.post { loading.percent = -1; loading.say(getString(PROTON_LINES.downloading), PROTON_LINES.topic, readable = true) }
        val catalog = com.droiddeck.launcher.runtime.DesktopCatalog
        val entry = catalog.fetch(catalog.STEAM_SEED_URL)?.firstOrNull { it.id == catalog.PROTON_SEED_ID }
            ?: return "catalog unreachable"
        return catalog.install(this, entry, progressFor(PROTON_LINES, entry.size / 1_000_000))
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (installingRuntime || SessionState.stopRequested) return
        if (!SessionState.running) {
            SessionEvents.transition(SessionPhase.STARTING_COMPOSITOR, "compositor.starting")
        }
        val runtimeDir = File(filesDir, ".wayland-rt").apply { mkdirs() }
        // The compositor hands this keymap to wl_keyboard clients, which is how the guest reads
        // the evdev codes we inject.
        FileUtils.copyAsset(this, "wayland/keymap.xkb", File(runtimeDir, "keymap.xkb"))

        // Turnip, not the system Adreno driver: importing the dma-bufs gamescope commits needs
        // VK_EXT_image_drm_format_modifier, which the system driver does not implement.
        val turnip = TurnipDriver(this)
        val driverId = if (CompositorHost.isStarted) null else turnip.install()

        // The output size belongs to the session, not to the Surface: gamescope's display is
        // sized once when the session starts and cannot change. A foldable recreates the Surface
        // on the other panel, and recomputing the size there told the compositor a 21:9 buffer
        // was 16:9 - the picture came back squashed sideways. While a session runs, keep its size.
        // Follow screen is the exception: there the compositor tells gamescope the new size, so the
        // session takes the shape of the Surface it now has.
        val previous = SessionState.outputSize
        val live = if (SessionState.followScreen && !isInPictureInPictureMode)
            com.droiddeck.launcher.session.SessionDisplay.followSize(holder.surfaceFrame.width(), holder.surfaceFrame.height())
        else null
        val size = live ?: if (SessionState.running) previous else outputSize()
        SessionState.outputSize = size
        if (!SessionState.running) SessionState.refreshHz = refreshHz()
        // How far the panel enlarges the session, for texture sharpness "Auto" (TextureFiltering):
        // the game environment is published again so the next launch derives it from this session.
        val panel = panelBounds()
        SessionState.upscaleRatio = maxOf(panel.width(), panel.height()).toFloat() / maxOf(size.first, size.second).coerceAtLeast(1)
        Thread({
            runCatching { GameEnvironmentStore.publish(this) }
                .onFailure { Log.e(TAG, "Could not update game environment", it) }
        }, "game-env-publish").start()
        // Letterbox, never stretch or crop: the output can be a different shape from the panel,
        // and a game's picture must keep its proportions with bars, not lose its edges.
        WaylandCompositor.nativeSetScaleMode(SCALE_FIT, ALIGN_CENTER)
        WaylandCompositor.nativeSetUpscaler(SessionPrefs.upscaler(this))
        WaylandCompositor.nativeSetUpscaleSharpness(SessionPrefs.upscaleSharpness(this))
        SessionPrefs.screenEffects(this).push(SessionPrefs.upscaler(this))
        // The session's folder, claimed here because the compositor starts before the service and
        // opens its log once. The compositor reads the path from its environment; setting it after
        // it has started changes nothing, which is why the service copies the file in at teardown.
        if (!SessionState.running) {
            val waylandLog = File(SessionPaths.beginOrCurrent(this), "wayland.log")
            WaylandCompositor.setSessionLogFile(waylandLog)
            try {
                android.system.Os.setenv("BL_WAYLAND_LOG", waylandLog.path, true)
            } catch (e: Exception) {
                Log.w(TAG, "could not point the compositor's log at $waylandLog", e)
            }
        }
        // HDR10: the gate is decided once, when the compositor starts (it lives for the whole app
        // process), from the panel's own word and the mode's setting. Zero-copy presentation is
        // what puts an HDR frame on a display layer tagged BT2020_PQ, so it is turned on with it.
        if (!CompositorHost.isStarted) {
            val mode = SessionPrefs.prefMode(intent.getStringExtra(SessionService.EXTRA_MODE) ?: SessionService.MODE_STEAM)
            val probe = HdrSupport.probe(this)
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
        CompositorHost.startOrAttach(
            holder.surface, runtimeDir.path,
            driverId?.let { turnip.driverPath(it) }, driverId?.let { turnip.libraryName(it) },
            applicationInfo.nativeLibraryDir, size.first, size.second, refreshHz(), SessionState.fpsLimit,
        )
        if (SessionState.running && size != previous) {
            Log.i(TAG, "follow screen: ${previous.first}x${previous.second} -> ${size.first}x${size.second} on a new surface")
            WaylandCompositor.nativeResizeOutput(size.first, size.second)
        }
        if (!SessionState.running) SessionEvents.record("compositor.started")
        // The service owns everything below the compositor. It is started whenever no session is
        // running - NOT only when the compositor was just started: the compositor lives for the
        // whole process, so the second Play after a session ended used to re-attach the Surface,
        // start nothing, and leave the loading panel counting up over a dead session.
        if (!SessionState.running) {
            SessionEvents.transition(SessionPhase.STARTING_GUEST, "guest.starting")
            SessionService.start(
                this, intent.getStringExtra(SessionService.EXTRA_MODE) ?: SessionService.MODE_STEAM,
                intent.getStringExtra(SessionService.EXTRA_PROGRAM),
                intent.getStringExtra(SessionService.EXTRA_STEAM_UI),
                intent.getStringExtra(SessionService.EXTRA_STEAM_URL),
                intent.getStringArrayExtra(SessionService.EXTRA_PROGRAM_ARGS),
            )
        }
        applyFrameGen()
    }

    private var surfaceW = 0
    private var surfaceH = 0

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val resized = surfaceW != 0 && (width != surfaceW || height != surfaceH)
        surfaceW = width
        surfaceH = height
        if (resized) {
            Log.i(TAG, "surface resized to ${width}x$height - rebinding the compositor")
            CompositorHost.resize(holder.surface) { applyFrameGen() }
            followWindow(width, height)
        }
    }

    /**
     * Follow screen: the session's display takes the window's new size (a Fold opening onto its
     * inner panel, or closing onto the cover), so the picture fills the panel instead of being
     * letterboxed onto it. Not in picture-in-picture: that window is a preview of the session,
     * and resizing the guest to it would leave Steam laid out for a thumbnail.
     */
    private fun followWindow(width: Int, height: Int) {
        if (!SessionState.followScreen || isInPictureInPictureMode || !CompositorHost.isStarted) return
        val size = com.droiddeck.launcher.session.SessionDisplay.followSize(width, height) ?: return
        if (size == SessionState.outputSize) return
        Log.i(TAG, "follow screen: ${SessionState.outputSize.first}x${SessionState.outputSize.second} -> ${size.first}x${size.second}")
        SessionState.outputSize = size
        val panel = panelBounds()
        SessionState.upscaleRatio = maxOf(panel.width(), panel.height()).toFloat() / maxOf(size.first, size.second).coerceAtLeast(1)
        WaylandCompositor.nativeResizeOutput(size.first, size.second)
    }

    /** Whether this session's resolution is Follow screen; decided once, when it starts. */
    private fun followChoice(): Boolean {
        val mode = SessionPrefs.prefMode(intent.getStringExtra(SessionService.EXTRA_MODE) ?: SessionService.MODE_STEAM)
        return SessionPrefs.resolutionChoice(this, mode, com.droiddeck.launcher.session.SessionDisplay.panelSize(this)) ==
            com.droiddeck.launcher.session.SessionDisplay.FOLLOW_SCREEN
    }

    /**
     * Follow screen on a large panel (a Fold's inner screen) may turn with the device: a window
     * locked to landscape on a squarer panel is letterboxed by the system itself, which no output
     * size can undo. A phone-sized panel (the cover screen) stays landscape as before.
     */
    private fun applyFollowOrientation(config: android.content.res.Configuration) {
        if (!SessionState.followScreen) return
        val wanted = if (config.smallestScreenWidthDp >= 600) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        if (requestedOrientation != wanted) requestedOrientation = wanted
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyFollowOrientation(newConfig)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceW = 0
        surfaceH = 0
        CompositorHost.detach(holder.surface)
    }

    /**
     * The size the session renders at. gamescope is told this and scales its output onto whatever
     * the panel is, so a 1440p phone can run the client at 1080p without the client knowing.
     */
    /** The panel, not the window: resources.displayMetrics is what is left after the system bars and the cutout are taken out. */
    private fun panelBounds(): android.graphics.Rect = if (Build.VERSION.SDK_INT >= 30) {
        windowManager.maximumWindowMetrics.bounds
    } else {
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        android.graphics.Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    private fun outputSize(): Pair<Int, Int> {
        val mode = SessionPrefs.prefMode(intent.getStringExtra(SessionService.EXTRA_MODE) ?: SessionService.MODE_STEAM)
        val panel = com.droiddeck.launcher.session.SessionDisplay.panelSize(this)
        val program = intent.getStringExtra(SessionService.EXTRA_PROGRAM)
        // Lightweight emulators keep their panel-sized default until a resolution is chosen.
        val choice = if (intent.getStringExtra(SessionService.EXTRA_MODE) == SessionService.MODE_RUN &&
            Library.drawsAtPanel(program) && !SessionPrefs.resolutionChosen(this, mode))
            com.droiddeck.launcher.session.SessionDisplay.MATCH_SCREEN
        else SessionPrefs.resolutionChoice(this, mode, panel)
        return com.droiddeck.launcher.session.SessionDisplay.resolveChoice(panel, choice)
    }

    /**
     * The rate of the mode PerfMode asked for, not the one the panel is still in: the switch lands
     * some frames after onCreate, and read too early it gave gamescope `-r 60` on a 120 Hz panel -
     * the client and every game then paced themselves to 60. (WinNative's requestedPanelRefreshRate.)
     */
    private fun refreshHz(): Float {
        val display = if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay
        val wanted = window.attributes.preferredDisplayModeId
        val hz = display?.supportedModes?.firstOrNull { wanted != 0 && it.modeId == wanted }?.refreshRate
            ?: display?.refreshRate ?: 60f
        return if (hz > 1f) hz else 60f
    }

    /**
     * The saved frame-generation setting, pushed to the compositor. Off the main thread: LSFG's
     * first use translates the shader chain out of Lossless.dll, which takes seconds.
     */
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

    /** A Lossless Scaling installed or updated in Steam during the session is taken when the drawer opens. */
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

    // ── Session state ───────────────────────────────────────────────────────────────────────

    /** Keeps the loading overlay current from the session log, and its clock moving. */
    private fun watchSession() {
        val handler = Handler(Looper.getMainLooper())
        var ticks = 0
        val poll = object : Runnable {
            override fun run() {
                if (!watching) return
                if (::pip.isInitialized) pip.refresh()
                if (SessionState.stopRequested && !SessionState.running && !installingRuntime) {
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
        if (isInPictureInPictureMode) {
            runOnUiThread { finish() }
            return
        }
        closeSecondScreen(reset = true)
        if (status == 0) {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                // A session asked for in this one's place (the desktop's Steam launchers or
                // Steam's Switch to Desktop) starts here: this activity restarts on its intent, as an agent
                // start does. startActivity(next) could not do it - the activity is singleTop, so
                // the request landed in this instance's onNewIntent and went with its finish().
                SessionState.relaunch?.let { next ->
                    SessionState.relaunch = null
                    next.putExtra(EXTRA_RETURN_HOME, intent.getBooleanExtra(EXTRA_RETURN_HOME, false))
                    setIntent(next)
                    recreate()
                    return@runOnUiThread
                }
                finish()
            }
            return
        }
        // What the log says about why, read off the main thread (notifyEnded arrives on it).
        Thread({
            val hint = sessionEndHint(SessionState.logFile)
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
        // It stays until the user leaves: a failure read against a timer is a failure not read.
        val stopped = when (SessionState.mode) {
            SessionService.MODE_RUN -> com.droiddeck.launcher.frontend.Library.nameForProgram(SessionState.program)
                ?.let { getString(R.string.session_ended_named, it) } ?: getString(R.string.session_ended_program)
            SessionService.MODE_DESKTOP -> getString(R.string.session_ended_desktop)
            else -> getString(R.string.session_ended_named, "Steam")
        }
        loading.showEnded(
            hint?.text ?: stopped,
            getString(R.string.session_ended_detail, status, SessionState.logFile?.path ?: getString(R.string.session_ended_no_log)),
        )
        focusEndedScreen()
    }

    /** The ended screen's buttons take the pad: it is no longer driving a game. */
    private fun focusEndedScreen() {
        drawerOpen = false
        padBridge?.releaseAll()
        uiHandler.post { sessionOverlay.requestFocus() }
    }

    /** The same start again, in a fresh activity: the ended one keeps no session to reuse. */
    private fun retrySession() {
        val again = Intent(intent)
        closeAtOnce = true
        finish()
        startActivity(again)
    }

    /**
     * One line of advice for a failure the log identifies, or null. The one this recognises: the
     * ENOSYS storm - dozens of `socket(): Function not implemented` / `shared memfd open() failed`
     * lines - which is proot's seccomp acceleration failing an x86 helper (Steam's xalia under
     * FEX) on some devices (a Fold 5, twice). The switch that answers it is in Performance, and a
     * user who never opens the log would not know.
     */
    private class EndHint(val text: String, val evenOnSuccess: Boolean = false)

    private fun sessionEndHint(log: File?): EndHint? {
        if (log == null || !log.isFile) return null
        return try {
            var enosys = 0
            var fexMissing = false
            // The tail is where a dying session says why; 512 KB covers the storm without reading a 1 GB log.
            val size = log.length()
            java.io.RandomAccessFile(log, "r").use { f ->
                val start = maxOf(0L, size - 512L * 1024)
                f.seek(start)
                val bytes = ByteArray((size - start).toInt())
                f.readFully(bytes)
                String(bytes, Charsets.ISO_8859_1).lineSequence().forEach { line ->
                    if (line.contains("Function not implemented")) enosys++
                    if (line.contains(FEX_MISSING)) fexMissing = true
                }
            }
            if (fexMissing) {
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

    // ── Input ───────────────────────────────────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The device's own volume keys belong to Android: forwarded to the guest as keys they
        // changed nothing anyone could hear (a Pocket FIT report, on the desktop).
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE ->
                return super.dispatchKeyEvent(event)
        }
        if (pipUi) return true
        val fromController = PadBridge.isControllerKey(event)
        if (fromController && event.action == KeyEvent.ACTION_DOWN) {
            if (drawerOpen && !drawerControllerActive) sessionOverlay.requestFocus()
            drawerControllerActive = true
        }
        // While the session starts, B is the loading screen's Cancel, as its corner says.
        if (fromController && event.keyCode == KeyEvent.KEYCODE_BUTTON_B && ::loading.isInitialized && loading.visible && !loading.ended && !drawerOpen) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                SessionService.stop(this)
                finish()
            }
            return true
        }
        // The ended screen is a page of buttons: the pad drives them, and B leaves.
        if (::loading.isInitialized && loading.ended) {
            if (event.keyCode == KeyEvent.KEYCODE_BUTTON_B || event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) finish()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (drawerOpen) {
            if (fromController && (event.keyCode == KeyEvent.KEYCODE_BUTTON_L1 || event.keyCode == KeyEvent.KEYCODE_BUTTON_R1)) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    drawerPage = sessionDrawerPages(SessionState.mode == SessionService.MODE_STEAM && secondScreenDisplays.isNotEmpty())
                        .step(drawerPage, if (event.keyCode == KeyEvent.KEYCODE_BUTTON_R1) 1 else -1)
                    releaseDrawerDirection()
                }
                return true
            }
            val handled = super.dispatchKeyEvent(event)
            if (event.action == KeyEvent.ACTION_DOWN) {
                Log.i(TAG, "drawer key=${KeyEvent.keyCodeToString(event.keyCode)} handled=$handled viewFocused=${sessionOverlay.hasFocus()}")
            }
            if (event.keyCode == KeyEvent.KEYCODE_BUTTON_B || event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (!handled && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) drawerOpen = false
                releaseDrawerDirection()
            }
            return true
        }
        val resumeKey = event.keyCode == KeyEvent.KEYCODE_BUTTON_A || event.keyCode == KeyEvent.KEYCODE_BUTTON_START ||
            event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE || event.keyCode == KeyEvent.KEYCODE_HOME
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
        if (pcKeyboardOpen && fromController) return super.dispatchKeyEvent(event)
        if (event.keyCode != KeyEvent.KEYCODE_BACK && padBridge?.onKeyEvent(event) == true) return true
        // A hardware keyboard, forwarded to the compositor's wl_keyboard. Back is left to the
        // activity, which opens the drawer.
        val fromPad = fromController
        if (CompositorHost.isStarted && event.keyCode != KeyEvent.KEYCODE_BACK && !fromPad) {
            val down = event.action == KeyEvent.ACTION_DOWN
            if (down || event.action == KeyEvent.ACTION_UP) {
                var evdev = EvdevKeys.fromKeyCode(event.keyCode)
                // What the key stands for, and the key it would sit on without Shift - non-zero
                // only for a character Shift puts there: every capital and the symbol row.
                val ch = event.unicodeChar
                val plain = if (ch > 0) EvdevKeys.unshiftedChar(ch) else 0
                // A soft keyboard's symbol keys are in neither the table nor a scan code, so they
                // reached the session as nothing at all - a sign-in took an address without its @.
                // Work back from the character instead: which key carries it.
                if (evdev <= 0 && ch > 0) {
                    val code = EvdevKeys.fromKeyCode(EvdevKeys.keycodeForChar(if (plain != 0) plain else ch))
                    if (code > 0) evdev = code
                }
                // The modifier has to be made here. A soft keyboard reports Shift in the event's
                // meta state and sends no Shift key of its own, so a capital arrives as a key this
                // side already knows and came out lowercase. A hardware keyboard sends its own
                // Shift, and a second one would release the modifier while the key is still held.
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
        if (drawerOpen && event.device != null && PadBridge.isFromController(event.device)) {
            if (!drawerControllerActive) sessionOverlay.requestFocus()
            drawerControllerActive = true
            if (event.actionMasked == MotionEvent.ACTION_MOVE &&
                event.isFromSource(InputDevice.SOURCE_JOYSTICK)
            ) {
                dispatchDrawerDirection(event)
            }
            return true
        }
        if (drawerDirectionKey != KeyEvent.KEYCODE_UNKNOWN) releaseDrawerDirection()
        if (pcKeyboardOpen && event.device != null && PadBridge.isFromController(event.device)) return super.dispatchGenericMotionEvent(event)
        if (padBridge?.onMotionEvent(event) == true) return true
        if (event.isFromSource(android.view.InputDevice.SOURCE_MOUSE) && !drawerOpen && onMouse(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    /** With no view focused, captured mouse events skip the root and arrive here as trackball events. */
    override fun dispatchTrackballEvent(event: MotionEvent): Boolean =
        onCapturedPointer(event) || super.dispatchTrackballEvent(event)

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (pipUi) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN && event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
            drawerControllerActive = false
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

    /** Use Android's per-device flat region, then rescale the remaining stick travel to 0..1. */
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
        super.dispatchKeyEvent(event)
    }

    private val pointerListener = object : PointerGestures.Listener {
        override fun onMove(x: Float, y: Float) = movePointer(x, y)
        override fun onButton(button: Int, pressed: Boolean, x: Float, y: Float) {
            movePointer(x, y)
            WaylandCompositor.nativeSendSceneInput(3, button, if (pressed) 1 else 0)
        }
        override fun onWheel(steps: Int) { WaylandCompositor.nativeSendSceneInput(4, steps, 0) }
        override fun onLongPress() {
            @Suppress("DEPRECATION")
            (getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator)?.vibrate(15)
        }
    }

    private fun movePointer(x: Float, y: Float) {
        val rect = drawnRect() ?: return
        val cx = x.coerceIn(rect.left, rect.right)
        val cy = y.coerceIn(rect.top, rect.bottom)
        WaylandCompositor.nativeSendPointer(1, InputSpace.x(cx, surfaceView.width), InputSpace.y(cy, surfaceView.height))
        showCursor(cx, cy)
    }

    /**
     * The arrow stays on a desktop; in a Steam session it shows for a moment after each move. While
     * a controller is driving (input in the last [CURSOR_PAD_HOLD_MS]) it stays hidden, so a touch
     * during play does not flash it up - Bannerlator's rule for its Wayland pointer.
     */
    private fun showCursor(x: Float, y: Float) {
        cursorPos = androidx.compose.ui.geometry.Offset(x, y)
        syncClientCursor()
        uiHandler.removeCallbacks(cursorResync)
        uiHandler.postDelayed(cursorResync, 60)
        // A program that asked for no pointer (a mouse-look game) wins over everything below.
        if (clientHidesCursor) { cursorVisible = false; return }
        if (android.os.SystemClock.uptimeMillis() - lastPadInputMs < CURSOR_PAD_HOLD_MS) return
        cursorVisible = true
        uiHandler.removeCallbacks(cursorHide)
        if (SessionState.mode == SessionService.MODE_STEAM && SessionState.steamUi != "desktop") uiHandler.postDelayed(cursorHide, 2500)
    }

    /**
     * Pulls the program's pointer (wl_pointer.set_cursor) if it changed: labwc's resize arrows at a
     * window's edges and corners, a text field's I-beam, or a request for no pointer. Serial 0 = no
     * program has set one yet, and the built-in arrow stays. Returns true when something changed.
     */
    private fun syncClientCursor(): Boolean {
        if (!CompositorHost.isStarted) return false
        // Asked on every pointer move; the image is copied only when the client has changed it.
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
        // Session pixels -> screen pixels, as the session's picture is scaled (movePointer).
        val width = surfaceView.width.takeIf { it > 0 }
        val height = surfaceView.height.takeIf { it > 0 }
        val out = SessionState.outputSize
        if (width != null && height != null)
            cursorImageScale = minOf(width / out.first.toFloat(), height / out.second.toFloat())
        return true
    }

    private fun refreshSecondScreenDisplays() {
        if (!::displayManager.isInitialized) return
        val primaryId = display?.displayId ?: windowManager.defaultDisplay.displayId
        val candidates = SecondScreenDisplays.available(displayManager, primaryId)
        val oldIds = secondScreenDisplays.map { it.id }.toSet()
        secondScreenDisplays = candidates
        val newIds = candidates.map { it.id }.toSet()
        if (secondScreenMode != SecondScreenMode.NONE && oldIds.isNotEmpty() && selectedSecondScreenDisplay !in newIds) {
            closeSecondScreen(reset = true)
            return
        }
        if (selectedSecondScreenDisplay !in newIds) selectedSecondScreenDisplay = candidates.firstOrNull()?.id ?: -1
        if (secondScreenMode != SecondScreenMode.NONE && candidates.isEmpty()) closeSecondScreen(reset = true)
        // A choice kept while its display was away (asleep, waking): back as soon as it is.
        else if (started && secondScreenMode != SecondScreenMode.NONE && secondScreenPresentation == null) showSecondScreen(secondScreenMode)
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
            // Not there right now - a panel still waking, say. The choice is kept; the display
            // listener shows it again when the display comes back (refreshSecondScreenDisplays).
            closeSecondScreen(reset = false)
            return
        }
        var presentation = secondScreenPresentation
        if (presentation?.display?.displayId != target.displayId) {
            presentation?.closeControls()
            presentation = SecondScreenPresentation(
                this, target,
                sendPointer = ::moveSecondScreenPointer,
                sendButton = { button, pressed ->
                    WaylandCompositor.nativeSendSceneInput(3, button, if (pressed) 1 else 0)
                },
                sendWheel = { steps -> WaylandCompositor.nativeSendSceneInput(4, steps, 0) },
                onSteamMenu = {
                    if (SessionState.mode == SessionService.MODE_STEAM) {
                        padBridge?.applyTouch { st -> st.press(com.droiddeck.launcher.input.PadState.GUIDE, true) }
                        Handler(Looper.getMainLooper()).postDelayed({
                            padBridge?.applyTouch { st -> st.press(com.droiddeck.launcher.input.PadState.GUIDE, false) }
                        }, 90)
                    }
                },
                onQam = {
                    if (SessionState.mode == SessionService.MODE_STEAM) padBridge?.triggerQam()
                },
                onClose = { selectSecondScreenMode(SecondScreenMode.NONE) },
            )
            secondScreenPresentation = presentation
        }
        try {
            if (!presentation.isShowing) presentation.show()
            presentation.showMode(mode)
            secondScreenMode = mode
            SessionState.secondScreenMode = mode
            SessionState.secondScreenDisplay = selectedSecondScreenDisplay
        } catch (e: Exception) {
            Log.w(TAG, "could not show second-screen controls on ${target.name}", e)
            closeSecondScreen(reset = true)
        }
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

    private fun closeSecondScreen(reset: Boolean) {
        secondScreenPresentation?.closeControls()
        secondScreenPresentation = null
        if (reset) {
            secondScreenMode = SecondScreenMode.NONE
            SessionState.secondScreenMode = SecondScreenMode.NONE
        }
    }

    /** Touchpad on the desktop, direct in Steam, unless the drawer says otherwise. */
    private fun usingTouchpad(): Boolean = when (SessionPrefs.touchMode(this)) {
        SessionPrefs.TOUCH_PAD -> true
        SessionPrefs.TOUCH_DIRECT -> false
        else -> SessionState.mode != SessionService.MODE_STEAM || SessionState.steamUi == "desktop"
    }

    /** The picture's rectangle inside the view: where the pointer may go. */
    private fun drawnRect(): android.graphics.RectF? {
        val width = surfaceView.width.takeIf { it > 0 } ?: return null
        val height = surfaceView.height.takeIf { it > 0 } ?: return null
        val out = SessionState.outputSize
        val scale = minOf(width / out.first.toFloat(), height / out.second.toFloat())
        val drawnW = out.first * scale
        val drawnH = out.second * scale
        val left = (width - drawnW) / 2f
        val top = (height - drawnH) / 2f
        return android.graphics.RectF(left, top, left + drawnW, top + drawnH)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) return onMouse(event)
        // Child views get the event first, so menus, keyboards and the on-screen pad still work.
        if (touchMode == SessionPrefs.TOUCH_OFF) return true
        if (usingTouchpad()) {
            val rect = drawnRect() ?: return false
            if (touchpad.bounds != rect) {
                val fresh = touchpad.bounds.width() <= 1f
                touchpad.bounds = rect
                if (fresh) touchpad.place(rect.centerX(), rect.centerY())
            }
            return touchpad.onTouch(event)
        }
        val rect = drawnRect() ?: return false
        fun sendTouch(action: Int, index: Int) {
            val x = event.getX(index).coerceIn(rect.left, rect.right)
            val y = event.getY(index).coerceIn(rect.top, rect.bottom)
            WaylandCompositor.nativeSendTouch(action, event.getPointerId(index),
                InputSpace.x(x, surfaceView.width), InputSpace.y(y, surfaceView.height))
        }
        when (event.actionMasked) {
            // A first finger starts a new gesture, and Android has ended every earlier one: whatever
            // the compositor still holds (an up lost to the drawer opening mid-touch) goes first.
            MotionEvent.ACTION_DOWN -> {
                WaylandCompositor.nativeSendTouch(3, -1, 0, 0)
                listOf(PointerGestures.BTN_LEFT, PointerGestures.BTN_RIGHT, PointerGestures.BTN_MIDDLE).forEach {
                    WaylandCompositor.nativeSendSceneInput(3, it, 0)
                }
                sendTouch(0, event.actionIndex)
            }
            MotionEvent.ACTION_POINTER_DOWN -> sendTouch(0, event.actionIndex)
            MotionEvent.ACTION_MOVE -> for (index in 0 until event.pointerCount) sendTouch(1, index)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> sendTouch(2, event.actionIndex)
            MotionEvent.ACTION_CANCEL -> WaylandCompositor.nativeSendTouch(3, -1, 0, 0)
            else -> return false
        }
        return true
    }

    /** A mouse: hover moves, buttons press, the wheel scrolls. Android sends buttons as touch
     *  actions and hover as generic motion, so both paths land here. */
    private fun onMouse(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_ENTER ->
                movePointer(event.x, event.y)
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                movePointer(event.x, event.y)
                val button = when (event.actionButton) {
                    MotionEvent.BUTTON_SECONDARY -> PointerGestures.BTN_RIGHT
                    MotionEvent.BUTTON_TERTIARY -> PointerGestures.BTN_MIDDLE
                    else -> PointerGestures.BTN_LEFT
                }
                WaylandCompositor.nativeSendSceneInput(3, button, if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS) 1 else 0)
            }
            MotionEvent.ACTION_SCROLL -> {
                val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (v != 0f) WaylandCompositor.nativeSendSceneInput(4, -Math.round(v), 0)
            }
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP -> {} // the BUTTON_* events carry these
            else -> return false
        }
        return true
    }

    /** A physical mouse, not a touchscreen, stylus or the system's own virtual devices. */
    private fun mouseAttached(): Boolean = InputDevice.getDeviceIds().any { id ->
        val d = InputDevice.getDevice(id)
        d != null && !d.isVirtual && d.supportsSource(InputDevice.SOURCE_MOUSE)
    }

    /** Capture the mouse exactly while a program holds a lock and the session has the mouse. */
    private fun updatePointerCapture() {
        if (!::surfaceView.isInitialized) return
        val view = window.decorView
        val want = pointerLocked && resumed && hasWindowFocus() && !drawerOpen && !pcKeyboardOpen && !pipUi && mouseAttached()
        if (want == view.hasPointerCapture()) return
        if (want) view.requestPointerCapture() else view.releasePointerCapture()
    }

    /**
     * A captured mouse: raw motion as relative pointer deltas, buttons and the wheel as on the
     * uncaptured path. Anything else (a captured touchpad's absolute fingers) is left alone.
     */
    private fun onCapturedPointer(event: MotionEvent): Boolean {
        if (!pointerLocked || !event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                var dx = event.x
                var dy = event.y
                for (i in 0 until event.historySize) {
                    dx += event.getHistoricalX(i)
                    dy += event.getHistoricalY(i)
                }
                WaylandCompositor.sendPointerDelta(dx, dy)
            }
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                val button = when (event.actionButton) {
                    MotionEvent.BUTTON_SECONDARY -> PointerGestures.BTN_RIGHT
                    MotionEvent.BUTTON_TERTIARY -> PointerGestures.BTN_MIDDLE
                    else -> PointerGestures.BTN_LEFT
                }
                WaylandCompositor.nativeSendSceneInput(3, button, if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS) 1 else 0)
            }
            MotionEvent.ACTION_SCROLL -> {
                val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (v != 0f) WaylandCompositor.nativeSendSceneInput(4, -Math.round(v), 0)
            }
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP -> {} // the BUTTON_* events carry these
            else -> return false
        }
        return true
    }

    /** The mode decides which controls appear; `droiddeck-osc` in Downloads still overrides. */
    /** Which controller the session has, so a pad report says what was plugged in and when. */
    private fun logInputDevice(what: String, deviceId: Int) {
        val d = InputDevice.getDevice(deviceId) ?: return
        if (!PadBridge.isFromController(d)) return
        Log.i(TAG, String.format(java.util.Locale.ROOT, "controller %s: \"%s\" (%04x:%04x, id %d, sources 0x%x)",
            what, d.name, d.vendorId, d.productId, d.id, d.sources))
    }

    private fun updateOnScreenControls() {
        val forced = File(Environment.getExternalStorageDirectory(), "Download/droiddeck-osc")
            .takeIf { it.isFile }
            ?.let { FileUtils.readString(it)?.trim()?.lowercase() }
            ?: SessionPrefs.oscMode(this)
        val controls = onScreenControls ?: return
        if (pipUi) {
            onScreenButtonsVisible = false
            controls.releaseAll()
            controls.visibility = View.GONE
            return
        }
        val buttonsOnly = forced == SessionPrefs.OSC_STEAM_QAM
        controls.setButtonsOnly(buttonsOnly)
        val show = when (forced) {
            SessionPrefs.OSC_ALWAYS -> true
            SessionPrefs.OSC_STEAM_QAM -> true
            SessionPrefs.OSC_NEVER -> false
            // Auto: the touch pad when there is no controller - except on the desktop, where the
            // screen is a touchpad for the pointer and a pad over it would be in the way. A game
            // started from the rail, or Steam, gets it; the drawer turns it on anywhere.
            else -> !PadBridge.anyControllerConnected() && SessionState.mode != SessionService.MODE_DESKTOP
        }
        onScreenButtonsVisible = show
        if (show == (controls.visibility == View.VISIBLE)) return
        if (!show) controls.releaseAll()
        controls.visibility = if (show) View.VISIBLE else View.GONE
        Log.i(TAG, "on-screen controls " + when {
            !show -> "hidden"
            buttonsOnly -> "Steam + QAM"
            else -> "full pad"
        })
    }

    private fun sendGuestKey(code: Int, down: Boolean) {
        if (down && pipUi) return
        if (down) guestKeysDown.add(code) else guestKeysDown.remove(code)
        WaylandCompositor.nativeSendKey(code, if (down) 1 else 0)
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
            pendingBackAction?.let(uiHandler::removeCallbacks)
            pendingBackAction = null
            releaseDrawerDirection()
            onScreenControls?.releaseAll()
            padBridge?.releaseAll()
            touchpad.cancel()
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
        // The panel may have changed while the session was a thumbnail (folded or unfolded in PiP).
        if (!inPip && surfaceW > 0) followWindow(surfaceW, surfaceH)
    }

    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        if (::pip.isInitialized && (requestCode >= 0 || intent.component?.packageName == packageName ||
                intent.action in setOf(Intent.ACTION_CHOOSER, Intent.ACTION_SEND, Intent.ACTION_OPEN_DOCUMENT,
                    Intent.ACTION_GET_CONTENT, "android.settings.PICTURE_IN_PICTURE_SETTINGS"))) pip.background()
        super.startActivityForResult(intent, requestCode, options)
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────────────────────

    override fun onStart() {
        super.onStart()
        if (runtimeRemovalBlocked) return
        started = true
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        refreshSecondScreenDisplays()
        if (secondScreenMode != SecondScreenMode.NONE && secondScreenPresentation == null) showSecondScreen(secondScreenMode)
        SessionService.setActivityVisible(this, true)
    }

    override fun onStop() {
        if (runtimeRemovalBlocked) { super.onStop(); return }
        started = false
        displayManager.unregisterDisplayListener(displayListener)
        // Closed while the session is out of sight (sleep, a closed lid, another app), and kept:
        // onStart puts it back.
        closeSecondScreen(reset = false)
        SessionService.setActivityVisible(this, false)
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
        // Home/notification resumes must retain the external frontend's return destination.
        if (this.intent.getBooleanExtra(EXTRA_RETURN_HOME, false)) intent.putExtra(EXTRA_RETURN_HOME, true)
        setIntent(intent)
        if (intent.action == SessionService.ACTION_HOME_GUIDE) {
            handleHomeGuideIntent(intent)
        } else if (intent.action == SessionService.ACTION_RESUME) {
            intent.action = null
            SessionService.resume(this)
        }
    }

    override fun onResume() {
        super.onResume()
        if (runtimeRemovalBlocked) return
        com.droiddeck.launcher.ui.Motion.refresh(this)
        refreshHomeApp()
        if (intent?.action == SessionService.ACTION_RESUME) {
            intent.action = null
            SessionService.resume(this)
        }
        (getSystemService(INPUT_SERVICE) as? InputManager)
            ?.registerInputDeviceListener(deviceListener, Handler(Looper.getMainLooper()))
        onScreenControls?.reload()
        controllerSettings = ControllerPrefs.read(this)
        updateOnScreenControls()
        resumed = true
        updatePointerCapture()
        if (!pipUi) sessionClipboard.start()
        updatePadMotion()
        readPrefs()
        if (::pip.isInitialized) pip.resumed()
        if (CompositorHost.isStarted) applyFrameGen()
    }

    override fun onPause() {
        if (runtimeRemovalBlocked) { super.onPause(); return }
        sessionClipboard.stop()
        // Do not carry transient session UI across an app/display transition. In particular, the
        // drawer's dim layer can otherwise remain over Steam when this activity returns.
        drawerOpen = false
        pcKeyboardOpen = false
        (getSystemService(INPUT_SERVICE) as? InputManager)?.unregisterInputDeviceListener(deviceListener)
        releaseDrawerDirection()
        // A button held when the app goes away would stay held in the ring for the whole session.
        onScreenControls?.releaseAll()
        resumed = false
        updatePointerCapture()
        updatePadMotion()
        keyboard?.takeIf { it.shown }?.hide()
        super.onPause()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val decor = window.decorView
        decor.requestUnbufferedDispatch(UNBUFFERED_SOURCES)
        decor.viewTreeObserver.addOnGlobalFocusChangeListener { _, _ -> decor.post { decor.requestUnbufferedDispatch(UNBUFFERED_SOURCES) } }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (runtimeRemovalBlocked) return
        if (hasFocus && !pipUi) {
            goFullscreen()
            refreshClipboard()
        }
        // Android drops a capture with the focus; a lock still held takes it again on return.
        updatePointerCapture()
    }

    /** The keyboard/trackpad presentation can own focus instead of the main display. */
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
        // ALWAYS from Android 11: SHORT_EDGES left a black strip along a camera on the long edge,
        // which is where a Fold's inner-screen camera sits with the device held in landscape.
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    /** Ours, so a finishing activity never unhooks the one that replaced it. */
    private val endListener: (Int) -> Unit = { status -> onSessionEnded(status) }
    private val firstFrameListener = Runnable {
        SessionEvents.firstFrame()
        if (SessionState.mode == SessionService.MODE_STEAM) SteamRepair.clientShown(this)
        runOnUiThread {
            loading.visible = false
            hud.start()
        }
    }

    override fun onDestroy() {
        if (runtimeRemovalBlocked) { super.onDestroy(); return }
        // Deliberately does NOT end the session: this activity can be destroyed while the user is
        // in another app, and the whole point of the service is that Steam survives that.
        watching = false
        releaseDrawerDirection()
        pendingBackAction?.let(uiHandler::removeCallbacks)
        pendingBackAction = null
        if (::pip.isInitialized) pip.destroyed()
        closeSecondScreen(reset = false)
        if (::hud.isInitialized) hud.stop()
        padBridge?.stop()
        if (SessionState.padBridge === padBridge) SessionState.padBridge = null
        padMotion?.stop()
        if (SessionState.deckPadListener === deckPadListener) SessionState.deckPadListener = null
        if (SessionState.endListener === endListener) SessionState.endListener = null
        WaylandCompositor.clearFirstFrameListener(firstFrameListener)
        WaylandCompositor.clearPointerLockListener(pointerLockListener)
        super.onDestroy()
    }

    /** The screen is all blue: the session stops and the front end opens on the same blue (FloodReturn). */
    private fun finishFlooded() {
        if (isFinishing) return
        val signal = com.droiddeck.launcher.ui.Themes.byId(SessionPrefs.theme(this)).signal
        com.droiddeck.launcher.ui.QuitFlood.mark(signal.toArgb())
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(signal.toArgb()))
        quitFlooded = true
        SessionService.stop(this)
        finish()
    }

    /**
     * Every way out on screen (backing out of the loading screen, closing the ended screen, the
     * session ending) leaves as a session arrives, in reverse: the blue spreads out of the
     * throbber's ball, or the middle of the screen once a game is up, and the front end opens on it
     * and draws it back into the button the session came from. Off screen, or with animations
     * off, it just closes.
     */
    override fun finish() {
        if (isFinishing) return
        if (!quitFlooded && !closeAtOnce && com.droiddeck.launcher.ui.Motion.scale != 0f &&
            lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            if (!leaving) {
                leaving = true
                // Should the flood never get its frames, the session still closes.
                uiHandler.postDelayed({ completeLeave() }, LEAVE_TIMEOUT_MS)
            }
            return
        }
        super.finish()
        if (!closeAtOnce && intent.getBooleanExtra(EXTRA_RETURN_HOME, false)) {
            com.droiddeck.launcher.ui.QuitFlood.take()
            com.droiddeck.launcher.ui.LaunchOrigin.flooding = null
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        if (quitFlooded) overridePendingTransition(0, 0)
        else overridePendingTransition(R.anim.session_hold, R.anim.session_sink)
    }

    /** The screen is all blue: close onto the front end, which opens on the same blue. */
    private fun completeLeave() {
        if (isFinishing) return
        val signal = com.droiddeck.launcher.ui.Themes.byId(SessionPrefs.theme(this)).signal
        com.droiddeck.launcher.ui.QuitFlood.mark(signal.toArgb())
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(signal.toArgb()))
        quitFlooded = true
        finish()
    }

    companion object {
        const val EXTRA_RETURN_HOME = "returnHome"
        /** How long the leaving flood may take before the session closes without it. */
        private const val LEAVE_TIMEOUT_MS = 1_500L
        private const val TAG = "SessionActivity"
        private const val FEX_MISSING = "droiddeck-fex: x86 Linux programs need"
        private const val UNBUFFERED_SOURCES = InputDevice.SOURCE_CLASS_JOYSTICK or InputDevice.SOURCE_CLASS_TRACKBALL or InputDevice.SOURCE_CLASS_POSITION
        private const val CURSOR_PAD_HOLD_MS = 1200L
        private const val DRAWER_HAT_THRESHOLD = 0.5f
        private const val DRAWER_STICK_ENTER_THRESHOLD = 0.55f
        private const val DRAWER_STICK_EXIT_THRESHOLD = 0.35f
        private const val DRAWER_DIRECTION_MARGIN = 1.15f
        private const val DRAWER_ACTIVE_DIRECTION_MARGIN = 0.85f
        private const val DRAWER_SLOW_REPEAT_FLOOR_MS = 240L
        private const val DRAWER_FAST_REPEAT_INTERVAL_MS = 90L
        private const val DRAWER_REPEAT_ACCELERATION_MS = 1_400L
        /** Compositor scale modes (Container.FULLSCREEN_* values): 1 = fit with bars, centred. */
        private const val SCALE_FIT = 1
        private const val ALIGN_CENTER = 0
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
