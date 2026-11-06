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
import com.droiddeck.launcher.input.KeyboardHost
import com.droiddeck.launcher.input.PadBridge
import com.droiddeck.launcher.input.SecondScreenMode
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.session.BottomDesktop
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
) : Presentation(sessionActivity, display) {
    private val frame = FrameLayout(context)
    private var menuView: androidx.compose.ui.platform.ComposeView? = null
    private var dashboardView: androidx.compose.ui.platform.ComposeView? = null
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
    private var keyboardPending = false
    private var imeSeen = false
    private var fieldKeyboard = false
    private var terminalPanel: SessionTerminalPanel? = null
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
                else if (imeSeen) keyboardClosed()
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
        val next = if (mode == SecondScreenMode.KEYBOARD_TRACKPAD || mode == SecondScreenMode.DECK_CONTROLS) SecondScreenMode.CUSTOM_CONTROLS else mode
        if (currentMode == next && isShowing) return
        currentMode = next
        if (created) renderMode()
    }

    fun retryTerminal() {
        if (created && isShowing && currentMode == SecondScreenMode.TERMINAL && terminalPanel?.ready != true) renderMode()
    }

    val showsKeyboard: Boolean get() = isShowing && !menuShown && currentMode == SecondScreenMode.CUSTOM_CONTROLS

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
        keyboardHost?.hide()
        keyboardHost = null
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

    fun refreshControls() { if (created) renderMode() }

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
        fieldKeyboard = false
        keyboardHost?.hide()
        keyboardHost = null
        keyboardShown.value = false
        keyboardPending = false
        imeSeen = false
        closeTerminal()
        closeDashboard()
        root.removeAllViews()
        ownComposeViews()
        val library = com.droiddeck.launcher.input.InputLibraries.read(context)
        val profileLayout = library.activeLayout(SessionState.mode == SessionService.MODE_DESKTOP, com.droiddeck.launcher.input.InputRole.SECONDARY)
        val requested = currentMode
        val mode = when {
            requested == SecondScreenMode.DASHBOARD && dashboard == null -> SecondScreenMode.NONE
            requested == SecondScreenMode.DESKTOP && !BottomDesktop.available() -> SecondScreenMode.NONE
            else -> requested
        }
        if (mode == SecondScreenMode.NONE) return

        when (mode) {
            SecondScreenMode.TERMINAL -> renderTerminal()
            SecondScreenMode.CUSTOM_CONTROLS -> {
                root.addView(InputSurfaceView(context, pad, profileLayout?.elements.orEmpty(),
                    surfaceStyle = profileLayout?.style ?: com.droiddeck.launcher.input.InputSurfaceStyle()), weightParams())
                attachKeyboard()
            }
            SecondScreenMode.KEYBOARD_TRACKPAD, SecondScreenMode.DECK_CONTROLS -> Unit
            SecondScreenMode.DASHBOARD -> renderDashboard()
            SecondScreenMode.DESKTOP -> {
                root.addView(BottomDesktopPanel(context, onClose, onMenu,
                    (onSteamMenu to onQam).takeIf { SessionState.mode == SessionService.MODE_STEAM }, onSwap), weightParams())
            }
            SecondScreenMode.NONE -> Unit
        }
    }

    private fun bar(mode: SecondScreenMode, extras: () -> List<BarExtra> = { emptyList() }) {
        root.addView(bottomScreenBarView(context) {
            sessionBarModel(context.getString(mode.label), onClose, onMenu, onSteamMenu, onQam, extras())
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun renderDashboard() {
        val content = dashboard ?: return
        bar(SecondScreenMode.DASHBOARD) {
            listOfNotNull(onSwap?.let { BarExtra(context.getString(R.string.swap_screens), icon = R.drawable.ic_bottom_swap, tag = "bottom-swap", onClick = it) })
        }
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
        bar(SecondScreenMode.TERMINAL) {
            listOf(BarExtra(context.getString(R.string.drawer_keyboard), icon = R.drawable.ic_bottom_keyboard, tag = "bottom-keyboard") {
                terminalPanel?.showKeyboard()
            })
        }
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
