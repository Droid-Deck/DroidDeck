package com.droiddeck.launcher.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.droiddeck.launcher.handtracking.TrackingCameras
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w960dp-h540dp-land", application = android.app.Application::class)
class HandCameraPromptTest {
    @get:Rule val compose = createRobolectricComposeRule()

    @Test fun bothCamerasOfferAChoiceWithTheBackOnePicked() {
        var allowed: String? = "unset"
        compose.setContent {
            DroidDeckTheme { HandCameraPrompt(setOf(TrackingCameras.REAR, TrackingCameras.FRONT), onAllow = { allowed = it }, onNotNow = {}) }
        }
        compose.onNodeWithTag("hand-camera-rear").assertExists()
        compose.onNodeWithTag("hand-camera-allow").performClick()
        assertEquals(TrackingCameras.REAR, allowed)
    }

    @Test fun theFrontCameraCanBeChosen() {
        var allowed: String? = "unset"
        compose.setContent {
            DroidDeckTheme { HandCameraPrompt(setOf(TrackingCameras.REAR, TrackingCameras.FRONT), onAllow = { allowed = it }, onNotNow = {}) }
        }
        compose.onNodeWithTag("hand-camera-front").performClick()
        compose.onNodeWithTag("hand-camera-allow").performClick()
        assertEquals(TrackingCameras.FRONT, allowed)
    }

    @Test fun aSingleCameraIsUsedWithoutAsking() {
        var allowed: String? = "unset"
        compose.setContent {
            DroidDeckTheme { HandCameraPrompt(setOf(TrackingCameras.REAR), onAllow = { allowed = it }, onNotNow = {}) }
        }
        compose.onAllNodesWithTag("hand-camera-rear").assertCountEquals(0)
        compose.onAllNodesWithTag("hand-camera-front").assertCountEquals(0)
        compose.onNodeWithTag("hand-camera-allow").performClick()
        assertEquals(null, allowed)
    }

    @Test fun notNowDeclines() {
        var declined = false
        compose.setContent {
            DroidDeckTheme { HandCameraPrompt(setOf(TrackingCameras.FRONT), onAllow = {}, onNotNow = { declined = true }) }
        }
        compose.onNodeWithTag("hand-camera-not-now").performClick()
        assertEquals(true, declined)
    }
}
