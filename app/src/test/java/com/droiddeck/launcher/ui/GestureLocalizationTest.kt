package com.droiddeck.launcher.ui

import android.app.Application
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.droiddeck.launcher.R
import com.droiddeck.launcher.input.GesturePrefs
import com.droiddeck.launcher.input.TouchGestureConfig
import com.droiddeck.launcher.input.TouchscreenGestures.Gesture
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w400dp-h850dp-port")
class GestureLocalizationTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clearPrefs() {
        context.getSharedPreferences("touch_gestures", 0).edit().clear().commit()
    }

    @Test
    @Config(qualifiers = "fr-rFR-w400dp-h850dp-port")
    fun pageKeysAndModifierNamesUseLocalizedResources() {
        compose.setContent {
            DroidDeckTheme {
                Column {
                    Text(gestureActionTitle("page_up_down"))
                    Text(gestureActionTitle("key_SHIFT_L"))
                }
            }
        }
        compose.onNodeWithText("Page précédente / Page suivante").assertIsDisplayed()
        compose.onNodeWithText("Maj gauche").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "de-rDE-w400dp-h850dp-port")
    fun longTranslationsKeepDefaultRestorationAndSaveUsable() {
        GesturePrefs.save(context, "123", TouchGestureConfig(tapAction = "key_E"))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                DroidDeckTheme { TouchGestureDialog("123", {}, {}) }
            }
        }
        val restore = compose.onNodeWithTag("gesture-inherit")
        restore.assertIsDisplayed()
        val scope = compose.onNodeWithTag("gesture-scope-game").fetchSemanticsNode().boundsInRoot
        assertTrue("The restore action must have room for its translated label", restore.fetchSemanticsNode().boundsInRoot.width > scope.width)
        restore.performClick()
        compose.onNodeWithTag("gesture-save").assertIsDisplayed().performClick()
        compose.runOnIdle { assertFalse(GesturePrefs.hasOverride(context, "123")) }
    }

    @Test
    @Config(qualifiers = "ar-rEG-w400dp-h850dp-port")
    fun arabicPickerSearchPreservesBindingsAndMirrorsFooter() {
        assertEquals(View.LAYOUT_DIRECTION_RTL, context.resources.configuration.layoutDirection)
        compose.setContent { DroidDeckTheme { TouchGestureDialog(null, {}, {}) } }
        val save = compose.onNodeWithTag("gesture-save").fetchSemanticsNode().boundsInRoot
        val cancel = compose.onNodeWithText(context.getString(R.string.gesture_cancel)).fetchSemanticsNode().boundsInRoot
        assertTrue("Save $save and Cancel $cancel should follow the right-to-left layout", save.left < cancel.left)
        compose.onNodeWithTag("gesture-choice-tap").performScrollTo().performClick()
        compose.onNodeWithTag("gesture-action-search").performTextInput("هروب")
        compose.onNodeWithTag("gesture-action-key_ESC").assertIsDisplayed().performClick()
        compose.onNodeWithTag("gesture-save").performClick()
        compose.runOnIdle { assertEquals("key_ESC", GesturePrefs.read(context).tapAction) }
    }

    @Test
    @Config(qualifiers = "ja-rJP-w400dp-h850dp-port")
    fun recognizedGestureFeedbackUsesTheActiveLanguage() {
        compose.setContent { DroidDeckTheme { GestureFeedback(Gesture.TWO_FINGER_HOLD) } }
        compose.onNodeWithText("2本指で長押し").assertIsDisplayed()
    }
}
