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
    constructor(activity: Activity) :
        this(activity, { activity.dispatchKeyEvent(it) }, { WaylandCompositor.textInputCommit(it) })

    private val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    var shown = false
        private set
    var imeShowing = false
        private set
    var onImeChanged: ((Boolean) -> Unit)? = null
    val hideMeansDismissal: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || (shown && imeShowing)

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

    fun type(text: CharSequence?) {
        if (text.isNullOrEmpty()) return
        // Text and soft editing actions share this queue to preserve IME ordering.
        sendText(text.toString())
    }

    fun tap(keyCode: Int) {
        val control = softControl(keyCode)
        if (control != null) {
            sendText(control.toString())
            return
        }
        val t = System.currentTimeMillis()
        sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0))
        sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private inner class KeyConnection : BaseInputConnection(this, false) {
        // A press keeps the route chosen on its first down across repeats and the matching up.
        private val controlRoutes = HashMap<Int, Boolean>()

        private var composingText = ""
        private var composingCursor = 0

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            composingText = ""
            composingCursor = 0
            type(text)
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            composingText = text?.toString().orEmpty()
            val rawCursor = if (newCursorPosition > 0) {
                composingText.length.toLong() + newCursorPosition - 1L
            } else {
                newCursorPosition.toLong()
            }
            composingCursor = rawCursor.coerceIn(0L, composingText.length.toLong()).toInt()
            return true
        }

        override fun finishComposingText(): Boolean {
            val text = composingText
            composingText = ""
            composingCursor = 0
            type(text)
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val before = beforeLength.coerceAtLeast(0)
            val after = afterLength.coerceAtLeast(0)
            if (before == 0 && after == 0) return true
            val consumedBefore = minOf(before, composingCursor)
            val consumedAfter = minOf(after, composingText.length - composingCursor)
            val guestBefore = before - consumedBefore
            val guestAfter = after - consumedAfter
            if (guestBefore > 1 || guestAfter > 1) return false
            return applyDeletion(
                composingCursor - consumedBefore,
                composingCursor + consumedAfter,
                guestBefore,
                guestAfter,
            )
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
            val before = beforeLength.coerceAtLeast(0)
            val after = afterLength.coerceAtLeast(0)
            if (before == 0 && after == 0) return true
            if (splitsPair(composingCursor)) return false
            val text = composingText
            val beforePoints = Character.codePointCount(text, 0, composingCursor)
            val afterPoints = Character.codePointCount(text, composingCursor, text.length)
            val consumedBefore = minOf(before, beforePoints)
            val consumedAfter = minOf(after, afterPoints)
            val start = Character.offsetByCodePoints(text, composingCursor, -consumedBefore)
            val end = Character.offsetByCodePoints(text, composingCursor, consumedAfter)
            if (hasUnpairedSurrogate(text, start, end)) return false
            return applyDeletion(
                start,
                end,
                before - consumedBefore,
                after - consumedAfter,
            )
        }

        private fun applyDeletion(start: Int, end: Int, guestBefore: Int, guestAfter: Int): Boolean {
            if (guestBefore > 64 || guestAfter > 64) return false
            if (splitsPair(start) || splitsPair(end)) return false
            if (start < end) {
                composingText = composingText.removeRange(start, end)
                composingCursor = start
            }
            val controls = StringBuilder()
            repeat(guestBefore) { controls.append(BACKSPACE) }
            repeat(guestAfter) { controls.append(FORWARD_DELETE) }
            if (controls.isNotEmpty()) sendText(controls.toString())
            return true
        }

        private fun splitsPair(index: Int): Boolean =
            index > 0 && index < composingText.length &&
                composingText[index - 1].isHighSurrogate() && composingText[index].isLowSurrogate()

        private fun hasUnpairedSurrogate(text: CharSequence, start: Int, end: Int): Boolean {
            var i = start
            while (i < end) {
                val c = text[i]
                if (c.isHighSurrogate()) {
                    if (i + 1 >= end || !text[i + 1].isLowSurrogate()) return true
                    i += 2
                } else if (c.isLowSurrogate()) {
                    return true
                } else {
                    i++
                }
            }
            return false
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            val control = softControl(event.keyCode)
            if (control == null) return this@KeyboardHost.sendKeyEvent(event)
            if (event.action == KeyEvent.ACTION_DOWN) {
                val soft = if (event.repeatCount == 0) {
                    val route = !event.isModified()
                    controlRoutes[event.keyCode] = route
                    route
                } else {
                    controlRoutes[event.keyCode] ?: !event.isModified()
                }
                if (!soft) return this@KeyboardHost.sendKeyEvent(event)
                if (control == ENTER) finishComposingText()
                sendText(control.toString())
                return true
            }
            if (event.action == KeyEvent.ACTION_UP) {
                val soft = controlRoutes.remove(event.keyCode) ?: !event.isModified()
                if (soft) return true
                return this@KeyboardHost.sendKeyEvent(event)
            }
            return this@KeyboardHost.sendKeyEvent(event)
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            finishComposingText()
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
