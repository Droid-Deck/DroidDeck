package com.droiddeck.launcher.input

import com.droiddeck.launcher.session.SessionPrefs
import org.junit.Assert.assertEquals
import org.junit.Test

class PenRouterTest {
    @Test fun appUiBlocksThePenInEveryModeAndScreen() {
        for (mode in listOf(SessionPrefs.PEN_MOUSE, SessionPrefs.PEN_TOUCH))
            for (glasses in listOf(false, true)) for (lock in listOf(false, true))
                assertEquals("$mode $glasses $lock", PenRouter.Route.BLOCKED, PenRouter.decide(true, mode, glasses, lock))
    }

    @Test fun touchModeKeepsThePenAFingerEverywhere() {
        for (glasses in listOf(false, true)) for (lock in listOf(false, true))
            assertEquals("$glasses $lock", PenRouter.Route.TOUCH, PenRouter.decide(false, SessionPrefs.PEN_TOUCH, glasses, lock))
    }

    @Test fun glassesAndProgramLocksMakeTheMouseRelative() {
        for ((glasses, lock) in listOf(true to false, false to true, true to true))
            assertEquals("$glasses $lock", PenRouter.Route.RELATIVE, PenRouter.decide(false, SessionPrefs.PEN_MOUSE, glasses, lock))
    }

    @Test fun mouseDrivesThePenRouteOnThePhone() {
        assertEquals(PenRouter.Route.PEN, PenRouter.decide(false, SessionPrefs.PEN_MOUSE, false, false))
    }
}
