package com.droiddeck.launcher.ui

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import com.droiddeck.launcher.input.GesturePrefs
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w960dp-h640dp-land")
class TouchGestureDialogTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clearPrefs() {
        context.getSharedPreferences("touch_gestures", 0).edit().clear().commit()
    }

    @Test fun searchSelectAndSaveAKeyboardAction() {
        compose.setContent { DroidDeckTheme { TouchGestureDialog(null, {}, {}) } }
        compose.onNodeWithTag("gesture-choice-tap").performScrollTo().performClick()
        compose.onNodeWithTag("gesture-action-left_click").assertIsSelected()
        compose.onNodeWithTag("gesture-action-search").performTextInput("numpad enter")
        compose.onNodeWithTag("gesture-action-key_KP_ENTER").assertIsDisplayed().performClick()
        compose.onNodeWithTag("gesture-save").performClick()
        compose.runOnIdle { assertEquals("key_KP_ENTER", GesturePrefs.read(context).tapAction) }
    }

    @Test fun switchingTabsKeepsEditsAndLetsTwoFingerPanBeChanged() {
        compose.setContent { DroidDeckTheme { TouchGestureDialog(null, {}, {}) } }
        compose.onNodeWithTag("gesture-tab-1").performClick()
        compose.onNodeWithTag("gesture-choice-two-drag").performScrollTo().performClick()
        compose.onNodeWithTag("gesture-action-search").performTextInput("middle mouse pan")
        compose.onNodeWithTag("gesture-action-middle_mouse_pan").performClick()
        compose.onNodeWithTag("gesture-tab-3").performClick()
        compose.onNodeWithTag("gesture-tab-1").performClick()
        compose.onNodeWithText("Middle mouse pan").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("gesture-save").performClick()
        compose.runOnIdle { assertEquals("middle_mouse_pan", GesturePrefs.read(context).twoFingerDragAction) }
    }

    @Test fun closingWithoutSavingDiscardsTheDraft() {
        val open = mutableStateOf(true)
        compose.setContent { DroidDeckTheme { if (open.value) TouchGestureDialog(null, { open.value = false }, {}) } }
        chooseTap("Escape", "key_ESC")
        compose.onNodeWithContentDescription("Close").performClick()
        compose.runOnIdle { assertEquals("left_click", GesturePrefs.read(context).tapAction) }
    }

    @Test fun defaultsAndGameDraftsSurviveScopeChangesAndSaveTogether() {
        compose.setContent { DroidDeckTheme { TouchGestureDialog("123", {}, {}) } }
        chooseTap("numpad enter", "key_KP_ENTER")
        compose.onNodeWithTag("gesture-scope-default").performClick()
        chooseTap("Escape", "key_ESC")
        compose.onNodeWithTag("gesture-scope-game").performClick()
        compose.onNodeWithTag("gesture-choice-tap").performScrollTo()
        compose.onNodeWithText("Numpad Enter").assertIsDisplayed()
        compose.onNodeWithTag("gesture-save").performClick()
        compose.runOnIdle {
            assertEquals("key_ESC", GesturePrefs.read(context).tapAction)
            assertEquals("key_KP_ENTER", GesturePrefs.read(context, "123").tapAction)
        }
    }

    @Test fun aFocusChangeCannotRetargetAnOpenGameEditor() {
        val game = mutableStateOf("123")
        compose.setContent { DroidDeckTheme { TouchGestureDialog(game.value, {}, {}) } }
        chooseTap("Escape", "key_ESC")
        compose.runOnIdle {
            game.value = "456"
            Snapshot.sendApplyNotifications()
        }
        compose.onNodeWithTag("gesture-save").performClick()
        compose.runOnIdle {
            assertEquals("key_ESC", GesturePrefs.read(context, "123").tapAction)
            assertFalse(GesturePrefs.hasOverride(context, "456"))
        }
    }

    @Test
    @Config(qualifiers = "w400dp-h850dp-port")
    fun aNarrowWindowWithLargeTextKeepsNavigationAndSaveReachable() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                DroidDeckTheme { TouchGestureDialog("123", {}, {}) }
            }
        }
        compose.onNodeWithContentDescription("Close").assertIsDisplayed()
        compose.onNodeWithTag("gesture-save").assertIsDisplayed()
        compose.onNodeWithTag("gesture-tab-3").performScrollTo().performClick()
        compose.onNodeWithText("Mouse movement").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("gesture-save").assertIsDisplayed()
    }

    private fun chooseTap(search: String, id: String) {
        compose.onNodeWithTag("gesture-choice-tap").performScrollTo().performClick()
        compose.onNodeWithTag("gesture-action-search").performTextInput(search)
        compose.onNodeWithTag("gesture-action-$id").performClick()
    }
}
