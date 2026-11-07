package com.droiddeck.launcher.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.input.PC_KEY_HIDDEN_GROUPS
import com.droiddeck.launcher.input.PC_PUNCTUATION_CODES
import com.droiddeck.launcher.session.SessionPrefs

internal object PcKeyboardPrefs {
    var size by mutableStateOf(SessionPrefs.PC_KEYBOARD_SIZE_DEFAULT)
        private set
    var opacity by mutableStateOf(SessionPrefs.PC_KEYBOARD_OPACITY_DEFAULT)
        private set
    var hiddenKeys by mutableStateOf(emptySet<String>())
        private set
    var hiddenSymbols by mutableStateOf(emptySet<Int>())
        private set
    var longPress by mutableStateOf(SessionPrefs.PC_KEYBOARD_LONG_PRESS_DEFAULT)
        private set
    var longPressMs by mutableStateOf(SessionPrefs.PC_KEYBOARD_LONG_PRESS_MS_DEFAULT)
        private set

    fun load(context: Context) {
        size = SessionPrefs.pcKeyboardSize(context)
        opacity = SessionPrefs.pcKeyboardOpacity(context)
        hiddenKeys = SessionPrefs.pcKeyboardHiddenKeys(context).filter { it in PC_KEY_HIDDEN_GROUPS }.toSet()
        hiddenSymbols = SessionPrefs.pcKeyboardHiddenSymbols(context).filter { it in PC_PUNCTUATION_CODES }.toSet()
        longPress = SessionPrefs.pcKeyboardLongPress(context)
        longPressMs = SessionPrefs.pcKeyboardLongPressMs(context)
    }

    fun setSize(context: Context, percent: Int) {
        size = percent
        SessionPrefs.setPcKeyboardSize(context, percent)
    }

    fun setOpacity(context: Context, percent: Int) {
        opacity = percent
        SessionPrefs.setPcKeyboardOpacity(context, percent)
    }

    fun toggleHiddenKey(context: Context, group: String, on: Boolean) {
        hiddenKeys = if (on) hiddenKeys + group else hiddenKeys - group
        SessionPrefs.setPcKeyboardHiddenKeys(context, hiddenKeys)
    }

    fun toggleHiddenSymbol(context: Context, code: Int, on: Boolean) {
        hiddenSymbols = if (on) hiddenSymbols + code else hiddenSymbols - code
        SessionPrefs.setPcKeyboardHiddenSymbols(context, hiddenSymbols)
    }

    fun setLongPress(context: Context, on: Boolean) {
        longPress = on
        SessionPrefs.setPcKeyboardLongPress(context, on)
    }

    fun setLongPressMs(context: Context, ms: Int) {
        val coerced = ms.coerceIn(SessionPrefs.PC_KEYBOARD_LONG_PRESS_MS_RANGE)
        longPressMs = coerced
        SessionPrefs.setPcKeyboardLongPressMs(context, coerced)
    }
}
