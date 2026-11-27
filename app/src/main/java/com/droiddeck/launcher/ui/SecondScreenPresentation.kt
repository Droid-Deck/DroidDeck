package com.droiddeck.launcher.ui

import com.droiddeck.launcher.R
import android.app.Presentation
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Tune
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.droiddeck.launcher.input.DeckPanel
import com.droiddeck.launcher.input.KeyboardHost
import com.droiddeck.launcher.input.PadBridge
import com.droiddeck.launcher.input.SecondScreenMode
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.session.BottomDesktop
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState

class SecondScreenPresentation(
    private val sessionActivity: SessionActivity,
    display: Display,
    private val onSteamMenu: () -> Unit,
    private val onQam: () -> Unit,
    private val onClose: () -> Unit,
    private val pad: PadBridge? = null,
    private val onKeyboardClosed: () -> Unit = {},
    private val onMenu: (() -> Unit)? = null,
    private val menu: (@androidx.compose.runtime.Composable () -> Unit)? = null,
    private val dashboard: (@androidx.compose.runtime.Composable () -> Unit)? = null,
    private val onSwap: (() -> Unit)? = null,
    private val onMenuTouched: () -> Unit = {},
    private val onDeckPanel: (DeckPanel) -> Unit = { SessionPrefs.setDeckPanel(sessionActivity, it) },
    private val picker: ((SecondScreenMode) -> ScreenPicker?)? = null,
    private val panel: (SecondScreenMode) -> (@androidx.compose.runtime.Composable () -> Unit)? = { null },
) : Presentation(sessionActivity, display) {
    private val frame = FrameLayout(context)
    private var menuView: androidx.compose.ui.platform.ComposeView? = null
    private var dashboardView: androidx.compose.ui.platform.ComposeView? = null
    private var chatView: androidx.compose.ui.platform.ComposeView? = null
    private var chatLost: (() -> Unit)? = null
    var menuShown = false
        private set
    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.BLACK)
        isFocusableInTouchMode = true
    }
    private var currentMode = SecondScreenMode.NONE
    private var keyboardHost: KeyboardHost? = null
    private val keyboardShown = androidx.compose.runtime.mutableStateOf(false)
    private val deckPanel = androidx.compose.runtime.mutableStateOf(DeckPanel.BOTH)
    private var keyboardPending = false
    private var imeSeen = false
    private var fieldKeyboard = false
    private var terminalPanel: SessionTerminalPanel? = null
    var controlsView: InputSurfaceView? = null
        private set
    private var created = false
    private val brightnessOpen = androidx.compose.runtime.mutableStateOf(false)
    private val brightness = androidx.compose.runtime.mutableIntStateOf(BottomBrightness.MIN)
    private val brightnessView by lazy {
        androidx.compose.ui.platform.ComposeView(context).apply {
            setContent {
                DroidDeckTheme {
                    if (!BottomChrome.barShown && currentMode.hasBar) PullTab()
                    if (brightnessOpen.value) BrightnessDrop(brightness.intValue, ::setBrightness,
                        onScreenOff = { brightnessOpen.value = false; screenOff() }, onDismiss = { brightnessOpen.value = false })
                }
            }
        }
    }

    init {
        setCancelable(false)
        setCanceledOnTouchOutside(false)
        setOnDismissListener { closeTerminal() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        created = true
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        frame.addView(root, FrameLayout.LayoutParams(-1, -1))
        frame.addView(brightnessView, FrameLayout.LayoutParams(-1, -1))
        frame.addView(sleepView, FrameLayout.LayoutParams(-1, -1))
        light(BottomScreenLight.ON)
        setContentView(frame)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                if (insets.isVisible(WindowInsets.Type.ime())) imeSeen = true
                else if (imeSeen && chatView == null) keyboardClosed()
                view.onApplyWindowInsets(insets)
            }
        }
        applyImmersiveMode()
        renderMode()
    }

    fun requestKeyboard(forField: Boolean = false) {
        if (!showsKeyboard || keyboardHost == null) return
        wake()
        if (forField) fieldKeyboard = true
        keyboardPending = true
        if (window?.decorView?.hasWindowFocus() == true) showPendingKeyboard()
    }

    private fun showPendingKeyboard() {
        keyboardPending = false
        keyboardShown.value = true
        val host = keyboardHost ?: return
        for (delay in longArrayOf(150L, 450L)) root.postDelayed({
            if (keyboardHost === host && keyboardShown.value && !imeSeen && showsKeyboard) host.show()
        }, delay)
    }

    val fieldKeyboardOpen: Boolean get() = fieldKeyboard

    fun closeFieldKeyboard() {
        if (!fieldKeyboard) return
        keyboardPending = false
        keyboardClosed()
    }

    private fun keyboardClosed() {
        fieldKeyboard = false
        imeSeen = false
        keyboardHost?.takeIf { it.shown }?.hide()
        keyboardShown.value = false
        onKeyboardClosed()
    }

    fun showMode(mode: SecondScreenMode) {
        if (currentMode == mode && isShowing) return
        currentMode = mode
        brightnessOpen.value = false
        BottomChrome.reset()
        if (created) renderMode()
    }

    fun retryTerminal() {
        if (created && isShowing && currentMode == SecondScreenMode.TERMINAL && terminalPanel?.ready != true) renderMode()
    }

    fun showChat(onLost: () -> Unit, content: @androidx.compose.runtime.Composable () -> Unit) {
        if (!showsKeyboard || chatView != null) return
        wake()
        keyboardHost?.takeIf { it.shown }?.hide()
        keyboardShown.value = false
        keyboardPending = false
        fieldKeyboard = false
        chatLost = onLost
        ownComposeViews()
        val view = androidx.compose.ui.platform.ComposeView(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setContent { PresentationWindows(content) }
        }
        chatView = view
        root.addView(view, 1.coerceAtMost(root.childCount), LinearLayout.LayoutParams(-1, -2))
    }

    fun hideChat() {
        chatLost = null
        dropChat()
    }

    private fun dropChat() {
        val view = chatView ?: return
        chatView = null
        chatLost?.let { lost -> chatLost = null; sessionActivity.runOnUiThread(lost) }
        imeSeen = false
        root.removeView(view)
        view.disposeComposition()
    }

    val showsKeyboard: Boolean get() = isShowing && !menuShown && currentMode.controls

    val showsControls: Boolean get() = isShowing && currentMode != SecondScreenMode.NONE

    fun showMenu(on: Boolean) {
        if (on == menuShown) return
        val content = menu ?: return
        menuShown = on
        if (on) {
            wake()
            keyboardHost?.takeIf { it.shown }?.hide()
            keyboardShown.value = false
            ownComposeViews()
            val view = menuView ?: androidx.compose.ui.platform.ComposeView(context).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                defaultFocusHighlightEnabled = false
                setContent { PresentationWindows(content) }
                frame.addView(this, FrameLayout.LayoutParams(-1, -1))
                menuView = this
            }
            view.visibility = View.VISIBLE
            root.visibility = View.INVISIBLE
            view.requestFocus()
        } else {
            menuView?.let { frame.removeView(it); it.disposeComposition() }
            menuView = null
            root.visibility = View.VISIBLE
        }
    }

    private fun ownComposeViews() {
        window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(sessionActivity)
            decor.setViewTreeViewModelStoreOwner(sessionActivity)
            decor.setViewTreeSavedStateRegistryOwner(sessionActivity)
            decor.setViewTreeOnBackPressedDispatcherOwner(sessionActivity)
        }
    }

    fun focusMenu(): Boolean = menuView?.requestFocus() == true

    fun dispatchMenuKey(event: KeyEvent): Boolean {
        KeyWindows.topOn(display.displayId)?.let { return it.rootView.dispatchKeyEvent(event) }
        return menuView?.dispatchKeyEvent(event) == true
    }

    fun closeControls() {
        showMenu(false)
        closeDashboard()
        closeTerminal()
        dropChat()
        keyboardHost?.hide()
        keyboardHost = null
        controlsView = null
        dismiss()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val ownKeys = currentMode == SecondScreenMode.TERMINAL || currentMode == SecondScreenMode.DASHBOARD || currentMode.sessionPanel ||
            currentMode == SecondScreenMode.DESKTOP
        val back = event.keyCode == KeyEvent.KEYCODE_BACK && currentMode == SecondScreenMode.DASHBOARD
        val pad = PadBridge.isControllerKeyEvent(event)
        if (!pad) uiActivity()
        val forward = menuShown || !ownKeys || back || pad
        if (forward && sessionActivity.dispatchKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && screen == BottomScreenLight.ON) waking = false
        if (screen != BottomScreenLight.ON || waking) {
            // The touch that wakes the screen is swallowed whole, so its release does not click what is under it.
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (screen != BottomScreenLight.ON) { waking = true; wake() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> waking = false
            }
            return true
        }
        scheduleDim()
        if (menuShown && event.actionMasked == MotionEvent.ACTION_DOWN) onMenuTouched()
        return super.dispatchTouchEvent(event)
    }

    private val sleepView = View(context).apply {
        setBackgroundColor(Color.BLACK)
        visibility = View.GONE
    }
    private var screen = BottomScreenLight.ON
    private var waking = false
    private val dim = Runnable { if (screen == BottomScreenLight.ON) light(BottomScreenLight.DIM) }

    fun screenOff() {
        // A control held by another finger would keep its press, since the screen-off overlay takes the release.
        controlsView?.releaseInputs()
        light(BottomScreenLight.OFF)
    }

    /** Keyboard or mouse use of the bottom screen's own UI counts as activity; an explicit screen off stays off. */
    private fun uiActivity() {
        when (screen) {
            BottomScreenLight.DIM -> wake()
            BottomScreenLight.ON -> scheduleDim()
            BottomScreenLight.OFF -> Unit
        }
    }

    fun wake() {
        light(BottomScreenLight.ON)
        scheduleDim()
    }

    private fun light(state: BottomScreenLight) {
        screen = state
        sleepView.visibility = if (state == BottomScreenLight.OFF) View.VISIBLE else View.GONE
        sleepView.alpha = 1f
        val w = window ?: return
        w.attributes = w.attributes.apply {
            screenBrightness = when (state) {
                BottomScreenLight.ON -> BottomBrightness.level(context) ?: state.brightness
                BottomScreenLight.DIM -> BottomBrightness.dimmed(context, state.brightness)
                BottomScreenLight.OFF -> state.brightness
            }
        }
    }

    private fun toggleBrightness() {
        if (!brightnessOpen.value) brightness.intValue = BottomBrightness.initial(context)
        brightnessOpen.value = !brightnessOpen.value
    }

    private fun setBrightness(percent: Int) {
        val value = BottomBrightness.clamp(percent)
        brightness.intValue = value
        SessionPrefs.setBottomBrightness(context, value)
        if (screen == BottomScreenLight.ON) light(BottomScreenLight.ON)
    }

    private fun scheduleDim() {
        frame.removeCallbacks(dim)
        if (BottomScreenLight.autoDims(currentMode) && SessionPrefs.bottomAutoDim(context)) frame.postDelayed(dim, BottomScreenLight.DIM_AFTER_MS)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (PadBridge.isControllerMotionEvent(event) && sessionActivity.dispatchGenericMotionEvent(event)) return true
        if (event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) uiActivity()
        return super.dispatchGenericMotionEvent(event)
    }

    fun refreshControls() {
        if (!created || !currentMode.controls) return
        val controls = controlsView ?: return
        val layout = panelLayout(currentMode)
        controls.updateCustomLayout(layout?.elements.orEmpty())
        controls.updateStyle(layout?.style ?: com.droiddeck.launcher.input.InputSurfaceStyle())
    }

    fun showKeyboard(): Boolean {
        val host = keyboardHost ?: return false
        host.toggle()
        keyboardShown.value = host.shown
        return true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveMode()
            sessionActivity.refreshClipboard()
            if (keyboardPending) showPendingKeyboard()
        }
    }

    private fun renderMode() {
        if (created) wake()
        dropChat()
        fieldKeyboard = false
        keyboardHost?.hide()
        keyboardHost = null
        keyboardShown.value = false
        keyboardPending = false
        imeSeen = false
        closeTerminal()
        closeDashboard()
        root.removeAllViews()
        controlsView = null
        ownComposeViews()
        val requested = currentMode
        val mode = when {
            requested == SecondScreenMode.DECK_CONTROLS && !SessionState.deckPad -> SecondScreenMode.NONE
            requested == SecondScreenMode.DASHBOARD && dashboard == null -> SecondScreenMode.NONE
            requested.sessionPanel && panel(requested) == null -> SecondScreenMode.NONE
            requested == SecondScreenMode.DESKTOP && !BottomDesktop.available() -> SecondScreenMode.NONE
            else -> requested
        }
        if (mode == SecondScreenMode.NONE) return

        when (mode) {
            SecondScreenMode.TERMINAL -> renderTerminal()
            SecondScreenMode.CUSTOM_CONTROLS, SecondScreenMode.KEYBOARD_TRACKPAD, SecondScreenMode.DECK_CONTROLS -> {
                if (mode == SecondScreenMode.DECK_CONTROLS) deckPanel.value = SessionPrefs.deckPanel(context)
                bar(mode)
                val layout = panelLayout(mode)
                val controls = InputSurfaceView(context, pad, layout?.elements.orEmpty(),
                    surfaceStyle = layout?.style ?: com.droiddeck.launcher.input.InputSurfaceStyle())
                controlsView = controls
                root.addView(controls, weightParams())
                attachKeyboard()
            }
            SecondScreenMode.DASHBOARD -> renderDashboard()
            SecondScreenMode.GUIDES, SecondScreenMode.MIRROR, SecondScreenMode.STEAM_QAM, SecondScreenMode.ART -> renderPanel(mode)
            SecondScreenMode.DESKTOP -> {
                root.addView(BottomDesktopPanel(context, onClose, onMenu,
                    (onSteamMenu to onQam).takeIf { SessionState.mode == SessionService.MODE_STEAM }, onSwap,
                    picker = picker?.let { pick -> { pick(SecondScreenMode.DESKTOP) } }), weightParams())
            }
            SecondScreenMode.NONE, SecondScreenMode.MENU -> Unit
        }
    }

    private fun panelLayout(mode: SecondScreenMode) = com.droiddeck.launcher.input.InputLibraries.read(context)
        .panelLayout(SessionState.mode == SessionService.MODE_DESKTOP, mode.panel(context))

    private fun bar(mode: SecondScreenMode) {
        val first = barModel(mode) ?: return
        root.addView(androidx.compose.ui.platform.ComposeView(context).apply {
            setContent {
                DroidDeckTheme {
                    androidx.compose.animation.AnimatedVisibility(BottomChrome.barShown,
                        enter = androidx.compose.animation.expandVertically(), exit = androidx.compose.animation.shrinkVertically()) {
                        BottomScreenBar(barModel(mode) ?: first)
                    }
                }
            }
        }, LinearLayout.LayoutParams(-1, -2))
    }

    internal fun barModel(mode: SecondScreenMode): BottomBarModel? {
        val extras = listOfNotNull(onSwap?.let { BarExtra(context.getString(R.string.swap_screens), icon = R.drawable.ic_bottom_swap, tag = "bottom-swap", onClick = it) }) +
            BarExtra(context.getString(R.string.bottom_brightness), vector = Icons.Outlined.LightMode, active = brightnessOpen.value,
                tag = "bottom-brightness-toggle", onClick = ::toggleBrightness) +
            BarExtra(context.getString(R.string.bottom_bar_hide), vector = Icons.Outlined.ExpandLess, tag = "bottom-bar-hide") {
                brightnessOpen.value = false
                BottomChrome.show(false)
            }
        fun model(extras: List<BarExtra>, tabs: BarTabs? = null) =
            sessionBarModel(context.getString(mode.label), onClose, onMenu, onSteamMenu, onQam, extras, tabs, picker?.invoke(mode))
        return when (mode) {
            SecondScreenMode.KEYBOARD_TRACKPAD -> model(listOf(BarExtra(
                context.getString(if (keyboardShown.value) R.string.second_hide_keyboard else R.string.second_show_keyboard),
                icon = R.drawable.ic_bottom_keyboard, active = keyboardShown.value, tag = "bottom-keyboard") { showKeyboard() }) + extras)
            SecondScreenMode.DECK_CONTROLS -> model(extras, deckPanelTabs(context, deckPanel.value, ::selectDeckPanel))
            SecondScreenMode.STEAM_QAM -> {
                val fit = QamFit.current(context)
                model(listOf(BarExtra(context.getString(R.string.bottom_qam_layout, context.getString(fit.label)), vector = fit.icon,
                    tag = "bottom-qam-layout") { QamFit.choose(context, fit.next) }) + extras)
            }
            SecondScreenMode.MIRROR -> model(listOf(BarExtra(context.getString(R.string.bottom_mirror_options), vector = Icons.Outlined.Tune,
                active = MirrorOptions.open.value, tag = "bottom-mirror-options-toggle") {
                brightnessOpen.value = false
                MirrorOptions.open.value = !MirrorOptions.open.value
            }) + extras)
            SecondScreenMode.DASHBOARD, SecondScreenMode.CUSTOM_CONTROLS, SecondScreenMode.GUIDES,
            SecondScreenMode.ART -> model(extras)
            SecondScreenMode.TERMINAL -> model(listOf(BarExtra(context.getString(R.string.drawer_keyboard), icon = R.drawable.ic_bottom_keyboard,
                tag = "bottom-keyboard") { terminalPanel?.showKeyboard() }) + extras)
            else -> null
        }
    }

    private fun selectDeckPanel(panel: DeckPanel) {
        if (deckPanel.value == panel) return
        deckPanel.value = panel
        onDeckPanel(panel)
        refreshControls()
    }

    private fun renderDashboard() {
        val content = dashboard ?: return
        bar(SecondScreenMode.DASHBOARD)
        val view = androidx.compose.ui.platform.ComposeView(context).apply { setContent { PresentationWindows(content) } }
        dashboardView = view
        root.addView(view, weightParams())
    }

    private fun renderPanel(mode: SecondScreenMode) {
        val content = panel(mode) ?: return
        bar(mode)
        val view = androidx.compose.ui.platform.ComposeView(context).apply { setContent { PresentationWindows(content) } }
        dashboardView = view
        root.addView(view, weightParams())
    }

    private fun closeDashboard() {
        dashboardView?.disposeComposition()
        dashboardView = null
    }

    private fun attachKeyboard() {
        val host = KeyboardHost(context) { event ->
            val handled = sessionActivity.dispatchKeyEvent(event)
            if (fieldKeyboard && event.action == KeyEvent.ACTION_UP &&
                (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) root.post { closeFieldKeyboard() }
            handled
        }
        host.onImeChanged = { visible -> if (!visible && keyboardShown.value) keyboardClosed() }
        keyboardHost = host
        root.addView(host, ViewGroup.LayoutParams(1, 1))
    }

    private fun renderTerminal() {
        bar(SecondScreenMode.TERMINAL)
        val panel = SessionTerminalPanel(context, keepShell = true)
        terminalPanel = panel
        root.addView(panel, weightParams())
    }

    private fun closeTerminal() {
        terminalPanel?.close()
        terminalPanel = null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun weightParams() = LinearLayout.LayoutParams(-1, 0, 1f)

    private fun applyImmersiveMode() {
        @Suppress("DEPRECATION")
        window?.decorView?.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }
}

@androidx.compose.runtime.Composable
private fun PresentationWindows(content: @androidx.compose.runtime.Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalPresentationWindow provides true) { content() }
}

internal enum class BottomScreenLight(val brightness: Float) {
    ON(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE),
    DIM(0.05f),
    OFF(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF);

    companion object {
        const val DIM_AFTER_MS = 60_000L
        fun autoDims(mode: SecondScreenMode) = mode == SecondScreenMode.DASHBOARD
    }
}

/** Modes drawn with the bottom bar, which the pull tab can bring back. */
internal val SecondScreenMode.hasBar: Boolean get() = this != SecondScreenMode.NONE && this != SecondScreenMode.MENU && this != SecondScreenMode.DESKTOP
