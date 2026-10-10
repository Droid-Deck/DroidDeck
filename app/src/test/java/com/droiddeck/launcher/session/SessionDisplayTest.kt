package com.droiddeck.launcher.session

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
        // 20:9 phone: edge to edge, no bars for the client's interface.
        assertEquals(1600 to 720, SessionDisplay.resolveChoice(2400 to 1080, SessionDisplay.DEFAULT_RESOLUTION))
        assertEquals(1608 to 720, SessionDisplay.resolveChoice(2412 to 1080, "720p"))
        // 1440p phone: bounded by the preset height, not the panel.
        assertEquals(1560 to 720, SessionDisplay.resolveChoice(3120 to 1440, "720p"))
        assertEquals(2340 to 1080, SessionDisplay.resolveChoice(3120 to 1440, "1080p"))
        // Foldable inner panel and 4:3 handhelds: never narrower than 16:9.
        assertEquals(1280 to 720, SessionDisplay.resolveChoice(2208 to 1840, "720p"))
        assertEquals(1280 to 720, SessionDisplay.resolveChoice(1440 to 1080, "720p"))
        // Never taller than the panel.
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
