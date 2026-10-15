package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class StoreModeHeaderTest {
    @get:Rule val compose = createRobolectricComposeRule()

    @Test fun narrowHeaderWrapsAndKeepsEveryActionInsideThePane() {
        compose.setContent {
            DroidDeckTheme {
                Box(Modifier.width(220.dp).testTag("pane")) {
                    ModeHeader(
                        title = "Library (247)", query = "", onQuery = {}, placeholder = "Search",
                        tools = { Box(Modifier.width(96.dp).height(36.dp).testTag("sort")) },
                        trailing = {
                            Box(Modifier.width(68.dp).height(36.dp).testTag("refresh"))
                            Box(Modifier.width(120.dp).height(36.dp).testTag("signout"))
                        },
                    )
                }
            }
        }
        compose.waitForIdle()
        val pane = compose.onNodeWithTag("pane").fetchSemanticsNode().boundsInRoot
        val actions = listOf("sort", "refresh", "signout").map {
            compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot
        }
        actions.forEach { bounds ->
            assertTrue("Action is clipped at the pane's left edge: $bounds", bounds.left >= pane.left)
            assertTrue("Action overflows the pane's right edge: $bounds", bounds.right <= pane.right)
        }
        assertTrue("Oversized actions should wrap onto another row", actions.map { it.top }.distinct().size > 1)
    }
}
