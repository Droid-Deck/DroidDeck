package com.droiddeck.launcher.input

import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import org.junit.Assert.assertEquals
import org.junit.Test

class PenRouterTest {
    @Test fun appUiBlocksThePenInEveryModeAndScreen() {
        for (mode in listOf(SessionPrefs.PEN_AUTO, SessionPrefs.PEN_MOUSE, SessionPrefs.PEN_TOUCH))
            for (glasses in listOf(false, true)) for (lock in listOf(false, true))
                assertEquals("$mode $glasses $lock", PenRouter.Route.BLOCKED, PenRouter.decide(true, mode, glasses, lock))
    }

    @Test fun touchModeKeepsThePenAFingerEverywhere() {
        for (glasses in listOf(false, true)) for (lock in listOf(false, true))
            assertEquals("$glasses $lock", PenRouter.Route.TOUCH, PenRouter.decide(false, SessionPrefs.PEN_TOUCH, glasses, lock))
    }

    @Test fun glassesAndProgramLocksMakeAutoAndMouseRelative() {
        for (mode in listOf(SessionPrefs.PEN_AUTO, SessionPrefs.PEN_MOUSE))
            for ((glasses, lock) in listOf(true to false, false to true, true to true))
                assertEquals("$mode $glasses $lock", PenRouter.Route.RELATIVE, PenRouter.decide(false, mode, glasses, lock))
    }

    @Test fun autoAndMouseDriveThePenRouteOnThePhone() {
        assertEquals(PenRouter.Route.PEN, PenRouter.decide(false, SessionPrefs.PEN_AUTO, false, false))
        assertEquals(PenRouter.Route.PEN, PenRouter.decide(false, SessionPrefs.PEN_MOUSE, false, false))
    }

    @Test fun onlyAutoOnThePenRouteInTheDesktopAsksForTheTablet() {
        for (route in PenRouter.Route.values())
            for (mode in listOf(SessionPrefs.PEN_AUTO, SessionPrefs.PEN_MOUSE, SessionPrefs.PEN_TOUCH))
                for (session in listOf(SessionService.MODE_DESKTOP, SessionService.MODE_STEAM, SessionService.MODE_RUN)) {
                    val expected = route == PenRouter.Route.PEN && mode == SessionPrefs.PEN_AUTO && session == SessionService.MODE_DESKTOP
                    assertEquals("$route $mode $session", expected, PenRouter.wantsTablet(route, mode, session))
                }
    }
}
