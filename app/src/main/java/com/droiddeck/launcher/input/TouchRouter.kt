package com.droiddeck.launcher.input

import com.droiddeck.launcher.session.SessionPrefs

object TouchRouter {
    enum class Route { BLOCKED, GESTURES, SCROLL, OFF, TOUCHPAD, DIRECT }

    data class Snapshot(
        val uiBlocking: Boolean,
        val mode: String,
        val scrollLayer: Boolean,
        val steamSession: Boolean,
        val steamDesktopUi: Boolean,
    )

    fun decide(s: Snapshot): Route {
        if (s.uiBlocking) return Route.BLOCKED
        if (s.mode == SessionPrefs.TOUCH_GESTURES) return Route.GESTURES
        if (s.scrollLayer) return Route.SCROLL
        if (s.mode == SessionPrefs.TOUCH_OFF) return Route.OFF
        return if (touchpad(s)) Route.TOUCHPAD else Route.DIRECT
    }

    fun steamKeyboardZone(s: Snapshot): Boolean = s.steamSession && !s.steamDesktopUi

    private fun touchpad(s: Snapshot): Boolean = when (s.mode) {
        SessionPrefs.TOUCH_PAD -> true
        SessionPrefs.TOUCH_DIRECT -> false
        else -> !s.steamSession || s.steamDesktopUi
    }
}
