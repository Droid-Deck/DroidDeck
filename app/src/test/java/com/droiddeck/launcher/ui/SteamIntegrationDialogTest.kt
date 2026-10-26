package com.droiddeck.launcher.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
class SteamIntegrationDialogTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val state = mutableStateOf(SteamIntegration.State())
    private val selected = mutableStateOf<Feature?>(null)
    private val requests = mutableListOf<Feature>()
    private var dismissed = false

    private fun show() = compose.setContent {
        DroidDeckTheme {
            SteamIntegrationDialog(state.value, selected.value, { selected.value = it },
                { requests.add(it) }, {}, { dismissed = true })
        }
    }

    @Test fun browsingAndBackingOutDoesNotRequestPermissions() {
        show()
        compose.onNodeWithTag("integration-brightness").performClick()
        compose.onNodeWithTag("integration-continue").assertIsDisplayed()
        assertTrue(requests.isEmpty())
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Done").performClick()
        assertTrue(dismissed)
        assertTrue(requests.isEmpty())
    }

    @Test fun shortcutsExplainTheHomeDependencyBeforeRequestingButtonAccess() {
        show()
        compose.onNodeWithTag("integration-shortcuts").performScrollTo().performClick()
        compose.onNodeWithTag("integration-continue").assertIsDisplayed().performClick()
        assertEquals(listOf(Feature.SHORTCUTS), requests)
        compose.runOnIdle { state.value = state.value.copy(home = true) }
        compose.onNodeWithText("Continue").assertIsDisplayed()
        assertEquals(1, requests.size)
    }

    @Test fun returningFromSettingsRefreshesActionsWithoutStartingTheNextPermission() {
        state.value = SteamIntegration.State(home = true)
        selected.value = Feature.SHORTCUTS
        show()
        compose.onNodeWithTag("integration-continue").performClick()
        assertEquals(listOf(Feature.SHORTCUTS), requests)
        compose.runOnIdle { state.value = state.value.copy(shortcuts = true) }
        compose.onNodeWithText("Open settings").assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithTag("integration-shortcuts").performScrollTo().assertIsDisplayed()
        assertEquals(1, requests.size)
    }
}
