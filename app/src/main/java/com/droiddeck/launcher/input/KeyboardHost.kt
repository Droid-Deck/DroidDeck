package com.droiddeck.launcher.input

import android.app.Activity
import android.content.Context
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class KeyboardHost(
    context: Context,
    private val onImeVisibilityChanged: (Boolean) -> Unit = {},
    private val sendKeyEvent: (KeyEvent) -> Boolean,
) : View(context) {
    constructor(activity: Activity, onImeVisibilityChanged: (Boolean) -> Unit = {}) :
        this(activity, onImeVisibilityChanged, { activity.dispatchKeyEvent(it) })

    private val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private val map: KeyCharacterMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
    private var active = false
    private var imeVisible = false
    private var previousFocus: View? = null
    // Fullscreen content can consume IME insets before they reach this view.
    private val observeIme = ViewTreeObserver.OnGlobalLayoutListener {
        ViewCompat.getRootWindowInsets(this)?.let(::applyInsets)
    }

    init {
        isFocusable = false
        isFocusableInTouchMode = false
        visibility = VISIBLE
        layoutParams = android.widget.FrameLayout.LayoutParams(1, 1)
    }

    override fun onCheckIsTextEditor(): Boolean = active

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!active) return null
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return KeyConnection()
    }

    fun toggle() = if (active && imeVisible) hide() else show()

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        applyInsets(WindowInsetsCompat.toWindowInsetsCompat(insets, this))
        return super.onApplyWindowInsets(insets)
    }

    private fun applyInsets(insets: WindowInsetsCompat) {
        val visible = insets.isVisible(WindowInsetsCompat.Type.ime())
        if (imeVisible && !visible) releaseFocus()
        updateVisibility(visible)
    }

    private fun updateVisibility(visible: Boolean) {
        if (imeVisible == visible) return
        imeVisible = visible
        onImeVisibilityChanged(visible)
    }

    fun show() {
        if (!active) previousFocus = rootView.findFocus()?.takeUnless { it === this }
        active = true
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()
        imm.restartInput(this)
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hide() {
        imm.hideSoftInputFromWindow(windowToken, 0)
        releaseFocus()
    }

    private fun releaseFocus() {
        active = false
        isFocusable = false
        isFocusableInTouchMode = false
        clearFocus()
        previousFocus?.takeIf { it.isAttachedToWindow }?.requestFocus()
        previousFocus = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalLayoutListener(observeIme)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnGlobalLayoutListener(observeIme)
        if (active) hide()
        updateVisibility(false)
        super.onDetachedFromWindow()
    }

    private inner class KeyConnection : BaseInputConnection(this, false) {
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (text.isNullOrEmpty()) return true
            val events = map.getEvents(text.toString().toCharArray())
            if (events != null) {
                for (event in events) sendKeyEvent(event)
            } else {
                for (ch in text) {
                    val t = System.currentTimeMillis()
                    val down = KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_UNKNOWN, 0, 0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, 0)
                    sendKeyEvent(CharKeyEvent(down, ch))
                    sendKeyEvent(CharKeyEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP), ch))
                }
            }
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = true
        override fun finishComposingText(): Boolean = true

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength) { tap(KeyEvent.KEYCODE_DEL) }
            repeat(afterLength) { tap(KeyEvent.KEYCODE_FORWARD_DEL) }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean = this@KeyboardHost.sendKeyEvent(event)

        override fun performEditorAction(actionCode: Int): Boolean {
            tap(KeyEvent.KEYCODE_ENTER)
            return true
        }

        private fun tap(keyCode: Int) {
            val t = System.currentTimeMillis()
            sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0))
            sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0))
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = ""
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
        override fun getSelectedText(flags: Int): CharSequence? = null
    }

    private class CharKeyEvent(origin: KeyEvent, private val ch: Char) : KeyEvent(origin) {
        override fun getUnicodeChar(): Int = ch.code
        override fun getUnicodeChar(metaState: Int): Int = ch.code
    }
}
