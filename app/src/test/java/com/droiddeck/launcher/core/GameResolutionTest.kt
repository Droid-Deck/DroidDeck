package com.droiddeck.launcher.core

import com.droiddeck.launcher.session.NoSteamLaunch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameResolutionTest {
    @Test
    fun choicesRoundTripThroughTheStoredValue() {
        for (choice in listOf(GameResolution.Fixed(1280, 720), GameResolution.Scaled(1.5f), GameResolution.Scaled(2f))) {
            assertEquals(choice, GameResolution.parse(GameResolution.format(choice)))
        }
        assertEquals("1280x720", GameResolution.format(GameResolution.Fixed(1280, 720)))
        assertEquals("/1.5", GameResolution.format(GameResolution.Scaled(1.5f)))
    }

    @Test
    fun invalidValuesAreIgnored() {
        for (value in listOf(null, "", "abc", "100x100", "1280", "1280x720x2", "/1", "/0.5", "/9")) {
            assertNull(value, GameResolution.parse(value))
        }
    }

    @Test
    fun aScaleDividesTheOutputToEvenSides() {
        assertEquals(1706 to 1066, GameResolution.resolve(GameResolution.Scaled(1.5f), 2560 to 1600))
        assertEquals(1280 to 800, GameResolution.resolve(GameResolution.Scaled(2f), 2560 to 1600))
        assertEquals(1280 to 720, GameResolution.resolve(GameResolution.Fixed(1280, 720), 2560 to 1600))
    }

    @Test
    fun aspectRatiosAreNamed() {
        assertEquals("16:9", GameResolution.aspectLabel(1920, 1080))
        assertEquals("16:10", GameResolution.aspectLabel(2560, 1600))
        assertEquals("4:3", GameResolution.aspectLabel(1024, 768))
        assertEquals("21:9", GameResolution.aspectLabel(2560, 1080))
        assertEquals("20:9", GameResolution.aspectLabel(2400, 1080))
    }

    @Test
    fun presetsLargerThanThePanelAreLeftOut() {
        val wide = GameResolution.aspects.first { it.label == "16:9" }
        assertEquals(listOf(960 to 540, 1280 to 720, 1600 to 900, 1920 to 1080),
            GameResolution.sizesFor(wide, 1920 to 1200).map { it.width to it.height })
    }

    @Test
    fun theSessionFindsTheResolutionOfTheGameItStarts() {
        val config = GameEnvironment.withGameResolution(GameEnvironment.Config(), "570", GameResolution.Fixed(1280, 800))
        val scope = NoSteamLaunch.scopeOf(NoSteamLaunch.PROTON_RUN, listOf("--app", "570"))
        assertEquals("570", scope)
        assertEquals(1280 to 800, NoSteamLaunch.resolution(config, scope, 2560 to 1600))
        assertNull(NoSteamLaunch.resolution(config, "440", 2560 to 1600))
        assertEquals("123", NoSteamLaunch.scopeOf(NoSteamLaunch.BATTLENET, listOf("launch", "WTCG")) { if (it == "WTCG") "123" else null })
        assertEquals(com.droiddeck.launcher.runtime.BattleNet.APP_ID.toString(),
            NoSteamLaunch.scopeOf(NoSteamLaunch.BATTLENET, listOf("launch", "Pro")))
        assertNull(GameEnvironment.withGameResolution(config, "570", null).games["570"])
    }
}
