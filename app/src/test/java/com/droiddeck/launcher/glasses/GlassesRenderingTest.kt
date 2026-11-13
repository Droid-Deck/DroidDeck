package com.droiddeck.launcher.glasses

import com.droiddeck.launcher.gpu.Cardboard
import org.junit.Assert.*
import org.junit.Test

class GlassesRenderingTest {
    @Test fun advertisedRatesDoNotGainUnsupportedSixtyHzAndOldPreferencesUseAValidRate() {
        val state = GlassesState(rates2d = listOf(90, 120))
        assertEquals(listOf(90, 120), GlassesRendering.refreshRates(GlassesSettings(), state))
        for (saved in listOf(30, 60, 90)) {
            assertEquals(90, GlassesRendering.outputHz(GlassesSettings(refreshHz = saved), state))
        }
    }

    @Test fun sdkRefreshChoicesMatchEveryNativeAndBypassDisplayShape() {
        val settings = GlassesSettings(refreshHz = 30, sbsHz = 30)
        for (native in 0..1) {
            val device = VitureDeviceState(capabilities = VitureSdk.CAP_NATIVE, nativeMode = native)
            for (mode in device.displayModes) {
                val state = GlassesState(connected = true, displayId = 2, supports30Hz = true,
                    sbs = mode.stereo, sbs90 = false, rates2d = listOf(60, 90, 120),
                    viture = device.copy(displayMode = mode.code))
                val expected = when {
                    mode.ultrawide -> listOf(60)
                    mode.stereo -> listOf(if (native == 0) 90 else 60)
                    native == 1 -> listOf(60, 120)
                    else -> listOf(60)
                }
                assertEquals("native=$native mode=${mode.code}", expected, GlassesRendering.refreshRates(settings, state))
                assertEquals(mode.hz, GlassesRendering.outputHz(settings, state))
                assertFalse(GlassesRendering.native30HzAvailable(settings, state))
            }
        }
    }

    @Test fun unknownSdkDisplayOffersOnlyTheObservedRateWithoutGuessingItsMode() {
        val settings = GlassesSettings(refreshHz = 120, sbsHz = 30)
        val device = VitureDeviceState(capabilities = VitureSdk.CAP_NATIVE, displayMode = 0x45)
        for (unknown in listOf(device, device.copy(nativeMode = 1), device.copy(nativeMode = 2))) {
            val state = GlassesState(hz = 119.98f, sbs = true, supports30Hz = true, viture = unknown)
            assertEquals(listOf(120), GlassesRendering.refreshRates(settings, state))
            assertEquals(120, GlassesRendering.outputHz(settings, state))
            for (missingRate in listOf(0f, Float.NaN)) {
                val missing = state.copy(hz = missingRate)
                assertEquals(listOf(60), GlassesRendering.refreshRates(settings, missing))
                assertEquals(60, GlassesRendering.outputHz(settings, missing))
            }
        }
    }

    @Test fun deviceDisplayChoiceControlsPresentationRateInsteadOfSavedHostPreference() {
        val settings = GlassesSettings(refreshHz = 60, sbsHz = 60)
        val state = GlassesState(viture = VitureDeviceState(capabilities = VitureSdk.CAP_NATIVE,
            nativeMode = 1, displayMode = 0x36))
        assertEquals(120, GlassesRendering.outputHz(settings, state))
        assertEquals(GlassesResolution(1920, 1200), state.viture!!.selectedDisplayMode!!.presentationResolution)
        val bypass = state.copy(sbs = true, viture = state.viture.copy(nativeMode = 0, displayMode = 0x45))
        assertEquals(90, GlassesRendering.outputHz(settings, bypass))
        val ultrawide = state.copy(viture = state.viture.copy(displayMode = 0x40))
        assertEquals(60, GlassesRendering.outputHz(settings.copy(refreshHz = 120), ultrawide))
        assertEquals(GlassesResolution(1920, 1200), ultrawide.viture!!.selectedDisplayMode!!.presentationResolution)
    }

    @Test fun modelTwoDRatesLimitChoicesAndSavedRatesFallBackToAnOfferedOne() {
        val rokid = GlassesState(displayId = 2, width = 1920, height = 1080, hz = 60f, rates2d = listOf(60, 120))
        assertEquals(listOf(60, 120), GlassesRendering.refreshRates(GlassesSettings(), rokid))
        assertEquals(60, GlassesRendering.outputHz(GlassesSettings(refreshHz = 90), rokid))
        assertEquals(120, GlassesRendering.outputHz(GlassesSettings(refreshHz = 120), rokid))
        assertEquals(listOf(60, 90, 120), GlassesRendering.refreshRates(GlassesSettings(), rokid.copy(rates2d = listOf(60, 90, 120))))
    }

    private val ultra = GlassesState(
        model = "VITURE Luma Ultra", connected = true, controllable = true,
        displayId = 2, width = 1920, height = 1200,
    )
    private val base = Cardboard(ipdMm = 67)

