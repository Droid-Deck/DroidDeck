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

    @Test fun mirroredRefreshUsesTheSelectedGlassesRateEvenBeforeTheDisplaySwitches() {
        val settings = GlassesSettings(gameOnGlasses = false, refreshHz = 60)
        assertEquals(60, GlassesRendering.mirrorRefreshLimit(settings, ultra.copy(hz = 120f), false))
        assertEquals(90, GlassesRendering.mirrorRefreshLimit(settings.copy(refreshHz = 90), ultra, false))
        assertEquals(60, GlassesRendering.mirrorRefreshLimit(settings, ultra.copy(connected = false), false))
    }

    @Test fun mirroredStereoUsesTheStereoRefreshAndItsHardwareLimit() {
        val settings = GlassesSettings(refreshHz = 120, sbsHz = 90)
        assertEquals(60, GlassesRendering.mirrorRefreshLimit(settings, ultra.copy(sbs = true), false))
        assertEquals(90, GlassesRendering.mirrorRefreshLimit(settings, ultra.copy(sbs = true, sbs90 = true), false))
    }

    @Test fun disconnectOrDedicatedOutputRemovesTheMirrorCap() {
        val settings = GlassesSettings(refreshHz = 60)
        assertEquals(0, GlassesRendering.mirrorRefreshLimit(settings, GlassesState(), false))
        assertEquals(0, GlassesRendering.mirrorRefreshLimit(settings, ultra.copy(noSignal = true), false))
        assertEquals(0, GlassesRendering.mirrorRefreshLimit(settings, ultra, true))
    }

    @Test fun mirrorCapPreservesLowerLimitsAndRestoresTheRequestedLimitAfterDisconnect() {
        for (requested in listOf(0, 30, 60, 90, 120)) {
            assertEquals(if (requested == 30) 30 else 60, GlassesRendering.fpsLimit(requested, 60))
            assertEquals(requested, GlassesRendering.fpsLimit(requested, 0))
        }
    }

    private fun render(settings: GlassesSettings, state: GlassesState = ultra, steamVr: Boolean = false) =
        GlassesRendering.cardboard(base, settings, state, outputActive = true, steamVrRunning = steamVr)

    @Test fun steamVrDefaultsToOneTrackedEyeWithoutRequestingStereo() {
        val settings = GlassesSettings()
        assertFalse(settings.auto3d)
        val projection = render(settings, steamVr = true)
        assertTrue(projection.enabled)
        assertTrue(projection.mono)
        assertTrue(projection.headTracking)
        assertEquals(Cardboard.SOURCE_SBS, projection.source)
    }

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

    @Test fun steamVrOnPhoneKeepsHeadsetActiveWithoutEnablingTheSavedCardboardSetting() {
        val settings = GlassesSettings(gameOnGlasses = false, headTracking = false,
            picture3d = GlassesSettings.PICTURE_SCREEN)
        for (state in listOf(ultra.copy(displayId = -1), GlassesState())) {
            val preview = GlassesRendering.cardboard(base, settings, state, false, true)
            assertTrue(preview.enabled) // Published as headset proximity, preventing standby.
            assertTrue(preview.mono)
            assertEquals(Cardboard.SOURCE_SBS, preview.source)
            assertEquals(Cardboard.Viewer.NONE.id, preview.viewer)
            assertTrue(preview.headTracking) // Phone preference, not dormant glasses settings.
            assertFalse(base.enabled)
            assertSame(base, GlassesRendering.cardboard(base, settings, state, false, false))
        }
        val noTracking = base.copy(headTracking = false)
        assertFalse(GlassesRendering.cardboard(noTracking, settings, GlassesState(), false, true).headTracking)
    }

    @Test fun steamVrKeepsAnEnabledPhoneViewerAndSwitchesBackToGlassesRenderingOnHandoff() {
        val phone = base.copy(enabled = true, source = Cardboard.SOURCE_SBS)
        val settings = GlassesSettings()
        assertSame(phone, GlassesRendering.cardboard(phone, settings, GlassesState(), false, true))
        val glasses = GlassesRendering.cardboard(base, settings, ultra.copy(sbs = true), true, true)
        assertTrue(glasses.enabled)
        assertFalse(glasses.mono)
        assertEquals(Cardboard.Viewer.VITURE.id, glasses.viewer)
        assertEquals(Cardboard.SOURCE_SBS, glasses.source)
    }

    @Test fun steamVrMirroringKeepsTheSameGlassesPictureAndSensorSource() {
        for (stereo in listOf(false, true)) {
            val state = ultra.copy(sbs = stereo)
            for (picture in listOf(GlassesSettings.PICTURE_AUTO, GlassesSettings.PICTURE_SCREEN, GlassesSettings.PICTURE_SBS)) {
                val settings = GlassesSettings(picture3d = picture)
                val dedicated = GlassesRendering.cardboard(base, settings, state, true, true)
                val mirrored = GlassesRendering.cardboard(base, settings.copy(gameOnGlasses = false), state, false, true)
                assertEquals(dedicated, mirrored)
                assertTrue(mirrored.enabled)
                assertTrue(mirrored.headTracking)
                assertTrue(GlassesRendering.usesGlasses(state, false, true))
            }
        }
        assertFalse(GlassesRendering.usesGlasses(ultra, false, false))
        assertFalse(GlassesRendering.usesGlasses(GlassesState(), false, true))
        assertTrue(GlassesRendering.usesGlasses(ultra, true, false))
    }

    @Test fun missingUsbControlOnlyDisablesTrackingNotScreenSize() {
        val settings = GlassesSettings(picture3d = GlassesSettings.PICTURE_SCREEN, screenWidthDm = 35)
        val projection = render(settings, ultra.copy(connected = false))
        assertTrue(projection.enabled)
        assertFalse(projection.headTracking)
        assertEquals(35, projection.screenWidthDm)
    }
}
