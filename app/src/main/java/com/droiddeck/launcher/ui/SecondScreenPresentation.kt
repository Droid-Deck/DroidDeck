package com.droiddeck.launcher.ui

import com.droiddeck.launcher.R
import android.app.Presentation
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.droiddeck.launcher.input.KeyboardHost
import com.droiddeck.launcher.input.PadBridge
import com.droiddeck.launcher.input.PointerGestures
import com.droiddeck.launcher.input.SecondScreenMode
import com.droiddeck.launcher.input.TouchpadGestures
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.session.SessionTerminal
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

class SecondScreenPresentation(
    private val sessionActivity: SessionActivity,
    display: Display,
    private val sendPointer: (x: Float, y: Float, width: Float, height: Float) -> Unit,
    private val sendButton: (button: Int, pressed: Boolean) -> Unit,
    private val sendWheel: (steps: Int) -> Unit,
    private val onSteamMenu: () -> Unit,
    private val onQam: () -> Unit,
    private val onClose: () -> Unit,
    private val pad: PadBridge? = null,
    private val onKeyboardClosed: () -> Unit = {},
    private val onMenu: (() -> Unit)? = null,
    private val menu: (@androidx.compose.runtime.Composable () -> Unit)? = null,
) : Presentation(sessionActivity, display) {
    private val frame = FrameLayout(context)
    private var menuView: androidx.compose.ui.platform.ComposeView? = null
    var menuShown = false
        private set
    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.rgb(15, 18, 22))
        isFocusableInTouchMode = true
    }
    private var currentMode = SecondScreenMode.NONE
    private var keyboardHost: KeyboardHost? = null
    private var keyboardButton: TextView? = null
    private var keyboardPending = false
    private var imeSeen = false
    private var fieldKeyboard = false
    private var terminalSession: TerminalSession? = null
    private var terminalView: TerminalView? = null
    private var terminalPid = -1
    private var ctrlActive = false
    private var altActive = false
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
        keyboardButton?.text = context.getString(R.string.second_hide_keyboard)
        for (delay in longArrayOf(150L, 450L)) root.postDelayed({
            val host = keyboardHost
            if (host != null && !imeSeen && showsKeyboard) host.show()
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
        keyboardButton?.text = context.getString(R.string.second_show_keyboard)
        onKeyboardClosed()
    }

    fun showMode(mode: SecondScreenMode) {
        if (currentMode == mode && isShowing) return
        currentMode = mode
        if (created) renderMode()
    }

    val showsKeyboard: Boolean get() = isShowing && !menuShown && currentMode == SecondScreenMode.KEYBOARD_TRACKPAD

    val showsControls: Boolean get() = isShowing && currentMode != SecondScreenMode.NONE

    fun showMenu(on: Boolean) {
        if (on == menuShown) return
        val content = menu ?: return
        menuShown = on
        if (on) {
            keyboardHost?.takeIf { it.shown }?.hide()
            window?.decorView?.let { decor ->
                decor.setViewTreeLifecycleOwner(sessionActivity)
                decor.setViewTreeViewModelStoreOwner(sessionActivity)
                decor.setViewTreeSavedStateRegistryOwner(sessionActivity)
                decor.setViewTreeOnBackPressedDispatcherOwner(sessionActivity)
            }
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

    fun focusMenu(): Boolean = menuView?.requestFocus() == true

    fun dispatchMenuKey(event: KeyEvent): Boolean = menuView?.dispatchKeyEvent(event) == true

    fun closeControls() {
        showMenu(false)
        closeTerminal()
        keyboardHost?.hide()
        keyboardHost = null
        dismiss()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val forward = menuShown || currentMode != SecondScreenMode.TERMINAL || PadBridge.isControllerKeyEvent(event)
        if (forward && sessionActivity.dispatchKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (PadBridge.isControllerMotionEvent(event) && sessionActivity.dispatchGenericMotionEvent(event)) return true
        return super.dispatchGenericMotionEvent(event)
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
        keyboardButton = null
        keyboardPending = false
        closeTerminal()
        root.removeAllViews()
        val mode = if (currentMode == SecondScreenMode.DECK_CONTROLS && !SessionState.deckPad) SecondScreenMode.NONE
        else currentMode
        if (mode == SecondScreenMode.NONE) return

        when (mode) {
            SecondScreenMode.KEYBOARD_TRACKPAD -> renderKeyboardAndTrackpad()
            SecondScreenMode.TERMINAL -> renderTerminal()
            SecondScreenMode.DECK_CONTROLS -> root.addView(DeckControlsPanel(context, onClose, onSteamMenu, onQam, pad, onMenu = onMenu), weightParams())
            SecondScreenMode.NONE -> Unit
        }
    }

    private fun renderKeyboardAndTrackpad() {
        val panel = FrameLayout(context)
        panel.addView(trackpadView(), FrameLayout.LayoutParams(-1, -1))
        val controls = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(6))
            setBackgroundColor(Color.BLACK)
        }
        fun gap() = View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) }
        controls.addView(DeckControlsPanel.roundButton(context, "×", context.getString(R.string.deck_close), onClose))
        controls.addView(TextView(context).apply {
            text = context.getString(R.string.second_trackpad)
            textSize = 14f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            setTextColor(Color.rgb(0x7A, 0x80, 0x8A))
            setPadding(dp(14), 0, dp(8), 0)
            isSingleLine = true
        }, LinearLayout.LayoutParams(0, -2, 1f))
        lateinit var keyboardButton: TextView
        keyboardButton = DeckControlsPanel.roundButton(context, context.getString(R.string.second_show_keyboard),
            context.getString(R.string.second_show_keyboard), {
                val host = keyboardHost
                if (host != null) {
                    host.toggle()
                    keyboardButton.text = context.getString(if (host.shown) R.string.second_hide_keyboard else R.string.second_show_keyboard)
                }
            }, wide = true)
        controls.addView(keyboardButton)
        this.keyboardButton = keyboardButton
        onMenu?.let {
            controls.addView(gap())
            controls.addView(DeckControlsPanel.menuButton(context, it))
        }
        if (SessionState.mode == SessionService.MODE_STEAM) {
            controls.addView(gap())
            controls.addView(DeckControlsPanel.roundButton(context, "STEAM", context.getString(R.string.deck_open_steam_menu), onSteamMenu, wide = true))
            controls.addView(gap())
            controls.addView(DeckControlsPanel.roundButton(context, "•••", context.getString(R.string.drawer_open_qam), onQam, wide = true))
        }
        panel.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        root.addView(panel, weightParams())
        val host = KeyboardHost(context) { event ->
            val handled = sessionActivity.dispatchKeyEvent(event)
            if (fieldKeyboard && event.action == KeyEvent.ACTION_UP &&
                (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) root.post { closeFieldKeyboard() }
            handled
        }
        keyboardHost = host
        root.addView(host, ViewGroup.LayoutParams(1, 1))
    }

    private fun trackpadView(): View = TrackpadView(context)

    private fun renderTerminal() {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(Color.rgb(27, 32, 38))
        }
        bar.addView(Button(context).apply {
            text = "×"
            contentDescription = context.getString(R.string.deck_close)
            minWidth = dp(48)
            setOnClickListener { onClose() }
        })
        bar.addView(TextView(context).apply {
            text = context.getString(R.string.second_terminal)
            textSize = 14f
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(Button(context).apply {
            text = context.getString(R.string.drawer_keyboard)
            setOnClickListener { showTerminalKeyboard() }
        })
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))

        val terminalClient = TerminalClient()
        val view = TerminalView(context, null).apply {
            setTerminalViewClient(terminalClient)
            setTextSize(dp(14))
            isFocusable = true
            isFocusableInTouchMode = true
        }
        terminalView = view
        val session = SessionTerminal.create(terminalClient)
        if (session == null) {
            root.addView(TextView(context).apply {
                text = context.getString(R.string.second_terminal_not_ready)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, 0, 1f))
            return
        }
        terminalSession = session

        val keyStrip = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        val keys = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), dp(2), dp(4), dp(2)) }
        fun key(label: String, input: String, onClick: ((Button) -> Unit)? = null) {
            val button = Button(context).apply {
                text = label
                setOnClickListener { if (onClick != null) onClick(this) else session.write(input) }
            }
            keys.addView(button)
        }
        val ctrl = context.getString(R.string.pckb_key_ctrl)
        val alt = context.getString(R.string.pckb_key_alt)
        key(context.getString(R.string.pckb_key_esc), "\u001b")
        key(context.getString(R.string.second_key_tab), "\t")
        key(ctrl, "") { button ->
            ctrlActive = !ctrlActive
            button.text = if (ctrlActive) context.getString(R.string.second_key_on, ctrl) else ctrl
        }
        key(alt, "") { button ->
            altActive = !altActive
            button.text = if (altActive) context.getString(R.string.second_key_on, alt) else alt
        }
        key("↑", "\u001b[A")
        key("↓", "\u001b[B")
        key("←", "\u001b[D")
        key("→", "\u001b[C")
        key(context.getString(R.string.second_key_ctrl_c), "\u0003")
        key(context.getString(R.string.second_key_ctrl_d), "\u0004")
        keyStrip.addView(keys)
        root.addView(keyStrip, LinearLayout.LayoutParams(-1, -2))

        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        view.attachSession(session)

        view.post {
            registerTerminalPid()
            view.requestFocus()
            showTerminalKeyboard()
        }
    }

    private fun showTerminalKeyboard() {
        terminalView?.let { view ->
            view.requestFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun registerTerminalPid() {
        val pid = terminalSession?.pid ?: -1
        if (pid > 1 && pid != terminalPid) {
            terminalPid = pid
            SessionService.registerTerminalProcess(context, pid)
        }
    }

    private fun closeTerminal() {
        terminalView?.let { view ->
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(view.windowToken, 0)
        }
        val pid = terminalPid.takeIf { it > 1 } ?: (terminalSession?.pid ?: -1)
        if (pid > 1) SessionService.stopTerminalProcess(context, pid)
        terminalPid = -1
        terminalSession = null
        terminalView = null
        ctrlActive = false
        altActive = false
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

    private inner class TrackpadView(context: Context) : View(context) {
        private val gestures = TouchpadGestures(ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
            object : PointerGestures.Listener {
                override fun onMove(x: Float, y: Float) {
                    sendPointer(x, y, width.toFloat(), height.toFloat())
                    invalidate()
                }

                override fun onButton(button: Int, pressed: Boolean, x: Float, y: Float) {
                    sendPointer(x, y, width.toFloat(), height.toFloat())
                    sendButton(button, pressed)
                }

                override fun onWheel(steps: Int) = sendWheel(steps)
                override fun onLongPress() { performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) }
            })

        init { isFocusable = true }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            gestures.bounds = android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat())
            if (oldw == 0 || oldh == 0) gestures.place(w / 2f, h / 2f)
        }

        private val surface = android.graphics.RectF()
        private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x0A, 0x0B, 0x0D) }
        private val edge = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            color = Color.rgb(0x26, 0x29, 0x2E)
            strokeWidth = resources.displayMetrics.density
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.BLACK)
            val inset = dp(12).toFloat()
            surface.set(inset, dp(72).toFloat(), width - inset, height - inset)
            if (surface.height() <= inset) return
            val radius = dp(20).toFloat()
            canvas.drawRoundRect(surface, radius, radius, fill)
            canvas.drawRoundRect(surface, radius, radius, edge)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouch(event)
    }

    private inner class TerminalClient : TerminalSessionClient, TerminalViewClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            terminalView?.onScreenUpdated()
        }
        override fun onTitleChanged(changedSession: TerminalSession) = Unit
        override fun onSessionFinished(finishedSession: TerminalSession) {
            val pid = terminalPid.takeIf { it > 1 } ?: finishedSession.pid
            if (pid > 1) SessionService.terminalProcessExited(context, pid)
            terminalPid = -1
            terminalView?.onScreenUpdated()
        }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("Terminal", text))
        }
        override fun onPasteTextFromClipboard(session: TerminalSession) {
            val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            if (clip != null && clip.itemCount > 0) session.write(clip.getItemAt(0).coerceToText(context).toString())
        }
        override fun onBell(session: TerminalSession) {
            terminalView?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        }
        override fun onColorsChanged(changedSession: TerminalSession) {
            terminalView?.invalidate()
        }
        override fun onTerminalCursorStateChange(state: Boolean) {
            terminalView?.setTerminalCursorBlinkerRate(if (state) 600 else 0)
        }
        override fun getTerminalCursorStyle(): Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
        override fun logError(tag: String, message: String) { Log.e(tag, message) }
        override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
        override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
        override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
        override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
        override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, e.message, e) }

        override fun onScale(scale: Float): Float = 1f
        override fun onSingleTapUp(e: MotionEvent) = showTerminalKeyboard()
        override fun shouldBackButtonBeMappedToEscape(): Boolean = true
        override fun shouldEnforceCharBasedInput(): Boolean = false
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) = Unit
        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
        override fun onLongPress(event: MotionEvent): Boolean = false
        override fun readControlKey(): Boolean = ctrlActive
        override fun readAltKey(): Boolean = altActive
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
        override fun onEmulatorSet() {
            terminalView?.onScreenUpdated()
            registerTerminalPid()
        }
    }
}
