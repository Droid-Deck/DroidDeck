package com.droiddeck.launcher.input

import android.media.AudioManager
import android.view.KeyEvent

/** Use Steam's PulseAudio bridge for feedback instead of requesting Android's volume UI. */
internal object SteamVolumeKeys {
    fun dispatch(event: KeyEvent, enabled: Boolean, adjust: (Int) -> Unit): Boolean {
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> AudioManager.ADJUST_RAISE
            KeyEvent.KEYCODE_VOLUME_DOWN -> AudioManager.ADJUST_LOWER
            KeyEvent.KEYCODE_VOLUME_MUTE -> AudioManager.ADJUST_TOGGLE_MUTE
            else -> return false
        }
        if (!enabled) return false
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode != KeyEvent.KEYCODE_VOLUME_MUTE || event.repeatCount == 0)) {
            adjust(direction)
        }
        return event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP
    }
}
