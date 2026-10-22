package com.droiddeck.launcher.glasses

import org.junit.Assert.*
import org.junit.Test

class GlassesDisplayModesTest {
    private val fullHd = GlassesResolution(1920, 1080)
    private val tall = GlassesResolution(1920, 1200)
    private val ultra = VitureProtocol.sdkModel("Luma Ultra")!!
    private val state = GlassesState(resolutions = ultra.resolutions)

    @Test fun separateProfilesRetainTheirOwnResolutions() {
        val settings = GlassesSettings(resolution2d = fullHd.key, resolution3d = tall.key, resolutionVr = fullHd.key)
        assertEquals(fullHd, GlassesDisplayModes.selected(settings, state))
        assertEquals(tall, GlassesDisplayModes.selected(settings, state.copy(sbs = true)))
        for (stereo in listOf(false, true))
            assertEquals(fullHd, GlassesDisplayModes.selected(settings, state.copy(sbs = stereo, vrActive = true)))
    }

    @Test fun automaticVrInheritsTheActiveLayoutAndDefaultsKeepNativeBehavior() {
        val settings = GlassesSettings(resolution2d = fullHd.key, resolution3d = tall.key)
        assertEquals(fullHd, GlassesDisplayModes.selected(settings, state.copy(vrActive = true)))
        assertEquals(tall, GlassesDisplayModes.selected(settings, state.copy(sbs = true, vrActive = true)))
        assertNull(GlassesDisplayModes.selected(GlassesSettings(), state))
        assertNull(GlassesDisplayModes.selected(GlassesSettings(), state.copy(sbs = true, vrActive = true)))
    }

    @Test fun anotherHeadsetDoesNotReceiveAnUnsupportedSavedResolution() {
        val settings = GlassesSettings(resolution2d = tall.key, resolutionVr = tall.key)
        assertNull(GlassesDisplayModes.selected(settings, state.copy(resolutions = listOf(fullHd))))
        assertNull(GlassesDisplayModes.selected(settings, state.copy(resolutions = emptyList(), vrActive = true)))
        assertEquals(tall, GlassesDisplayModes.selected(settings, state))
    }

    @Test fun ultraResolutionsMapToVendorCommandsAtEverySupportedRefresh() {
        for ((hz, code) in listOf(60 to 0x31, 90 to 0x33, 120 to 0x34)) {
            assertEquals(code, ultra.mode(hz, false, fullHd))
            assertEquals(code + 0x10, ultra.mode(hz, false, tall))
        }
        for ((hz, code) in listOf(60 to 0x32, 90 to 0x35)) {
            assertEquals(code, ultra.mode(hz, true, fullHd))
            assertEquals(code + 0x10, ultra.mode(hz, true, tall))
        }
        assertEquals(0x41, ultra.mode(30, false, tall))
        assertEquals(0x42, ultra.mode(30, true, tall))
    }

    @Test fun olderModelsKeepTheirNativeResolutionAndStereoRateLimits() {
        for (name in listOf("One", "Pro", "Pro 2")) {
            val model = VitureProtocol.sdkModel(name)!!
            assertEquals(listOf(fullHd), model.resolutions)
            assertEquals(0x34, model.mode(120, false, tall))
            assertEquals(0x32, model.mode(90, true, tall))
        }
        for (name in listOf("Luma", "Luma Pro", "Luma Ultra", "Beast")) {
            val model = VitureProtocol.sdkModel(name)!!
            assertEquals(listOf(fullHd, tall), model.resolutions)
            assertEquals(0x44, model.mode(120, false))
            assertEquals(if (model.sbs90) 0x45 else 0x42, model.mode(90, true))
        }
    }
}
