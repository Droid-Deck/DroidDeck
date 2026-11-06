package com.droiddeck.launcher.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.input.PC_KEY_HIDDEN_GROUPS
import com.droiddeck.launcher.session.SessionPrefs

internal object PcKeyboardPrefs {
    var size by mutableStateOf(SessionPrefs.PC_KEYBOARD_SIZE_DEFAULT)
        private set
    var opacity by mutableStateOf(SessionPrefs.PC_KEYBOARD_OPACITY_DEFAULT)
        private set
    var hiddenKeys by mutableStateOf(emptySet<String>())
        private set

    fun load(context: Context) {
        size = SessionPrefs.pcKeyboardSize(context)
        opacity = SessionPrefs.pcKeyboardOpacity(context)
        hiddenKeys = SessionPrefs.pcKeyboardHiddenKeys(context).filter { it in PC_KEY_HIDDEN_GROUPS }.toSet()
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
}
