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

    private fun show() {
        compose.setContent {
            DroidDeckTheme(theme = Themes.GRAPHITE) { ColorPickerDialog("Highlight color", 0xFFE53935.toInt(), onDismiss = {}) {} }
        }
        compose.mainClock.advanceTimeBy(1_000)
    }

    private fun ok() = compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.common_ok)).getUnclippedBoundsInRoot()

    /** OK sits inside the dialog's 12 dp margin and 16 dp padding, so the whole dialog fits and nothing has to scroll down to it. */
    private fun assertOkOnScreen() {
        val height = RuntimeEnvironment.getApplication().resources.configuration.screenHeightDp.dp
        val ok = ok()
        assertTrue("OK ends at ${ok.bottom} on a $height screen", ok.bottom <= height - 28.dp)
        assertTrue("OK starts at ${ok.top}", ok.top >= 0.dp)
    }

    @Test fun onALandscapeHandheldThePickerEndsWellAboveTheScreenEdge() {
        show()
        val ok = ok()
        val title = compose.onNodeWithText("Highlight color").getUnclippedBoundsInRoot()
        // The dialog is centered, so the space under OK equals the space above the title; 40 dp keeps both clear of the edges.
        assertTrue("OK ends at ${ok.bottom}, title ${title.top}", ok.bottom <= 468.dp - 40.dp)
        assertTrue("title starts at ${title.top}", title.top >= 40.dp)
    }

    @Test @Config(qualifiers = "w740dp-h360dp-land-420dpi")
    fun onAShortLandscapePhoneOkIsOnScreen() { show(); assertOkOnScreen() }

    @Test @Config(qualifiers = "w800dp-h384dp-land")
    fun onAShortWideScreenOkIsOnScreen() { show(); assertOkOnScreen() }

    @Test @Config(qualifiers = "w411dp-h891dp-port-420dpi")
    fun inPortraitOkIsOnScreen() { show(); assertOkOnScreen() }
}
