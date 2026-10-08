package com.droiddeck.launcher.ui

import com.droiddeck.launcher.R
import android.app.Presentation
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.droiddeck.launcher.input.KeyboardHost
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

/** Touch controls drawn on a second Android display while the compositor remains on the main one. */
class SecondScreenPresentation(
    private val sessionActivity: SessionActivity,
    display: Display,
    private val sendPointer: (x: Float, y: Float, width: Float, height: Float) -> Unit,
    private val sendButton: (button: Int, pressed: Boolean) -> Unit,
    private val sendWheel: (steps: Int) -> Unit,
    private val onSteamMenu: () -> Unit,
    private val onQam: () -> Unit,
    private val onClose: () -> Unit,
) : Presentation(sessionActivity, display) {
    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.rgb(15, 18, 22))
        isFocusableInTouchMode = true
    }
    private var currentMode = SecondScreenMode.NONE
    private var keyboardHost: KeyboardHost? = null
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
        setContentView(root)
        applyImmersiveMode()
        renderMode()
    }

    fun showMode(mode: SecondScreenMode) {
        if (currentMode == mode && isShowing) return
        currentMode = mode
        if (created) renderMode()
    }

    /** Releases the auxiliary Linux shell before the window is dismissed. */
    fun closeControls() {
        closeTerminal()
        keyboardHost?.hide()
        keyboardHost = null
        dismiss()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (currentMode == SecondScreenMode.KEYBOARD_TRACKPAD) {
            if (sessionActivity.dispatchKeyEvent(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveMode()
            sessionActivity.refreshClipboard()
        }
    }

    private fun renderMode() {
        keyboardHost?.hide()
        keyboardHost = null
        closeTerminal()
        root.removeAllViews()
        if (currentMode == SecondScreenMode.NONE) return

        when (currentMode) {
            SecondScreenMode.KEYBOARD_TRACKPAD -> renderKeyboardAndTrackpad()
            SecondScreenMode.TERMINAL -> renderTerminal()
            SecondScreenMode.DECK_CONTROLS -> root.addView(DeckControlsPanel(context, onClose, onSteamMenu, onQam), weightParams())
            SecondScreenMode.NONE -> Unit
        }
    }

    private fun renderKeyboardAndTrackpad() {
        val panel = FrameLayout(context)
        panel.addView(trackpadView(), FrameLayout.LayoutParams(-1, -1))
        val controls = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0xE6191E24.toInt())
        }
        controls.addView(Button(context).apply {
            text = "×"
            contentDescription = context.getString(R.string.deck_close)
            minWidth = dp(48)
            setOnClickListener { onClose() }
        })
        controls.addView(TextView(context).apply {
            text = context.getString(R.string.second_trackpad)
            textSize = 14f
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val keyboardButton = Button(context).apply {
            text = context.getString(R.string.second_show_keyboard)
            setOnClickListener {
                val host = keyboardHost ?: return@setOnClickListener
                if (host.shown) {
                    host.hide()
                    text = context.getString(R.string.second_show_keyboard)
                } else {
                    host.show()
                    text = context.getString(R.string.second_hide_keyboard)
                }
            }
        }
        controls.addView(keyboardButton)
        controls.addView(Button(context).apply {
            text = "STEAM"
            contentDescription = context.getString(R.string.deck_open_steam_menu)
            setOnClickListener { onSteamMenu() }
        })
        controls.addView(Button(context).apply {
            text = "…"
            contentDescription = context.getString(R.string.drawer_open_qam)
            setOnClickListener { onQam() }
        })
        panel.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        root.addView(panel, weightParams())
        val host = KeyboardHost(context) { sessionActivity.dispatchKeyEvent(it) }
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

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.BLACK)
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
