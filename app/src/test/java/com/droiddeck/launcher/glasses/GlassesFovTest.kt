package com.droiddeck.launcher.glasses

import org.junit.Assert.*
import org.junit.Test

class GlassesFovTest {
    @Test fun native1sAspectUsesThePerEyeResolution() {
        val native = GlassesState(width = 1920, height = 1200, diagonalFovDeg = 52f)
        val stereo = native.copy(width = 3840, sbs = true)
        val widescreen = native.copy(height = 1080)
        assertEquals(native.verticalFovDeg, stereo.verticalFovDeg, 0.001f)
        assertTrue(native.verticalFovDeg > widescreen.verticalFovDeg)
        assertEquals(widescreen.verticalFovDeg, native.copy(width = 0, height = 0).verticalFovDeg, 0.001f)
    }
}
