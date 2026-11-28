package com.droiddeck.launcher.session

import org.junit.Assert.*
import org.junit.Test

class SessionDisplayTest {
    private val scaledFollow = listOf("follow/1.5", "follow/2", "follow/2.5", "follow/3", "follow/4")

    @Test fun portraitPresetsSwapTheOutputAndRetainTheResolutionCap() {
        assertEquals(720 to 1280, SessionDisplay.resolveChoice(1920 to 1080, "720p", portrait = true))
        assertEquals(900 to 1600, SessionDisplay.resolveChoice(1080 to 1920, "900p", portrait = true))
        assertEquals(720 to 1280, SessionDisplay.resolveChoice(1440 to 1080, "720p", portrait = true))
        assertEquals(720 to 1600, SessionDisplay.resolveChoice(2400 to 1080, "720p", portrait = true))
        assertEquals(1080 to 1920, SessionDisplay.resolveChoice(1921 to 1081, SessionDisplay.MATCH_SCREEN, portrait = true))
        assertEquals(1080 to 2400, SessionDisplay.resolveChoice(2400 to 1080, SessionDisplay.FOLLOW_SCREEN, portrait = true))
        assertEquals(720 to 1280, SessionDisplay.resolveChoice(1920 to 1080, "invalid", portrait = true))
    }

    @Test fun customDimensionsStayExplicitInEitherOrientation() {
        for (portrait in listOf(false, true)) {
            assertEquals(1280 to 720, SessionDisplay.resolveChoice(1920 to 1080, "1280x720", portrait))
            assertEquals(720 to 1280, SessionDisplay.resolveChoice(1920 to 1080, "720x1280", portrait))
        }
    }

    @Test fun fixedSizesAndMatchScreenResolveWithoutAnyAspectSetting() {
        assertEquals(1280 to 720, SessionDisplay.resolveChoice(1440 to 1080, "1280x720"))
        assertEquals(1600 to 900, SessionDisplay.resolveChoice(1280 to 720, "1600x900"))
        assertEquals(1440 to 1080, SessionDisplay.resolveChoice(1080 to 1440, SessionDisplay.MATCH_SCREEN))
        assertEquals(2400 to 1080, SessionDisplay.resolveChoice(1080 to 2400, SessionDisplay.MATCH_SCREEN))
    }

    @Test fun heightPresetsKeepThePanelShapeWithASixteenByNineFloor() {
        assertEquals(1600 to 720, SessionDisplay.resolveChoice(2400 to 1080, SessionDisplay.DEFAULT_RESOLUTION))
        assertEquals(1608 to 720, SessionDisplay.resolveChoice(2412 to 1080, "720p"))
        assertEquals(1560 to 720, SessionDisplay.resolveChoice(3120 to 1440, "720p"))
        assertEquals(2340 to 1080, SessionDisplay.resolveChoice(3120 to 1440, "1080p"))
        assertEquals(1280 to 720, SessionDisplay.resolveChoice(2208 to 1840, "720p"))
        assertEquals(1280 to 720, SessionDisplay.resolveChoice(1440 to 1080, "720p"))
        assertEquals(1280 to 720, SessionDisplay.resolveChoice(1280 to 720, "1080p"))
    }

    @Test fun optionsListTheHeightsThePanelCanShowThenThePanel() {
        val thor = SessionDisplay.resolutionOptions(1920 to 1080)
        assertEquals(listOf("720p", "900p", SessionDisplay.MATCH_SCREEN, SessionDisplay.FOLLOW_SCREEN) + scaledFollow, thor)
        assertEquals(listOf("720p", "900p", SessionDisplay.MATCH_SCREEN, SessionDisplay.FOLLOW_SCREEN) + scaledFollow,
            SessionDisplay.resolutionOptions(2400 to 1080))
        assertEquals(listOf("720p", "900p", "1080p", SessionDisplay.MATCH_SCREEN, SessionDisplay.FOLLOW_SCREEN) + scaledFollow,
            SessionDisplay.resolutionOptions(3120 to 1440))
        val small = SessionDisplay.resolutionOptions(1280 to 720)
        assertEquals(listOf(SessionDisplay.MATCH_SCREEN, SessionDisplay.FOLLOW_SCREEN) + scaledFollow, small)
        assertEquals(1280 to 720, SessionDisplay.resolveChoice(1280 to 720, SessionDisplay.MATCH_SCREEN))
        assertEquals(2560 to 1440, SessionDisplay.resolveChoice(2560 to 1440, SessionDisplay.MATCH_SCREEN))
    }

