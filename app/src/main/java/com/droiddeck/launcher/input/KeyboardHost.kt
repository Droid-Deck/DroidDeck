package com.droiddeck.launcher.input

import android.app.Activity
import android.content.Context
import android.os.Build
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.droiddeck.launcher.wayland.WaylandCompositor

class KeyboardHost(
    context: Context,
    private val sendKeyEvent: (KeyEvent) -> Boolean,
    private val sendText: (String) -> Unit,
) : View(context) {
    constructor(context: Context, sendKeyEvent: (KeyEvent) -> Boolean) :
        this(context, sendKeyEvent, { WaylandCompositor.textInputCommit(it) })

    constructor(activity: Activity) :
        this(activity, { activity.dispatchKeyEvent(it) }, { WaylandCompositor.textInputCommit(it) })

    private val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    var shown = false
        private set
    var imeShowing = false
        private set
    var onImeChanged: ((Boolean) -> Unit)? = null

    init {
        isFocusable = false
        isFocusableInTouchMode = false
        visibility = VISIBLE
        layoutParams = android.widget.FrameLayout.LayoutParams(1, 1)
    }

    override fun onCheckIsTextEditor(): Boolean = shown

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!shown) return null
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return KeyConnection()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val visible = insets.isVisible(WindowInsets.Type.ime())
            if (visible != imeShowing) {
                imeShowing = visible
                onImeChanged?.invoke(visible)
            }
        }
        return super.onApplyWindowInsets(insets)
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R || keyCode != KeyEvent.KEYCODE_BACK || !shown) {
            return super.onKeyPreIme(keyCode, event)
        }
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
            hide()
            onImeChanged?.invoke(false)
        }
        return true
    }

    fun toggle() = if (shown && imeVisible()) hide() else show()

    private fun imeVisible() = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
        rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != false

    fun show() {
        shown = true
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()
        imm.restartInput(this)
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hide() {
        shown = false
        imm.hideSoftInputFromWindow(windowToken, 0)
        isFocusable = false
        isFocusableInTouchMode = false
        clearFocus()
    }

    private inner class KeyConnection : BaseInputConnection(this, false) {
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (text.isNullOrEmpty()) return true
            // Text and soft editing actions share this queue to preserve IME ordering.
            sendText(text.toString())
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = true
        override fun finishComposingText(): Boolean = true

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val controls = StringBuilder()
            repeat(beforeLength) { controls.append(BACKSPACE) }
            repeat(afterLength) { controls.append(FORWARD_DELETE) }
            if (controls.isNotEmpty()) sendText(controls.toString())
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            val control = softControl(event.keyCode)
            if (control != null && !event.isModified() &&
                (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP)) {
                if (event.action == KeyEvent.ACTION_DOWN) sendText(control.toString())
                return true
            }
            return this@KeyboardHost.sendKeyEvent(event)
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            sendText(ENTER.toString())
            return true
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = ""
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
        override fun getSelectedText(flags: Int): CharSequence? = null
    }

    private fun softControl(keyCode: Int): Char? = when (keyCode) {
        KeyEvent.KEYCODE_DEL -> BACKSPACE
        KeyEvent.KEYCODE_FORWARD_DEL -> FORWARD_DELETE
        KeyEvent.KEYCODE_ENTER -> ENTER
        KeyEvent.KEYCODE_TAB -> TAB
        else -> null
    }

    private fun KeyEvent.isModified(): Boolean =
        isCtrlPressed || isShiftPressed || isAltPressed || isMetaPressed

    private companion object {
        // Control characters represent editing keys on the guest text channel.
        const val ENTER = '\r'
        const val BACKSPACE = '\b'
        const val FORWARD_DELETE = '\u007f'
        const val TAB = '\t'
    }
}
