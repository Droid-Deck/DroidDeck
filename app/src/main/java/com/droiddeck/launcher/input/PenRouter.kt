package com.droiddeck.launcher.input

import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService

object PenRouter {
    enum class Route { BLOCKED, PEN, RELATIVE, TOUCH }

    fun decide(uiBlocking: Boolean, mode: String, glasses: Boolean, programLock: Boolean): Route = when {
        uiBlocking -> Route.BLOCKED
        mode == SessionPrefs.PEN_TOUCH -> Route.TOUCH
        glasses || programLock -> Route.RELATIVE
        else -> Route.PEN
    }

    fun wantsTablet(route: Route, mode: String, session: String): Boolean =
        route == Route.PEN && mode == SessionPrefs.PEN_AUTO && session == SessionService.MODE_DESKTOP
}
