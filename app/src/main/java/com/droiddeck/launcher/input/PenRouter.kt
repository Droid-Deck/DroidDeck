package com.droiddeck.launcher.input

import com.droiddeck.launcher.session.SessionPrefs

object PenRouter {
    enum class Route { BLOCKED, PEN, RELATIVE, TOUCH }

    fun decide(uiBlocking: Boolean, mode: String, glasses: Boolean, programLock: Boolean): Route = when {
        uiBlocking -> Route.BLOCKED
        mode == SessionPrefs.PEN_TOUCH -> Route.TOUCH
        glasses || programLock -> Route.RELATIVE
        else -> Route.PEN
    }
}
