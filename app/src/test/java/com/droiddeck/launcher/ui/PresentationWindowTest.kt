package com.droiddeck.launcher.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w640dp-h480dp-land", application = android.app.Application::class)
class PresentationWindowTest {
    @get:Rule val compose = createRobolectricComposeRule()

    @Test fun alertInsidePresentationRendersAndDismisses() {
        var dismissed = 0
        compose.setContent {
            CompositionLocalProvider(LocalPresentationWindow provides true) {
                GlideAlertDialog(
                    onDismissRequest = { dismissed++ },
                    confirmButton = { Text("Confirm", Modifier.tapOutside { dismissed += 10 }) },
                    title = { Text("Title") },
                    text = { Text("Body") },
                )
            }
        }
        compose.onNodeWithText("Title").assertIsDisplayed()
        compose.onNodeWithText("Body").assertIsDisplayed()
        compose.onNodeWithText("Confirm").performClick()
        assertEquals(10, dismissed)
    }

    @Test fun dialogInsidePresentationRendersItsContent() {
        var shown by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalPresentationWindow provides true) {
                if (shown) GlideDialog(onDismissRequest = { shown = false }) { Text("Panel") }
            }
        }
        compose.onNodeWithText("Panel").assertIsDisplayed()
    }

    @Test fun dialogOutsidePresentationKeepsThePlatformDialog() {
        compose.setContent { GlideDialog(onDismissRequest = {}) { Text("Panel") } }
        compose.onNodeWithText("Panel").assertIsDisplayed()
    }
}
