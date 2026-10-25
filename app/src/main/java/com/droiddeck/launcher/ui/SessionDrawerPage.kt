package com.droiddeck.launcher.ui

internal enum class SessionDrawerPage(val firstControl: String) {
    CONTROLLER("touch"),
    GLASSES("glasses-game"),
    SECOND_SCREEN("second-screen:none"),
    DISPLAY("hud"),
    EFFECTS("look"),
    VR("steamvr"),
    GAMES("anisotropy"),
    SDL3BRIDGE("sdl3-open"),   // sdl3bridge: SDL3Bridge page
    SESSION("suspend"),
}

internal fun sessionDrawerPages(hasSecondScreen: Boolean, hasGlasses: Boolean = false): List<SessionDrawerPage> =
    SessionDrawerPage.entries.filter {
        (hasSecondScreen || it != SessionDrawerPage.SECOND_SCREEN) && (hasGlasses || it != SessionDrawerPage.GLASSES)
    }

internal fun List<SessionDrawerPage>.step(current: SessionDrawerPage, direction: Int): SessionDrawerPage {
    val index = indexOf(current).coerceAtLeast(0)
    return this[Math.floorMod(index + direction, size)]
}