    @Test fun unfoldedControlsKeepTheGameSixteenByNineAcrossTheWidth() {
        assertEquals(606, SessionDisplay.unfoldedGameHeight(1080, 1920))
        assertEquals(606, SessionDisplay.unfoldedGameHeight(1080, 1920, aspect = 16f / 9f))
        assertEquals(606, SessionDisplay.unfoldedGameHeight(1080, 1920, aspect = 2.2f))
        assertEquals(1080, SessionDisplay.unfoldedGameHeight(1440, 3039, aspect = 4f / 3f))
        assertEquals(2430, SessionDisplay.unfoldedGameHeight(1440, 3039, aspect = 0.5f))
        assertEquals(1376, SessionDisplay.unfoldedGameHeight(2448, 1848))
        assertEquals(1038, SessionDisplay.unfoldedGameHeight(1848, 2448))
        assertEquals(864, SessionDisplay.unfoldedGameHeight(1920, 1080))
    }

    @Test fun aManualSplitReservesTheRequestedShareOfTheWindowForControls() {
        assertEquals(1176, SessionDisplay.unfoldedGameHeight(2180, 1960, controlsPercent = 40))
        assertEquals(1152, SessionDisplay.unfoldedGameHeight(1080, 1920, controlsPercent = 40))
        assertEquals(648, SessionDisplay.unfoldedGameHeight(1920, 1080, aspect = 4f / 3f, controlsPercent = 40))
        assertEquals(606, SessionDisplay.unfoldedGameHeight(1080, 1920, controlsPercent = 0))
    }

    @Test fun controllerHotplugSelectsSecondScreenWithoutReplacingTheSavedChoice() {
        assertFalse(SessionDisplay.unfoldedUsesSecondScreen(preferSecondScreen = false, controllerConnected = false))
        assertTrue(SessionDisplay.unfoldedUsesSecondScreen(preferSecondScreen = false, controllerConnected = true))
        assertTrue(SessionDisplay.unfoldedUsesSecondScreen(preferSecondScreen = true, controllerConnected = false))
        assertTrue(SessionDisplay.unfoldedUsesSecondScreen(preferSecondScreen = true, controllerConnected = true))
    }

    @Test fun keepingControlsWithAControllerPreservesEitherScreenChoice() {
        for (preferSecondScreen in listOf(false, true)) {
            for (controllerConnected in listOf(false, true)) {
                assertEquals(preferSecondScreen, SessionDisplay.unfoldedUsesSecondScreen(
                    preferSecondScreen, controllerConnected, keepWithController = true,
                ))
            }
        }
    }

    @Test fun theDockSupportsSteamAndStandaloneGamesButNotDesktopSessions() {
        assertTrue(SessionDisplay.controlsBelowSupported(SessionService.MODE_STEAM))
        assertTrue(SessionDisplay.controlsBelowSupported(SessionService.MODE_RUN))
        assertFalse(SessionDisplay.controlsBelowSupported(SessionService.MODE_DESKTOP))
    }

    @Test fun unfoldedControlsStillRequireAnEligibleWindow() {
        fun wanted(enabled: Boolean = true, foldable: Boolean = true, pad: Boolean = true, pip: Boolean = false,
            width: Int = 2448, height: Int = 1848, smallestWidthDp: Int = 700) =
            SessionDisplay.unfoldedControlsWanted(enabled, foldable, pad, pip, width, height, smallestWidthDp)
        assertTrue(wanted())
        assertTrue(wanted(foldable = false, width = 1080, height = 1920, smallestWidthDp = 393))
        assertFalse(wanted(enabled = false))
        assertFalse(wanted(foldable = false))
        assertFalse(wanted(pad = false))
        assertFalse(wanted(pip = true))
        assertFalse(wanted(smallestWidthDp = 599))
        assertFalse(wanted(width = 0))
    }

    @Test fun followScreenKeepsTheWindowShapeAndSkipsTinyWindows() {
        assertEquals(2520 to 1080, SessionDisplay.resolveChoice(1080 to 2520, SessionDisplay.FOLLOW_SCREEN))
        assertEquals(1968 to 2184, SessionDisplay.followSize(1968, 2184))
        assertEquals(2184 to 1968, SessionDisplay.followSize(2185, 1969))
        assertNull(SessionDisplay.followSize(640, 360))
    }

    @Test fun scaledFollowDividesTheWindowAndStaysEven() {
        assertEquals(1260 to 540, SessionDisplay.resolveChoice(1080 to 2520, "follow/2"))
        assertEquals(540 to 1260, SessionDisplay.resolveChoice(2520 to 1080, "follow/2", portrait = true))
        assertEquals(1092 to 984, SessionDisplay.followSize(2185, 1969, scale = 2f))
        assertEquals(656 to 728, SessionDisplay.followSize(1968, 2184, scale = 3f))
        assertNull(SessionDisplay.followSize(640, 360, scale = 2f))
        assertEquals(720 to 1600, SessionDisplay.resolveChoice(2400 to 1080, "follow/1.5", portrait = true))
        assertEquals(1600 to 720, SessionDisplay.resolveChoice(1080 to 2400, "follow/1.5"))
        assertNull(SessionDisplay.followScale("follow/1.0"))
    }

