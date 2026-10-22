package com.droiddeck.launcher.ui

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DialogSemanticsTest {
    @get:Rule val compose = createRobolectricComposeRule()

    @Test fun dialogTextNodesStaySeparateAndNonClickableInMergedSemanticsTree() {
        var closed = 0
        compose.setContent {
            DroidDeckTheme {
                val shown = remember { MutableTransitionState(true) }
                AppDialog(shown = shown, close = { closed++ }, label = "testDialog", wide = false) {
                    Text("Dialog Title")
                    Text("Dialog Hint")
                    Text("Body Copy")
                }
            }
        }
        compose.onNodeWithText("Dialog Title").assertExists().assertHasNoClickAction()
        compose.onNodeWithText("Dialog Hint").assertExists().assertHasNoClickAction()
        compose.onNodeWithText("Body Copy").assertExists().assertHasNoClickAction()

        compose.onNodeWithText("Dialog Title").performClick()
        compose.onNodeWithText("Body Copy").performClick()
        compose.waitForIdle()
        assertEquals(0, closed)
    }
}
