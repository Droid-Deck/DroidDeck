package com.droiddeck.launcher.ui

import android.view.Display
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
        show(stereo = true, steamVrRunning = true)
        compose.onNodeWithTag("glasses-width").assertDoesNotExist()
        compose.onNodeWithTag("glasses-distance").assertDoesNotExist()
        compose.onNodeWithTag("glasses-vr-fov").assertIsEnabled()
        compose.onNodeWithTag("setting-glasses-hz").assertDoesNotExist()
        compose.onNodeWithTag("setting-glasses-3d-hz").assertExists()
        compose.onNodeWithText(text(R.string.glasses_screen_direct_hint)).assertExists()
    }

    @Test fun mirroredSteamVrIn2dKeepsFieldOfViewAndRecenter() {
        show(steamVrRunning = true, outputActive = false, tab = null)
        assertMirroredVrControls()
    }

    @Test fun mirroredSteamVrIn3dKeepsFieldOfViewAndRecenter() {
        show(stereo = true, steamVrRunning = true, outputActive = false, tab = null)
        assertMirroredVrControls()
    }

    @Test fun mirroredSteamVrVirtualScreenKeepsGeometryAndRecenter() {
        show(settings = GlassesSettings(picture3d = GlassesSettings.PICTURE_SCREEN),
            steamVrRunning = true, outputActive = false, tab = null)
        compose.onNodeWithTag("glasses-width").assertIsEnabled()
        compose.onNodeWithTag("glasses-distance").assertIsEnabled()
        compose.onNodeWithText(text(R.string.cardboard_recenter_button)).assertExists()
        compose.onNodeWithText(text(R.string.glasses_screen_no_output)).assertDoesNotExist()
    }

    private fun assertMirroredVrControls() {
        compose.onNodeWithTag("glasses-vr-fov").assertIsEnabled()
        compose.onNodeWithText(text(R.string.cardboard_recenter_button)).assertExists()
        compose.onNodeWithText(text(R.string.glasses_screen_no_output)).assertDoesNotExist()
    }

    @Test fun automaticStereoOutputWithoutSteamVrUsesVirtualScreenDimensions() {
        show(stereo = true)
        compose.onNodeWithTag("glasses-width").assertIsEnabled()
        compose.onNodeWithTag("glasses-distance").assertIsEnabled()
        compose.onNodeWithTag("glasses-vr-fov").assertDoesNotExist()
        compose.onNodeWithTag("setting-glasses-hz").assertDoesNotExist()
        compose.onNodeWithTag("setting-glasses-3d-hz").assertExists()
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
        compose.onNodeWithTag("glasses-vr-fov").assertIsEnabled()
        compose.onNodeWithText(text(R.string.glasses_screen_next_session)).assertExists()
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
    }

    @Test fun runningSteamVrFieldOfViewRemainsAvailableWhenUsbControlDisconnects() {
        show(stereo = true, steamVrRunning = true, controlAvailable = false)
        compose.onNodeWithTag("glasses-vr-fov").assertIsEnabled()
        compose.onNodeWithTag("setting-glasses-picture").assertIsEnabled()
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

    private fun show(
        settings: GlassesSettings = GlassesSettings(),
        stereo: Boolean = false,
        steamVrRunning: Boolean = false,
        inSession: Boolean = true,
        outputActive: Boolean = inSession,
        controlAvailable: Boolean = true,
        tab: String? = GlassesTab.DISPLAY,
    ) {
        val state = GlassesState(present = true, connected = controlAvailable, controllable = true,
            displayId = Display.DEFAULT_DISPLAY + 1, width = if (stereo) 3840 else 1920, height = 1200,
            model = "VITURE Luma Ultra", sbs = stereo, sbs90 = true)
        compose.setContent {
            DroidDeckTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    GlassesGroups(rememberMenuHost(), state, settings,
                        track = { key ->
                            when (key) {
                                "glasses-width", "glasses-distance", "glasses-vr-fov" -> Modifier.testTag(key)
                                else -> Modifier
                            }
                        }, onRecenter = {},
                        inSession = inSession, outputActive = outputActive,
                        steamVrRunning = steamVrRunning, tab = tab)
                }
            }
        }
    }

    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)
}