    @Test fun onlyKnownFollowChoicesCountAsFollow() {
        assertEquals(1f, SessionDisplay.followScale(SessionDisplay.FOLLOW_SCREEN))
        assertEquals(3f, SessionDisplay.followScale("follow/3"))
        assertEquals(1.3f, SessionDisplay.followScale("follow/1.3"))
        assertEquals(1.3f, SessionDisplay.followScale("follow/1.26"))
        assertEquals("follow/1.3", SessionDisplay.followScaled(1.3f))
        assertEquals("follow/2", SessionDisplay.followScaled(2f))
        assertNull(SessionDisplay.followScale("follow/1"))
        assertNull(SessionDisplay.followScale("follow/x"))
        assertNull(SessionDisplay.followScale("follow/9"))
        assertNull(SessionDisplay.followScale("720p"))
        assertFalse(SessionDisplay.isFollow(SessionDisplay.MATCH_SCREEN))
    }

    @Test fun aPhoneCanFollowTheShortGameAreaAboveItsControls() {
        assertNull(SessionDisplay.followSize(720, 404))
        assertEquals(720 to 404, SessionDisplay.followSize(720, 404, controlsBelow = true))
        assertNull(SessionDisplay.followSize(320, 180, controlsBelow = true))
        assertNull(SessionDisplay.followSize(720, 0, controlsBelow = true))
    }

    @Test fun resolutionCapsPreserveTheSelectedShapeWithoutExceedingPanelHeight() {
        assertEquals(1280 to 720, SessionDisplay.resolve(1920 to 1080, 720, SessionPrefs.SHAPE_AUTO))
        assertEquals(960 to 720, SessionDisplay.resolve(1440 to 1080, 720, SessionPrefs.SHAPE_EXACT))
        assertEquals(1280 to 720, SessionDisplay.resolve(1440 to 1080, 720, SessionPrefs.SHAPE_AUTO))
        assertEquals(1280 to 720, SessionDisplay.resolve(1280 to 720, 1080, SessionPrefs.SHAPE_AUTO))
    }

    @Test fun noCapUsesPanelHeightWhileCustomOverridesBothCapAndAspect() {
        assertEquals(1440 to 1080, SessionDisplay.resolve(1440 to 1080, 0, SessionPrefs.SHAPE_EXACT))
        assertEquals(1920 to 1080, SessionDisplay.resolve(1440 to 1080, 0, SessionPrefs.SHAPE_WIDE))
        assertEquals(1024 to 768, SessionDisplay.resolve(1920 to 1080, 720, SessionPrefs.SHAPE_WIDE, 1024 to 768))
    }

    @Test fun orientationAndOddDimensionsKeepTheExistingEvenLandscapeDisplay() {
        assertEquals(1920 to 1080, SessionDisplay.resolve(1081 to 1921, 0, SessionPrefs.SHAPE_EXACT))
        assertEquals(1600 to 720, SessionDisplay.resolve(2400 to 1080, 720, SessionPrefs.SHAPE_AUTO))
    }

    @Test fun gameStretchAvailabilityMatchesTheRuntimeIncludingRounding() {
        assertTrue(SessionDisplay.canStretch16x9(960 to 720))
        assertFalse(SessionDisplay.canStretch16x9(1280 to 720))
        assertTrue(SessionDisplay.canStretch16x9(1600 to 720))
        assertTrue(SessionDisplay.canStretch16x9(2400 to 1080))
        assertFalse(SessionDisplay.canStretch16x9(1920 to 1080))
        assertFalse(SessionDisplay.canStretch16x9(1366 to 768))
        val wide = SessionDisplay.resolve(1440 to 1080, 0, SessionPrefs.SHAPE_WIDE)
        assertFalse(SessionDisplay.canStretch16x9(wide))
    }

    @Test fun removedFilterAliasesRetainTheirEquivalentBehavior() {
        assertEquals(0, SessionPrefs.canonicalUpscaler(1))
        assertEquals(4, SessionPrefs.canonicalUpscaler(5))
        assertEquals(2, SessionPrefs.canonicalUpscaler(99))
        assertFalse(SessionPrefs.upscalerHasSharpness(0))
        assertFalse(SessionPrefs.upscalerHasSharpness(1))
        assertFalse(SessionPrefs.upscalerHasSharpness(2))
        for (mode in 3..8) assertTrue(SessionPrefs.upscalerHasSharpness(mode))
    }
}
