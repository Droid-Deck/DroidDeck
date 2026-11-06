package com.droiddeck.launcher.ui

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionTerminal
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

class SessionTerminalPanel(context: Context) : LinearLayout(context) {
    private var terminalSession: TerminalSession? = null
    private var terminalView: TerminalView? = null
    private var terminalPid = -1
    private var ctrlActive = false
    private var altActive = false

    val ready: Boolean get() = terminalSession != null

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.BLACK)
        build()
    }

    private fun build() {
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
            addView(TextView(context).apply {
                text = context.getString(R.string.second_terminal_not_ready)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }, LayoutParams(-1, 0, 1f))
            return
        }
        terminalSession = session

        val keyStrip = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        val keys = LinearLayout(context).apply { orientation = HORIZONTAL; setPadding(dp(12), 0, dp(12), dp(8)) }
        fun key(label: String, input: String, onClick: ((TextView) -> Unit)? = null) {
            lateinit var button: TextView
            button = InputSurfaceView.roundButton(context, label, label, { if (onClick != null) onClick(button) else session.write(input) }, wide = true)
            if (keys.childCount > 0) keys.addView(View(context), LinearLayout.LayoutParams(dp(8), 1))
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
        addView(keyStrip, LayoutParams(-1, -2))

        addView(view, LayoutParams(-1, 0, 1f))
        view.attachSession(session)

        view.post {
            registerTerminalPid()
            view.requestFocus()
            showKeyboard()
        }
    }

    override fun onDetachedFromWindow() {
        close()
        super.onDetachedFromWindow()
    }

    fun showKeyboard() {
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

    fun close() {
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
        override fun onSingleTapUp(e: MotionEvent) = showKeyboard()
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