    @Test fun thirtyHzRequiresANativeModeAndDedicatedGlassesOutput() {
        val settings = GlassesSettings(refreshHz = 30, sbsHz = 30)
        for (stereo in listOf(false, true)) for (sbs90 in listOf(false, true)) {
            val state = ultra.copy(sbs = stereo, sbs90 = sbs90, supports30Hz = true)
            assertTrue(GlassesRendering.refreshRates(settings, state).contains(30))
            assertEquals(30, GlassesRendering.outputHz(settings, state))
            assertEquals(60, GlassesRendering.mirrorRefreshLimit(settings, state.copy(hz = 60f), false))
            assertEquals(30, GlassesRendering.mirrorRefreshLimit(settings, state.copy(hz = 29.97f), false))
            for (unavailable in listOf(state.copy(supports30Hz = false), state.copy(noSignal = true), state.copy(displayId = -1))) {
                assertFalse(GlassesRendering.refreshRates(settings, unavailable).contains(30))
                assertEquals(60, GlassesRendering.outputHz(settings, unavailable))
            }
            val mirrored = settings.copy(gameOnGlasses = false)
            assertFalse(GlassesRendering.refreshRates(mirrored, state).contains(30))
            assertEquals(60, GlassesRendering.outputHz(mirrored, state))
            assertEquals(60, GlassesRendering.mirrorRefreshLimit(mirrored, state, false))
        }
    }

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

    @Test fun automaticSbsDuplicatesTheWholeFrameWithoutEnablingHeadTracking() {
        val settings = GlassesSettings()
        val stereo = ultra.copy(sbs = true, width = 3840)
        assertEquals(GlassesRendering.Picture.DUPLICATED, GlassesRendering.picture(settings, true, false))
        assertEquals(Cardboard.SOURCE_FLAT, render(settings, stereo).source)
        assertFalse(render(settings, stereo).headTracking)
        assertFalse(render(settings, stereo).mono)
        assertEquals(GlassesRendering.Picture.STEREO, GlassesRendering.picture(settings, true, true))
        assertEquals(Cardboard.SOURCE_SBS, render(settings, stereo, steamVr = true).source)
        assertTrue(render(settings, stereo, steamVr = true).headTracking)
    }

    @Test fun flatSbsDoesNotDependOnTheTrackingPreferenceOrUsbControl() {
        for (tracking in listOf(false, true)) for (connected in listOf(false, true)) {
            for (height in listOf(1080, 1200)) {
                val state = ultra.copy(sbs = true, width = 3840, height = height, connected = connected)
                val settings = GlassesSettings(headTracking = tracking)
                val flat = render(settings, state)
                assertTrue(flat.enabled)
                assertFalse(flat.mono)
                assertFalse(flat.headTracking)
                assertEquals(Cardboard.SOURCE_FLAT, flat.source)
                assertEquals(Cardboard.SOURCE_SBS, render(settings.copy(picture3d = GlassesSettings.PICTURE_SBS), state).source)
            }
        }
    }

    @Test fun virtualScreenTracksFlatContentIndependentlyOfTheHardwareSbsMode() {
        val settings = GlassesSettings(picture3d = GlassesSettings.PICTURE_SCREEN)
        assertTrue(settings.screen3dof)
        assertFalse(settings.copy(headTracking = false).screen3dof)
        for (height in listOf(1080, 1200)) {
            for (sbs in listOf(false, true)) {
                val projection = render(settings, ultra.copy(width = if (sbs) 3840 else 1920, height = height, sbs = sbs))
                assertTrue(projection.enabled)
                assertTrue(projection.headTracking)
                assertEquals(Cardboard.SOURCE_SCREEN, projection.source)
                assertEquals(!sbs, projection.mono)
            }
        }
    }

    @Test fun explicitPictureChoiceOverridesAutomaticResolutionOutsideSteamVr() {
        val virtual = GlassesSettings(picture3d = GlassesSettings.PICTURE_SCREEN)
        val direct = GlassesSettings(picture3d = GlassesSettings.PICTURE_SBS)
        for (stereo in listOf(false, true)) {
            assertEquals(GlassesRendering.Picture.VIRTUAL_SCREEN, GlassesRendering.picture(virtual, stereo, false))
            assertEquals(GlassesRendering.Picture.STEREO, GlassesRendering.picture(direct, stereo, false))
        }
    }

    @Test fun steamVrSplitsTheEyePairRegardlessOfTheSavedFlatGamePicture() {
        for (picture in listOf(GlassesSettings.PICTURE_AUTO, GlassesSettings.PICTURE_SCREEN, GlassesSettings.PICTURE_SBS)) {
            val settings = GlassesSettings(picture3d = picture, auto3d = true)
            for (stereo in listOf(false, true)) for (outputActive in listOf(false, true)) {
                val state = ultra.copy(sbs = stereo, width = if (stereo) 3840 else 1920)
                val projection = GlassesRendering.cardboard(base, settings, state, outputActive, true)
                assertEquals(GlassesRendering.Picture.STEREO, GlassesRendering.picture(settings, stereo, true))
                assertEquals(Cardboard.SOURCE_SBS, projection.source)
                assertEquals(!stereo, projection.mono)
                assertTrue(projection.enabled)
                assertTrue(projection.headTracking)
            }
        }
    }

    @Test fun stoppingSteamVrRestoresTheSavedVirtualScreen() {
        val settings = GlassesSettings(picture3d = GlassesSettings.PICTURE_SCREEN, auto3d = true,
            screenWidthDm = 18, screenDistanceDm = 30)
        val before = render(settings)
        assertEquals(Cardboard.SOURCE_SCREEN, before.source)
        assertEquals(Cardboard.SOURCE_SBS, render(settings, steamVr = true).source)
        assertEquals(before, render(settings))
        assertEquals(GlassesSettings.PICTURE_SCREEN, settings.picture3d)
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
            assertTrue(preview.enabled)
            assertTrue(preview.mono)
            assertEquals(Cardboard.SOURCE_SBS, preview.source)
            assertEquals(Cardboard.Viewer.NONE.id, preview.viewer)
            assertTrue(preview.headTracking)
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
