package com.droiddeck.launcher.ui

/** Stable page identities keep focus and scroll positions when a secondary display comes and goes. */
internal enum class SessionDrawerPage(val firstControl: String) {
    CONTROLLER("touch"),
    SECOND_SCREEN("second-screen:none"),
    DISPLAY("hud"),
    EFFECTS("look"),
    GAMES("anisotropy"),
    SESSION("suspend"),
}

internal fun sessionDrawerPages(hasSecondScreen: Boolean): List<SessionDrawerPage> =
    SessionDrawerPage.entries.filter { hasSecondScreen || it != SessionDrawerPage.SECOND_SCREEN }

internal fun List<SessionDrawerPage>.step(current: SessionDrawerPage, direction: Int): SessionDrawerPage {
    val index = indexOf(current).coerceAtLeast(0)
    return this[Math.floorMod(index + direction, size)]
}
