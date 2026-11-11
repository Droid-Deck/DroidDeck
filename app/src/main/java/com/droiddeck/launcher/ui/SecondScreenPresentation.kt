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
    private var controlsView: InputSurfaceView? = null
    private var created = false

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
        if (created) renderMode()
    }

    fun retryTerminal() {
        if (created && isShowing && currentMode == SecondScreenMode.TERMINAL && terminalPanel?.ready != true) renderMode()
    }

    fun showChat(onLost: () -> Unit, content: @androidx.compose.runtime.Composable () -> Unit) {
        if (!showsKeyboard || chatView != null) return
        keyboardHost?.takeIf { it.shown }?.hide()
        keyboardShown.value = false
        keyboardPending = false
        fieldKeyboard = false
        chatLost = onLost
        ownComposeViews()
        val view = androidx.compose.ui.platform.ComposeView(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setContent { content() }
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
            keyboardHost?.takeIf { it.shown }?.hide()
            keyboardShown.value = false
            ownComposeViews()
            val view = menuView ?: androidx.compose.ui.platform.ComposeView(context).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                defaultFocusHighlightEnabled = false
                setContent { content() }
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
        val ownKeys = currentMode == SecondScreenMode.TERMINAL || currentMode == SecondScreenMode.DASHBOARD ||
            currentMode == SecondScreenMode.DESKTOP
        val back = event.keyCode == KeyEvent.KEYCODE_BACK && currentMode == SecondScreenMode.DASHBOARD
        val forward = menuShown || !ownKeys || back || PadBridge.isControllerKeyEvent(event)
        if (forward && sessionActivity.dispatchKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (menuShown && event.actionMasked == MotionEvent.ACTION_DOWN) onMenuTouched()
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (PadBridge.isControllerMotionEvent(event) && sessionActivity.dispatchGenericMotionEvent(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    fun shownControlElements() = controlsView?.takeIf { it.visibility == View.VISIBLE }?.shownElements().orEmpty()

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
            SecondScreenMode.DESKTOP -> {
                root.addView(BottomDesktopPanel(context, onClose, onMenu,
                    (onSteamMenu to onQam).takeIf { SessionState.mode == SessionService.MODE_STEAM }, onSwap), weightParams())
            }
            SecondScreenMode.NONE -> Unit
        }
    }

    private fun panelLayout(mode: SecondScreenMode) = com.droiddeck.launcher.input.InputLibraries.read(context)
        .panelLayout(SessionState.mode == SessionService.MODE_DESKTOP, mode.panel(context))

    private fun bar(mode: SecondScreenMode) {
        val first = barModel(mode) ?: return
        root.addView(bottomScreenBarView(context) { barModel(mode) ?: first }, LinearLayout.LayoutParams(-1, -2))
    }

    internal fun barModel(mode: SecondScreenMode): BottomBarModel? {
        val swap = listOfNotNull(onSwap?.let { BarExtra(context.getString(R.string.swap_screens), icon = R.drawable.ic_bottom_swap, tag = "bottom-swap", onClick = it) })
        fun model(extras: List<BarExtra>, tabs: BarTabs? = null) =
            sessionBarModel(context.getString(mode.label), onClose, onMenu, onSteamMenu, onQam, extras, tabs)
        return when (mode) {
            SecondScreenMode.KEYBOARD_TRACKPAD -> model(listOf(BarExtra(
                context.getString(if (keyboardShown.value) R.string.second_hide_keyboard else R.string.second_show_keyboard),
                icon = R.drawable.ic_bottom_keyboard, active = keyboardShown.value, tag = "bottom-keyboard") { showKeyboard() }))
            SecondScreenMode.DECK_CONTROLS -> model(swap, deckPanelTabs(context, deckPanel.value, ::selectDeckPanel))
            SecondScreenMode.DASHBOARD, SecondScreenMode.CUSTOM_CONTROLS -> model(swap)
            SecondScreenMode.TERMINAL -> model(listOf(BarExtra(context.getString(R.string.drawer_keyboard), icon = R.drawable.ic_bottom_keyboard,
                tag = "bottom-keyboard") { terminalPanel?.showKeyboard() }))
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
        val view = androidx.compose.ui.platform.ComposeView(context).apply { setContent { content() } }
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
        val panel = SessionTerminalPanel(context)
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
