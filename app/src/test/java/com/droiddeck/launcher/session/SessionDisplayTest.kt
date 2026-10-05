package com.droiddeck.launcher.session

import org.junit.Assert.*
import org.junit.Test

class SessionDisplayTest {
    @Test fun explicitPresetsAndMatchScreenResolveWithoutAnyAspectSetting() {
        assertEquals(1280 to 720, SessionDisplay.resolveChoice(1440 to 1080, "1280x720"))
        assertEquals(1600 to 900, SessionDisplay.resolveChoice(1280 to 720, "1600x900"))
        assertEquals(1440 to 1080, SessionDisplay.resolveChoice(1080 to 1440, SessionDisplay.MATCH_SCREEN))
        assertEquals(2400 to 1080, SessionDisplay.resolveChoice(1080 to 2400, SessionDisplay.MATCH_SCREEN))
    }

    @Test fun panelOptionReplacesTheIdenticalPresetAndTracksPanelChanges() {
        val thor = SessionDisplay.resolutionOptions(1920 to 1080)
        assertEquals(listOf("1280x720", "1600x900", SessionDisplay.MATCH_SCREEN), thor)
        val small = SessionDisplay.resolutionOptions(1280 to 720)
        assertFalse(small.contains("1280x720"))
        assertEquals(1280 to 720, SessionDisplay.resolveChoice(1280 to 720, SessionDisplay.MATCH_SCREEN))
        assertEquals(2560 to 1440, SessionDisplay.resolveChoice(2560 to 1440, SessionDisplay.MATCH_SCREEN))
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
