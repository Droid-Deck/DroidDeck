package com.droiddeck.launcher.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.droiddeck.launcher.core.SteamIntegration
import com.droiddeck.launcher.core.SteamIntegration.Feature
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w960dp-h540dp-land")
class SteamIntegrationGroupTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val state = mutableStateOf(SteamIntegration.State())
    private val requests = mutableListOf<Feature>()
    private var appInfo = 0
    private var location = 0

    private fun stateIs(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    private fun show() = compose.setContent {
        DroidDeckTheme {
            SteamIntegrationGroup(state.value, onFeature = { requests.add(it) }, onAppInfo = { appInfo++ }, onLocation = { location++ })
        }
    }

    @Test fun everySwitchRequestsOnlyItsOwnFeature() {
        state.value = SteamIntegration.State(home = true)
        show()
        compose.onNodeWithTag("integration-action-brightness").performClick()
        compose.onNodeWithTag("integration-action-home").performClick()
        compose.onNodeWithTag("integration-action-shortcuts").performClick()
        assertEquals(listOf(Feature.BRIGHTNESS, Feature.HOME, Feature.SHORTCUTS), requests)
    }

    @Test fun switchesReflectAndroidGrants() {
        state.value = SteamIntegration.State(brightness = true, home = true, shortcuts = true)
        show()
        compose.onNodeWithTag("integration-action-brightness").assert(stateIs("On"))
        compose.onNodeWithTag("integration-action-shortcuts").assert(stateIs("On"))
        compose.onNodeWithTag("integration-action-wifi").assert(stateIs("Off"))
        compose.onNodeWithText("3 of 4 on").assertExists()
    }

    @Test fun shortcutsWaitForHome() {
        show()
        compose.onNodeWithTag("integration-action-shortcuts").assertIsNotEnabled()
        compose.onNodeWithText("Needs DroidDeck as Home").assertExists()
        compose.runOnIdle { state.value = state.value.copy(home = true) }
        compose.onNodeWithTag("integration-action-shortcuts").assertIsEnabled()
    }

    @Test fun restrictedShortcutsPointToAppInfo() {
        state.value = SteamIntegration.State(home = true, shortcutsRestricted = true)
        show()
        compose.onNodeWithTag("integration-action-shortcuts").assertIsNotEnabled()
        compose.onNodeWithText("App info").performClick()
        assertEquals(1, appInfo)
        compose.runOnIdle { state.value = state.value.copy(shortcutsRestricted = false) }
        compose.onNodeWithText("App info").assertDoesNotExist()
        compose.onNodeWithTag("integration-action-shortcuts").assertIsEnabled()
    }

    @Test fun unconfirmedRestrictionOffersAppInfoAfterAnAttempt() {
        state.value = SteamIntegration.State(home = true)
        show()
        compose.onNodeWithText("App info").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(shortcutsTried = true) }
        compose.onNodeWithTag("integration-action-shortcuts").assertIsEnabled()
        compose.onNodeWithText("App info").performClick()
        assertEquals(1, appInfo)
    }

    @Test fun deniedLocationPointsToAppInfoWithoutTogglingWifi() {
        state.value = SteamIntegration.State(locationBlocked = true)
        show()
        compose.onNodeWithText("Location denied · allow it in App info").assertExists()
        compose.onNodeWithText("App info").performClick()
        assertEquals(1, location)
        assertTrue(requests.isEmpty())
    }
}
