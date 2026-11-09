package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Test

class TurnipEnvironmentTest {
    @Test fun sysmemSettingAppliesToGames() {
        assertEquals(listOf("DROIDDECK_GAME_TU_DEBUG=sysmem"), TurnipEnvironment.gameEnvironment(null, false, true, emptyList()))
    }

    @Test fun disablingSysmemKeepsTheDriverDefault() {
        assertEquals(listOf("DROIDDECK_GAME_TU_DEBUG="), TurnipEnvironment.gameEnvironment(null, false, false, emptyList()))
    }

    @Test fun overrideWinsOverTheSetting() {
        assertEquals(listOf("DROIDDECK_GAME_TU_DEBUG=nolrz"), TurnipEnvironment.gameEnvironment("nolrz", false, true, emptyList()))
    }

    @Test fun sysmemDriverForcesSysmem() {
        assertEquals(listOf("DROIDDECK_GAME_TU_DEBUG=sysmem"), TurnipEnvironment.gameEnvironment(null, true, false, emptyList()))
    }

    @Test fun gameEnvironmentOverrideWins() {
        assertEquals(emptyList<String>(), TurnipEnvironment.gameEnvironment(null, false, true, listOf("TU_DEBUG=nolrz")))
    }
}
