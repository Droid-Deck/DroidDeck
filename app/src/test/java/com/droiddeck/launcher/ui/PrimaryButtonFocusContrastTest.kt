package com.droiddeck.launcher.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrimaryButtonFocusContrastTest {
    @get:Rule val compose = createRobolectricComposeRule()

    @Test fun focusedPrimaryButtonDoesNotClaimAColorOverlayAboveItsLabel() {
        lateinit var glide: FocusGlide
        var clicks = 0
        compose.setContent {
            val view = LocalView.current
            glide = remember(view) { FocusGlide(view) }
            DroidDeckTheme(Themes.ELECTRIC) {
                CompositionLocalProvider(LocalFocusGlide provides glide) {
                    PrimaryButton("Play") { clicks++ }
                }
            }
        }
        val button = compose.onNodeWithText("Play")
        button.performSemanticsAction(SemanticsActions.RequestFocus)
        button.assertIsFocused()
        compose.runOnIdle { assertNull(glide.current) }
        button.performClick()
        compose.runOnIdle {
            assertEquals(1, clicks)
            assertNull(glide.current)
        }
    }
}
