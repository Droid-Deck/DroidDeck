package com.droiddeck.launcher.session

import com.droiddeck.launcher.R
import org.junit.Assert.*
import org.junit.Test

class SessionDisplayTest {
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
        assertEquals(listOf("720p", "900p", SessionDisplay.MATCH_SCREEN, SessionDisplay.FOLLOW_SCREEN), thor)
        assertEquals(listOf("720p", "900p", SessionDisplay.MATCH_SCREEN, SessionDisplay.FOLLOW_SCREEN),
            SessionDisplay.resolutionOptions(2400 to 1080))
        assertEquals(listOf("720p", "900p", "1080p", SessionDisplay.MATCH_SCREEN, SessionDisplay.FOLLOW_SCREEN),
            SessionDisplay.resolutionOptions(3120 to 1440))
        val small = SessionDisplay.resolutionOptions(1280 to 720)
        assertEquals(listOf(SessionDisplay.MATCH_SCREEN, SessionDisplay.FOLLOW_SCREEN), small)
        assertEquals(1280 to 720, SessionDisplay.resolveChoice(1280 to 720, SessionDisplay.MATCH_SCREEN))
        assertEquals(2560 to 1440, SessionDisplay.resolveChoice(2560 to 1440, SessionDisplay.MATCH_SCREEN))
    }

    @Test fun unfoldedTheGameKeepsItsShapeAcrossTheWidthInAtMostEightyPercent() {
        assertEquals(2448 to 1376, SessionDisplay.unfoldedGameSize(2448, 1848, SessionDisplay.FOLDED_16X9))
        assertEquals(1848 to 1038, SessionDisplay.unfoldedGameSize(1848, 2448, SessionDisplay.FOLDED_16X9))
        assertEquals(1536 to 864, SessionDisplay.unfoldedGameSize(1920, 1080, SessionDisplay.FOLDED_16X9))
        assertEquals(2184 to 984, SessionDisplay.unfoldedGameSize(2184, 1968, SessionDisplay.FOLDED_FILL))
    }

    @Test fun followScreenCanRenderAtAFractionOfTheWindow() {
        assertEquals(1092 to 984, SessionDisplay.followSize(2184, 1968, 2f))
        assertEquals(1456 to 1312, SessionDisplay.followSize(2184, 1968, 1.5f))
        assertEquals(728 to 656, SessionDisplay.followSize(2184, 1968, 3f))
        assertEquals(546 to 492, SessionDisplay.followSize(2184, 1968, 4f))
        assertNull(SessionDisplay.followSize(640, 360, 2f))
        assertEquals(R.string.display_follow_two_thirds, SessionDisplay.divisorLabel(1.5f))
        assertEquals(R.string.display_follow_half, SessionDisplay.divisorLabel(2f))
    }

    @Test fun aHalfFoldedGameTakesTheLargestBoxOfItsShapeAboveTheFold() {
        assertEquals(2184 to 984, SessionDisplay.foldedGameSize(2184, 984, SessionDisplay.FOLDED_FILL))
        assertEquals(1748 to 984, SessionDisplay.foldedGameSize(2184, 984, SessionDisplay.FOLDED_16X9))
        assertEquals(1574 to 984, SessionDisplay.foldedGameSize(2184, 984, SessionDisplay.FOLDED_16X10))
        assertEquals(2184 to 912, SessionDisplay.foldedGameSize(2184, 984, SessionDisplay.FOLDED_SCOPE))
        assertEquals(1600 to 900, SessionDisplay.foldedGameSize(1600, 1200, SessionDisplay.FOLDED_16X9))
    }


    @Test fun followScreenKeepsTheWindowShapeAndSkipsTinyWindows() {
        assertEquals(2520 to 1080, SessionDisplay.resolveChoice(1080 to 2520, SessionDisplay.FOLLOW_SCREEN))
        assertEquals(1968 to 2184, SessionDisplay.followSize(1968, 2184))
        assertEquals(2184 to 1968, SessionDisplay.followSize(2185, 1969))
        assertNull(SessionDisplay.followSize(640, 360))
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
        assertFalse(SessionDisplay.canStretch16x9(1600 to 720))
        val wide = SessionDisplay.resolve(1440 to 1080, 0, SessionPrefs.SHAPE_WIDE)
        assertFalse(SessionDisplay.canStretch16x9(wide))
    }

    @Test fun removedFilterAliasesRetainTheirEquivalentBehavior() {
        assertEquals(0, SessionPrefs.canonicalUpscaler(1))
        assertEquals(4, SessionPrefs.canonicalUpscaler(5))
        assertEquals(0, SessionPrefs.canonicalUpscaler(99))
        assertFalse(SessionPrefs.upscalerHasSharpness(0))
        assertFalse(SessionPrefs.upscalerHasSharpness(1))
        assertFalse(SessionPrefs.upscalerHasSharpness(2))
        for (mode in 3..8) assertTrue(SessionPrefs.upscalerHasSharpness(mode))
    }
}
