package com.droiddeck.launcher.glasses

import com.droiddeck.launcher.gpu.Cardboard
import org.junit.Assert.*
import org.junit.Test

class GlassesRenderingTest {
    private val ultra = GlassesState(
        model = "VITURE Luma Ultra", connected = true, controllable = true,
        displayId = 2, width = 1920, height = 1200,
    )
    private val base = Cardboard(ipdMm = 67)

    private fun render(settings: GlassesSettings, state: GlassesState = ultra, steamVr: Boolean = false) =
        GlassesRendering.cardboard(base, settings, state, outputActive = true, steamVrRunning = steamVr)

    @Test fun automatic2dIsFlatSoVirtualScreenSettingsDoNotApply() {
        val settings = GlassesSettings(screenWidthDm = 40, screenDistanceDm = 80)
        assertEquals(GlassesRendering.Picture.FLAT, GlassesRendering.picture(settings, false, false))
        assertEquals(Cardboard.OFF, render(settings))
        assertEquals(Cardboard.OFF, render(settings.copy(screenWidthDm = 10, screenDistanceDm = 10)))
    }

    @Test fun selectingVirtualScreenMakesSizeDistanceAndTrackingReachTheRendererIn2d() {
        val settings = GlassesSettings(picture3d = GlassesSettings.PICTURE_SCREEN,
            screenWidthDm = 40, screenDistanceDm = 80)
        val projection = render(settings)
        assertTrue(projection.enabled)
        assertTrue(projection.mono)
        assertTrue(projection.headTracking)
        assertEquals(Cardboard.SOURCE_SCREEN, projection.source)
        assertEquals(40, projection.screenWidthDm)
        assertEquals(80, projection.screenDistanceDm)
        assertEquals(67, projection.ipdMm)
        val changed = render(settings.copy(screenWidthDm = 15, screenDistanceDm = 20, headTracking = false))
        assertEquals(15, changed.screenWidthDm)
        assertEquals(20, changed.screenDistanceDm)
        assertFalse(changed.headTracking)
    }

    @Test fun automatic3dGamesUseAVirtualScreenButSteamVrUsesDirectStereo() {
        val settings = GlassesSettings()
        val stereo = ultra.copy(sbs = true, width = 3840)
        assertEquals(GlassesRendering.Picture.VIRTUAL_SCREEN, GlassesRendering.picture(settings, true, false))
        assertEquals(Cardboard.SOURCE_SCREEN, render(settings, stereo).source)
        assertFalse(render(settings, stereo).mono)
        assertEquals(GlassesRendering.Picture.STEREO, GlassesRendering.picture(settings, true, true))
        assertEquals(Cardboard.SOURCE_SBS, render(settings, stereo, steamVr = true).source)
    }

    @Test fun explicitPictureChoiceOverridesAutomaticResolution() {
        val virtual = GlassesSettings(picture3d = GlassesSettings.PICTURE_SCREEN)
        val direct = GlassesSettings(picture3d = GlassesSettings.PICTURE_SBS)
        for (stereo in listOf(false, true)) {
            for (vr in listOf(false, true)) {
                assertEquals(GlassesRendering.Picture.VIRTUAL_SCREEN, GlassesRendering.picture(virtual, stereo, vr))
                assertEquals(GlassesRendering.Picture.STEREO, GlassesRendering.picture(direct, stereo, vr))
            }
        }
    }

    @Test fun mirrorModeKeepsThePhoneProjectionInsteadOfApplyingDormantGlassesSettings() {
        val phone = base.copy(enabled = true, screenWidthDm = 60, screenDistanceDm = 20)
        val settings = GlassesSettings(picture3d = GlassesSettings.PICTURE_SCREEN,
            gameOnGlasses = false, screenWidthDm = 10, screenDistanceDm = 50)
        assertSame(phone, GlassesRendering.cardboard(phone, settings, ultra, false, false))
    }

    @Test fun missingUsbControlOnlyDisablesTrackingNotScreenSize() {
        val settings = GlassesSettings(picture3d = GlassesSettings.PICTURE_SCREEN, screenWidthDm = 35)
        val projection = render(settings, ultra.copy(connected = false))
        assertTrue(projection.enabled)
        assertFalse(projection.headTracking)
        assertEquals(35, projection.screenWidthDm)
    }
}
