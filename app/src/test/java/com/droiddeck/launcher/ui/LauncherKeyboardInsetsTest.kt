package com.droiddeck.launcher.ui

import android.graphics.Insets
import android.view.View
import android.view.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w400dp-h800dp-mdpi")
class LauncherKeyboardInsetsTest {
    @get:Rule val compose = createRobolectricComposeRule()

    @Test fun keyboardAndRoundedCornerShareBottomSpace() {
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            Box(Modifier.fillMaxSize().testTag("window")) {
                Box(Modifier.fillMaxSize().launcherSafePadding(FullscreenSafeInsets(bottom = 40))) {
                    Box(Modifier.fillMaxSize().imePadding()) {
                        Box(Modifier.fillMaxSize().testTag("page"))
                    }
                }
            }
        }

        fun keyboard(height: Int) = compose.runOnIdle {
            val type = WindowInsets.Type.ime()
            view.dispatchApplyWindowInsets(WindowInsets.Builder()
                .setInsets(type, Insets.of(0, 0, 0, height))
                .setVisible(type, height > 0)
                .build())
        }

        fun assertBottomSpace(pixels: Int) {
            val root = compose.onNodeWithTag("window").getUnclippedBoundsInRoot()
            val page = compose.onNodeWithTag("page").getUnclippedBoundsInRoot()
            assertEquals(pixels.dp, root.bottom - page.bottom)
        }

        assertBottomSpace(40)
        keyboard(300)
        assertBottomSpace(300)
        keyboard(0)
        assertBottomSpace(40)
    }
}
