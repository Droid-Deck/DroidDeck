package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Test

class GameTuDebugTest {
    @Test fun adreno830GamesUseSysmem() {
        assertEquals("sysmem", SessionService.gameTuDebug(null, sysmemDriver = false, adrenoModel = 830))
    }

    @Test fun otherAdrenosKeepTurnipsOwnChoice() {
        for (model in listOf(0, 650, 740, 750, 840)) {
            assertEquals("Adreno $model", "", SessionService.gameTuDebug(null, sysmemDriver = false, adrenoModel = model))
        }
    }

    @Test fun theA710A720DriverStillUsesSysmem() {
        assertEquals("sysmem", SessionService.gameTuDebug(null, sysmemDriver = true, adrenoModel = 720))
    }

    @Test fun theOverrideFileWins() {
        assertEquals("gmem", SessionService.gameTuDebug("gmem", sysmemDriver = false, adrenoModel = 830))
        assertEquals("syncdraw", SessionService.gameTuDebug("syncdraw", sysmemDriver = true, adrenoModel = 740))
    }
}
