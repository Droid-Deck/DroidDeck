package com.droiddeck.launcher.ui

import android.view.Display
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.droiddeck.launcher.R
import com.droiddeck.launcher.glasses.GlassesSettings
import com.droiddeck.launcher.glasses.GlassesState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GlassesPanelTest {
    @get:Rule val compose = createRobolectricComposeRule()

    @Test fun flatResolutionMenuShowsPerEyeResolutionsBesideRefresh() {
        show()
        compose.onNodeWithTag("setting-glasses-hz").assertExists()
        compose.onNodeWithTag("setting-glasses-resolution-two_d").performScrollTo().performClick()
        compose.onNodeWithText("1920 × 1080").assertExists()
        compose.onNodeWithText("1920 × 1200").assertExists()
    }

    @Test fun stereoResolutionHasItsOwnPreference() {
        show(stereo = true)
        compose.onNodeWithTag("setting-glasses-resolution-three_d").assertExists()
        compose.onNodeWithTag("setting-glasses-resolution-two_d").assertDoesNotExist()
        compose.onNodeWithTag("setting-glasses-3d-hz").assertExists()
    }

    @Test fun vrResolutionDoesNotEditTheOrdinaryStereoPreference() {
        show(stereo = true, steamVrRunning = true, tab = GlassesTab.VR)
        compose.onNodeWithTag("setting-glasses-resolution-vr").performScrollTo().performClick()
        compose.onNodeWithText("1920 × 1080").assertExists()
        compose.onNodeWithTag("setting-glasses-resolution-three_d").assertDoesNotExist()
    }

    @Test fun homeDisplayCanConfigureBothOrdinaryResolutionProfiles() {
        show(inSession = false)
        for (profile in listOf("two_d", "three_d"))
            compose.onNodeWithTag("setting-glasses-resolution-$profile").assertExists()
    }

    @Test fun nativeThirtyHzAppearsInTheFlatRefreshMenu() {
        show(supports30Hz = true)
        compose.onNodeWithTag("setting-glasses-hz").performScrollTo().performClick()
        compose.onNodeWithText("30 Hz").assertExists()
    }

    @Test fun nativeThirtyHzAppearsInStereoEvenWithoutNinetyHzSupport() {
        show(stereo = true, supports30Hz = true, sbs90 = false)
        compose.onNodeWithTag("setting-glasses-3d-hz").performScrollTo().performClick()
        compose.onNodeWithText("30 Hz").assertExists()
        compose.onNodeWithText("90 Hz").assertDoesNotExist()
    }

    @Test fun unsupportedThirtyHzIsAbsentFromTheRefreshMenu() {
        show()
        compose.onNodeWithTag("setting-glasses-hz").performScrollTo().performClick()
        compose.onNodeWithText("30 Hz").assertDoesNotExist()
    }

    @Test fun mirroredNativeThirtyHzExplainsTheDedicatedOutputRequirement() {
        show(settings = GlassesSettings(gameOnGlasses = false), supports30Hz = true)
        compose.onNodeWithText(text(R.string.glasses_refresh_30_hint)).assertExists()
        compose.onNodeWithTag("setting-glasses-hz").performScrollTo().performClick()
        compose.onNodeWithText("30 Hz").assertDoesNotExist()
    }

    @Test fun automaticFlatGameOffersVirtualScreenInsteadOfIneffectiveSliders() {
        show()
        compose.onNodeWithTag("glasses-width").assertDoesNotExist()
        compose.onNodeWithTag("glasses-distance").assertDoesNotExist()
        compose.onNodeWithTag("glasses-vr-fov").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.glasses_screen_use)).assertExists()
        compose.onNodeWithText(text(R.string.glasses_screen_flat_hint)).assertExists()
        compose.onNodeWithTag("setting-glasses-hz").assertExists()
        compose.onNodeWithTag("setting-glasses-3d-hz").assertDoesNotExist()
    }

    @Test fun virtualScreenEnablesDimensionsInTwoDimensionalOutput() {
        show(settings = GlassesSettings(picture3d = GlassesSettings.PICTURE_SCREEN))
        compose.onNodeWithTag("glasses-width").assertIsEnabled()
        compose.onNodeWithTag("glasses-distance").assertIsEnabled()
        compose.onNodeWithTag("glasses-vr-fov").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.glasses_screen_flat_hint)).assertDoesNotExist()
    }

    @Test fun automaticSteamVrShowsFieldOfViewAndStereoRefresh() {
        show(stereo = true, steamVrRunning = true, tab = GlassesTab.VR)
        compose.onNodeWithTag("glasses-width").assertDoesNotExist()
        compose.onNodeWithTag("glasses-distance").assertDoesNotExist()
        compose.onNodeWithTag("glasses-vr-fov").assertIsEnabled()
        compose.onNodeWithTag("setting-glasses-hz").assertDoesNotExist()
        compose.onNodeWithTag("setting-glasses-3d-hz").assertExists()
        compose.onNodeWithTag("setting-glasses-auto3d").assertExists()
    }

    @Test fun mirroredSteamVrIn2dKeepsFieldOfViewAndRecenter() {
        show(steamVrRunning = true, outputActive = false, tab = null)
        assertMirroredVrControls()
    }

    @Test fun mirroredSteamVrIn3dKeepsFieldOfViewAndRecenter() {
        show(stereo = true, steamVrRunning = true, outputActive = false, tab = null)
        assertMirroredVrControls()
    }

    @Test fun mirroredSteamVrUsesStereoControlsWithASavedVirtualScreen() {
        show(settings = GlassesSettings(picture3d = GlassesSettings.PICTURE_SCREEN),
            steamVrRunning = true, outputActive = false, tab = null)
        compose.onNodeWithTag("glasses-width").assertDoesNotExist()
        compose.onNodeWithTag("glasses-distance").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.glasses_screen_direct_hint)).assertExists()
        assertMirroredVrControls()
    }

    @Test fun automatic3dForVrUsesStereoControlsWithASavedVirtualScreen() {
        show(settings = GlassesSettings(picture3d = GlassesSettings.PICTURE_SCREEN, auto3d = true),
            stereo = true, steamVrRunning = true, tab = null)
        compose.onNodeWithTag("glasses-width").assertDoesNotExist()
        compose.onNodeWithTag("glasses-distance").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.glasses_screen_direct_hint)).assertExists()
        assertMirroredVrControls()
    }

    private fun assertMirroredVrControls() {
        compose.onNodeWithTag("glasses-vr-fov").assertIsEnabled()
        compose.onNodeWithText(text(R.string.cardboard_recenter_button)).assertExists()
        compose.onNodeWithText(text(R.string.glasses_screen_no_output)).assertDoesNotExist()
    }

    @Test fun automaticStereoOutputDoesNotEnable3dofOrVirtualScreenDimensions() {
        show(stereo = true)
        compose.onNodeWithTag("setting-glasses-3dof").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.widgets_off)))
        compose.onNodeWithTag("glasses-width").assertDoesNotExist()
        compose.onNodeWithTag("glasses-distance").assertDoesNotExist()
        compose.onNodeWithTag("glasses-vr-fov").assertDoesNotExist()
        compose.onNodeWithTag("setting-glasses-hz").assertDoesNotExist()
        compose.onNodeWithTag("setting-glasses-3d-hz").assertExists()
        compose.onNodeWithText(text(R.string.glasses_screen_flat_hint)).assertExists()
        compose.onNodeWithText(text(R.string.glasses_screen_sbs_hint)).assertDoesNotExist()
    }

    @Test fun automaticFlatSbsKeepsTrackingInactive() {
        show(stereo = true, tab = GlassesTab.TRACKING)
        compose.onNodeWithText(text(R.string.glasses_tracking_flat_hint)).assertExists()
        compose.onNodeWithText(text(R.string.cardboard_recenter_button)).assertDoesNotExist()
    }

    @Test fun flatGame3dofToggleIsIndependentOfSbsAndShowsScreenControls() {
        show(settings = GlassesSettings(picture3d = GlassesSettings.PICTURE_SCREEN))
        compose.onNodeWithTag("setting-glasses-3dof").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.widgets_on)))
        compose.onNodeWithTag("setting-glasses-3d").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.widgets_off)))
        compose.onNodeWithTag("glasses-width").assertIsEnabled()
        compose.onNodeWithTag("glasses-distance").assertIsEnabled()
    }

    @Test fun separate3dofToggleIsHiddenWithoutHeadTrackingSupport() {
        show(supportsScreen3dof = false)
        compose.onNodeWithTag("setting-glasses-3dof").assertDoesNotExist()
    }

    @Test fun virtualScreenRecenterExplainsHowToSetTheHeight() {
        show(settings = GlassesSettings(picture3d = GlassesSettings.PICTURE_SCREEN), tab = GlassesTab.TRACKING)
        compose.onNodeWithText(text(R.string.glasses_recenter_screen_hint)).assertExists()
        compose.onNodeWithText(text(R.string.cardboard_recenter_button)).assertExists()
    }

    @Test fun gameWithoutGlassesOutputExplainsWhyGeometryIsUnavailable() {
        show(settings = GlassesSettings(picture3d = GlassesSettings.PICTURE_SCREEN), outputActive = false)
        compose.onNodeWithTag("glasses-width").assertDoesNotExist()
        compose.onNodeWithTag("glasses-distance").assertDoesNotExist()
        compose.onNodeWithTag("glasses-vr-fov").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.glasses_screen_no_output)).assertExists()
    }

    @Test fun homeCanConfigureTheNextSessionWithAnExplanation() {
        show(inSession = false, outputActive = false)
        compose.onNodeWithTag("glasses-width").assertIsEnabled()
        compose.onNodeWithTag("glasses-distance").assertIsEnabled()
        compose.onNodeWithTag("glasses-vr-fov").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.glasses_screen_app_wide)).assertExists()
    }

    @Test fun flatTrackingExplainsTheModeAndHidesInactiveRecenter() {
        show(tab = GlassesTab.TRACKING)
        compose.onNodeWithText(text(R.string.glasses_tracking_flat_hint)).assertExists()
        compose.onNodeWithText(text(R.string.cardboard_recenter_button)).assertDoesNotExist()
    }

    @Test fun virtualScreenGeometryRemainsAvailableWhenUsbControlDisconnects() {
        show(settings = GlassesSettings(picture3d = GlassesSettings.PICTURE_SCREEN), controlAvailable = false)
        compose.onNodeWithTag("setting-glasses-picture").assertIsEnabled()
        compose.onNodeWithTag("glasses-width").assertIsEnabled()
        compose.onNodeWithTag("glasses-distance").assertIsEnabled()
        compose.onNodeWithTag("setting-glasses-hz").assertDoesNotExist()
        compose.onNodeWithTag("setting-glasses-3d").assertDoesNotExist()
        compose.onNodeWithTag("setting-glasses-3dof").assertDoesNotExist()
    }

    @Test fun runningSteamVrFieldOfViewRemainsAvailableWhenUsbControlDisconnects() {
        show(stereo = true, steamVrRunning = true, controlAvailable = false, tab = GlassesTab.VR)
        compose.onNodeWithTag("glasses-vr-fov").assertIsEnabled()
        compose.onNodeWithTag("setting-glasses-picture").assertDoesNotExist()
        compose.onNodeWithTag("setting-glasses-3d-hz").assertDoesNotExist()
        compose.onNodeWithTag("setting-glasses-3d").assertDoesNotExist()
    }

    @Test fun sideBySideGameWithoutSteamVrDoesNotOfferIneffectiveFieldOfView() {
        show(settings = GlassesSettings(picture3d = GlassesSettings.PICTURE_SBS), stereo = true)
        compose.onNodeWithTag("glasses-width").assertDoesNotExist()
        compose.onNodeWithTag("glasses-distance").assertDoesNotExist()
        compose.onNodeWithTag("glasses-vr-fov").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.glasses_screen_sbs_hint)).assertExists()
        compose.onNodeWithText(text(R.string.glasses_screen_direct_hint)).assertDoesNotExist()
    }

    @Test fun sideBySideTrackingWithoutSteamVrExplainsTheScopeAndHidesRecenter() {
        show(settings = GlassesSettings(picture3d = GlassesSettings.PICTURE_SBS), stereo = true,
            tab = GlassesTab.TRACKING)
        compose.onNodeWithText(text(R.string.glasses_tracking_sbs_hint)).assertExists()
        compose.onNodeWithText(text(R.string.cardboard_recenter_button)).assertDoesNotExist()
    }

    @Test fun homeVrCanConfigureResolutionAndFieldOfViewBeforeLaunching() {
        show(inSession = false, tab = GlassesTab.VR)
        compose.onNodeWithTag("setting-glasses-resolution-vr").assertExists()
        compose.onNodeWithTag("glasses-vr-fov").assertIsEnabled()
        compose.onNodeWithTag("setting-glasses-auto3d").assertExists()
        compose.onNodeWithTag("setting-glasses-picture").assertDoesNotExist()
        compose.onNodeWithTag("setting-glasses-resolution-two_d").assertDoesNotExist()
    }

    @Test fun displayKeepsVrAndHardwareControlsInTheirOwnTabs() {
        show(inSession = false)
        compose.onNodeWithTag("setting-glasses-auto3d").assertDoesNotExist()
        compose.onNodeWithTag("setting-glasses-resolution-vr").assertDoesNotExist()
        compose.onNodeWithTag("setting-glasses-sunblock").assertDoesNotExist()
        compose.onNodeWithTag("setting-glasses-tracking").assertDoesNotExist()
    }

    @Test fun recenterIsHiddenWhenHeadTrackingIsOff() {
        show(settings = GlassesSettings(headTracking = false, picture3d = GlassesSettings.PICTURE_SCREEN))
        compose.onNodeWithText(text(R.string.cardboard_recenter_button)).assertDoesNotExist()
    }

    @Test fun trackedMenusCanRecenterWithoutStartingASession() {
        var recenters = 0
        show(settings = GlassesSettings(picture3d = GlassesSettings.PICTURE_SCREEN), inSession = false,
            onRecenter = { recenters++ })

        compose.onNodeWithText(text(R.string.glasses_recenter_screen_hint)).assertExists()
        compose.onNodeWithText(text(R.string.cardboard_recenter_button)).performClick()
        org.junit.Assert.assertEquals(1, recenters)
    }

    @Test fun positionalMenusUseTheSameRecenterAction() {
        var recenters = 0
        show(settings = GlassesSettings(picture3d = GlassesSettings.PICTURE_SCREEN, positionalTracking = true),
            supportsSpatial = true, inSession = false, onRecenter = { recenters++ })

        compose.onNodeWithText(text(R.string.glasses_recenter_screen_hint)).assertExists()
        compose.onNodeWithText(text(R.string.cardboard_recenter_button)).performClick()
        org.junit.Assert.assertEquals(1, recenters)
    }

    @Test fun menusWithoutDedicatedGlassesOutputDoNotOfferRecenter() {
        show(settings = GlassesSettings(picture3d = GlassesSettings.PICTURE_SCREEN, gameOnGlasses = false),
            inSession = false)
        compose.onNodeWithText(text(R.string.cardboard_recenter_button)).assertDoesNotExist()
    }

    @Test fun spatialSteamVrIncludesHeadTrackingAndRestoresTheSavedVirtualScreenSwitch() {
        val vrRunning = androidx.compose.runtime.mutableStateOf(true)
        val saved = GlassesSettings(headTracking = false, positionalTracking = true,
            picture3d = GlassesSettings.PICTURE_SCREEN)
        show(settings = saved, supportsSpatial = true, tab = GlassesTab.TRACKING, observedSteamVr = vrRunning)

        compose.onNodeWithTag("setting-glasses-tracking").assertIsNotEnabled().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.widgets_on)))
        compose.onNodeWithText(text(R.string.glasses_head_tracking_spatial_vr_hint)).assertExists()
        compose.onNodeWithText(text(R.string.cardboard_recenter_button)).assertDoesNotExist()

        compose.runOnIdle { vrRunning.value = false }

        compose.onNodeWithTag("setting-glasses-tracking").assertIsEnabled().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.widgets_off)))
        compose.onNodeWithText(text(R.string.glasses_head_tracking_spatial_hint)).assertExists()
        compose.onNodeWithText(text(R.string.cardboard_recenter_button)).assertDoesNotExist()
    }

    @Test fun rotationOnlySteamVrKeepsTheHeadTrackingSwitchOptional() {
        show(settings = GlassesSettings(headTracking = false), supportsSpatial = true,
            steamVrRunning = true, tab = GlassesTab.TRACKING)

        compose.onNodeWithTag("setting-glasses-tracking").assertIsEnabled().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.widgets_off)))
        compose.onNodeWithText(text(R.string.glasses_head_tracking_hint)).assertExists()
        compose.onNodeWithText(text(R.string.cardboard_recenter_button)).assertDoesNotExist()
    }

    @Test fun spatialPreferencesDoNotClaimHeadTrackingForPhoneOutput() {
        val noDisplay = androidx.compose.runtime.mutableStateOf(GlassesState(present = true,
            connected = true, controllable = true, supportsSpatial = true))
        show(settings = GlassesSettings(headTracking = false, positionalTracking = true),
            steamVrRunning = true, outputActive = false, tab = GlassesTab.TRACKING, observedState = noDisplay)

        compose.onNodeWithTag("setting-glasses-tracking").assertIsEnabled().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.widgets_off)))
        compose.onNodeWithText(text(R.string.glasses_screen_no_output)).assertExists()
        compose.onNodeWithText(text(R.string.cardboard_recenter_button)).assertDoesNotExist()
    }

    @Test fun experimentalJoyConsAreOffAndRequirePositionalTracking() {
        show(supportsSpatial = true, tab = GlassesTab.TRACKING)
        compose.onNodeWithText(text(R.string.glasses_experimental).uppercase()).assertExists()
        compose.onNodeWithTag("setting-glasses-joycons").assertIsNotEnabled().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.widgets_off)))
    }

    @Test fun experimentalGroupFollowsOrdinaryTrackingAndEnablesJoyCons() {
        show(supportsSpatial = true, settings = GlassesSettings(positionalTracking = true), tab = GlassesTab.TRACKING)
        compose.onNodeWithTag("setting-glasses-joycons").performScrollTo().assertIsEnabled()
        val ordinary = compose.onNodeWithTag("setting-glasses-tracking").fetchSemanticsNode().positionInRoot.y
        val experimental = compose.onNodeWithText(text(R.string.glasses_experimental).uppercase()).fetchSemanticsNode().positionInRoot.y
        org.junit.Assert.assertTrue(experimental > ordinary)
        compose.onNodeWithTag("setting-glasses-hands").assertExists()
        compose.onNodeWithTag("setting-glasses-steady-hand-aim").assertDoesNotExist()
    }

    @Test fun steadyHandAimIsOptionalAndOffByDefault() {
        show(supportsSpatial = true, settings = GlassesSettings(positionalTracking = true, handControllers = true),
            tab = GlassesTab.TRACKING)
        compose.onNodeWithTag("setting-glasses-steady-hand-aim").performScrollTo().assertIsEnabled().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.widgets_off)))
        compose.onNodeWithText(text(R.string.glasses_steady_hand_aim_hint)).assertExists()
    }

    @Test fun savedSteadyHandAimIsHiddenInJoyConMode() {
        show(supportsSpatial = true, settings = GlassesSettings(positionalTracking = true, joyConTracking = true, steadyHandAim = true),
            tab = GlassesTab.TRACKING)
        compose.onNodeWithTag("setting-glasses-steady-hand-aim").assertDoesNotExist()
    }

    @Test fun ultraControlsDisappearOnDisconnectAndForOtherGlassesWithSavedPreferences() {
        val state = androidx.compose.runtime.mutableStateOf(GlassesState(present = true, connected = true,
            controllable = true, supportsSpatial = true, model = "VITURE Luma Ultra"))
        show(settings = GlassesSettings(positionalTracking = true, handControllers = true, joyConTracking = true),
            tab = GlassesTab.TRACKING, observedState = state)
        compose.onNodeWithTag("setting-glasses-position").assertExists()
        compose.onNodeWithTag("setting-glasses-hands").assertExists()
        compose.onNodeWithTag("setting-glasses-joycons").assertExists()
        val otherStates = listOf(state.value.copy(connected = false)) +
            listOf("VITURE Luma Ultra", "VITURE Luma Pro", "VITURE Pro", "XREAL", "RayNeo").map {
                state.value.copy(model = it, supportsSpatial = false)
            }
        for (other in otherStates) {
            compose.runOnIdle { state.value = other }
            compose.onNodeWithTag("setting-glasses-position").assertDoesNotExist()
            compose.onNodeWithTag("setting-glasses-hands").assertDoesNotExist()
            compose.onNodeWithTag("setting-glasses-steady-hand-aim").assertDoesNotExist()
            compose.onNodeWithTag("setting-glasses-joycons").assertDoesNotExist()
            compose.onNodeWithText(text(R.string.glasses_spatial_setup)).assertDoesNotExist()
            compose.onNodeWithText(text(R.string.glasses_eye_height)).assertDoesNotExist()
            compose.onNodeWithText(text(R.string.glasses_floor)).assertDoesNotExist()
        }
        compose.runOnIdle { state.value = state.value.copy(supportsSpatial = true) }
        compose.onNodeWithTag("setting-glasses-position").assertExists()
    }

    private fun show(
        settings: GlassesSettings = GlassesSettings(),
        stereo: Boolean = false,
        steamVrRunning: Boolean = false,
        inSession: Boolean = true,
        outputActive: Boolean = inSession,
        controlAvailable: Boolean = true,
        supportsScreen3dof: Boolean = true,
        supportsSpatial: Boolean = false,
        supports30Hz: Boolean = false,
        sbs90: Boolean = true,
        tab: String? = GlassesTab.DISPLAY,
        observedState: androidx.compose.runtime.State<GlassesState>? = null,
        observedSteamVr: androidx.compose.runtime.State<Boolean>? = null,
        onRecenter: (() -> Unit)? = {},
    ) {
        val state = GlassesState(present = true, connected = controlAvailable, controllable = true,
            displayId = Display.DEFAULT_DISPLAY + 1, width = if (stereo) 3840 else 1920, height = 1200,
            model = "VITURE Luma Ultra", sbs = stereo, sbs90 = sbs90, supportsScreen3dof = supportsScreen3dof,
            supports30Hz = supports30Hz, supportsSpatial = supportsSpatial, resolutions = listOf(
                com.droiddeck.launcher.glasses.GlassesResolution(1920, 1080),
                com.droiddeck.launcher.glasses.GlassesResolution(1920, 1200)))
        compose.setContent {
            DroidDeckTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    GlassesGroups(rememberMenuHost(), observedState?.value ?: state, settings,
                        track = { key ->
                            when (key) {
                                "glasses-width", "glasses-distance", "glasses-vr-fov" -> Modifier.testTag(key)
                                else -> Modifier
                            }
                        }, onRecenter = onRecenter,
                        inSession = inSession, outputActive = outputActive,
                        steamVrRunning = observedSteamVr?.value ?: steamVrRunning, tab = tab)
                }
            }
        }
    }

    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)
}
