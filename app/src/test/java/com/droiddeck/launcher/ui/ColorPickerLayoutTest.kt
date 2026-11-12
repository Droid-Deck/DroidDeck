package com.droiddeck.launcher.ui

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w832dp-h468dp-land-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ColorPickerLayoutTest {
    @get:Rule val compose = createRobolectricComposeRule()

    @Test fun onALandscapeHandheldThePickerEndsWellAboveTheScreenEdge() {
        compose.setContent {
            DroidDeckTheme(theme = Themes.GRAPHITE) { ColorPickerDialog("Highlight color", 0xFFE53935.toInt(), onDismiss = {}) {} }
        }
        compose.mainClock.advanceTimeBy(1_000)
        val ok = compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.common_ok)).getUnclippedBoundsInRoot()
        val title = compose.onNodeWithText("Highlight color").getUnclippedBoundsInRoot()
        // The dialog is centered, so the space under OK equals the space above the title; 40 dp keeps both clear of the edges.
        assertTrue("OK ends at ${ok.bottom}, title ${title.top}", ok.bottom <= 468.dp - 40.dp)
        assertTrue("title starts at ${title.top}", title.top >= 40.dp)
    }
}
