package com.droiddeck.launcher.input

import android.view.MotionEvent

/** Keeps split Android touch targets from restarting one shared gesture recognizer. */
internal class GestureTouchStreamGate {
    enum class Source { SURFACE, SCREEN_KEYS }

    private var owner: Source? = null

    fun accepts(source: Source, event: MotionEvent): Boolean {
        if (owner == null && event.actionMasked == MotionEvent.ACTION_DOWN) owner = source
        val accepted = owner == source
        if (accepted && (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)) {
            owner = null
        }
        return accepted
    }

    fun reset() { owner = null }
}
