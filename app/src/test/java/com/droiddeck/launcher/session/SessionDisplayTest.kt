package com.droiddeck.launcher.session

import org.junit.Assert.*
import org.junit.Test

class SessionDisplayTest {
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
