package com.droiddeck.launcher.ui

internal enum class SessionDrawerPage(val firstControl: String) {
    CONTROLLER("input-profile"),
    GLASSES("glasses-game"),
    SECOND_SCREEN("second-screen:custom-controls"),
    DISPLAY("fg"),
    HUD("hud"),
    EFFECTS("look"),
    VR("steamvr"),
    GAMES("anisotropy"),
    AGENT("agent-input"),
    SESSION("suspend"),
}

internal fun sessionDrawerPages(
    hasSecondScreen: Boolean, hasGlasses: Boolean = false, hasAgent: Boolean = com.droiddeck.launcher.agent.AgentHost.installed && com.droiddeck.launcher.agent.AgentTab.shown,
): List<SessionDrawerPage> =
    SessionDrawerPage.entries.filter {
        (hasSecondScreen || it != SessionDrawerPage.SECOND_SCREEN) && (hasGlasses || it != SessionDrawerPage.GLASSES) &&
            (hasAgent || it != SessionDrawerPage.AGENT)
    }

internal fun List<SessionDrawerPage>.step(current: SessionDrawerPage, direction: Int): SessionDrawerPage {
    val index = indexOf(current).coerceAtLeast(0)
    return this[Math.floorMod(index + direction, size)]
}
