package com.droiddeck.launcher.ui

import android.app.Presentation
import android.hardware.display.DisplayManager
import android.os.Looper
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w640dp-h480dp-land", application = android.app.Application::class)
class PresentationWindowTest {
    @get:Rule val compose = createRobolectricComposeRule()

    @Test fun alertInsidePresentationRendersDismissesAndTracksItsWindow() {
        var shown by mutableStateOf(true)
        var displayId = Display.DEFAULT_DISPLAY
        compose.setContent {
            CompositionLocalProvider(LocalPresentationWindow provides true) {
                val view = LocalView.current
                SideEffect { displayId = view.display?.displayId ?: Display.DEFAULT_DISPLAY }
                if (shown) GlideAlertDialog(
                    onDismissRequest = { shown = false },
                    confirmButton = { TextButton(onClick = { shown = false }) { Text("Confirm") } },
                    title = { Text("Title") },
                    text = { Text("Body") },
                )
            }
        }
        compose.onNodeWithText("Title").assertIsDisplayed()
            .assert(hasAnyAncestor(isPopup()))
        compose.onNodeWithText("Body").assertIsDisplayed()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertNotNull(KeyWindows.topOn(displayId)) }
        compose.onNodeWithText("Confirm").performClick()
        compose.onNodeWithText("Title").assertDoesNotExist()
    }

    @Test fun dialogInsidePresentationRendersItsContent() {
        var shown by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalPresentationWindow provides true) {
                if (shown) GlideDialog(onDismissRequest = { shown = false }) {
                    TextButton(onClick = { shown = false }) { Text("Close") }
                }
            }
        }
        compose.onNodeWithText("Close").assertIsDisplayed()
            .assert(hasAnyAncestor(isPopup()))
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Close").assertDoesNotExist()
    }

    @Test fun dialogInsidePresentationDismissesOnOutsideTapByDefault() {
        var shown by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalPresentationWindow provides true) {
                if (shown) GlideDialog(onDismissRequest = { shown = false }) { Text("Panel") }
            }
        }
        compose.onAllNodes(isRoot())[1].performTouchInput { click(Offset(1f, 1f)) }
        compose.onNodeWithText("Panel").assertDoesNotExist()
    }

    @Test fun dialogInsidePresentationHonorsDisabledOutsideDismissal() {
        var dismissals = 0
        compose.setContent {
            CompositionLocalProvider(LocalPresentationWindow provides true) {
                GlideDialog(
                    onDismissRequest = { dismissals++ },
                    properties = DialogProperties(dismissOnClickOutside = false),
                ) { Text("Panel") }
            }
        }
        compose.onAllNodes(isRoot())[1].performTouchInput { click(Offset(1f, 1f)) }
        compose.onNodeWithText("Panel").assertIsDisplayed()
        assertEquals(0, dismissals)
    }

    @Test
    @Config(sdk = [33])
    fun dialogUsesThePresentationWindowOnAndroid13() {
        val activityController = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = activityController.get()
        val displays = activity.getSystemService(DisplayManager::class.java)
        val displayId = ShadowDisplayManager.addDisplay("w640dp-h480dp-land")
        val display = displays.getDisplay(displayId)
        shadowOf(display).setFlags(Display.FLAG_PRESENTATION)
        var dialogComposed = false
        val presentation = Presentation(activity, display)
        val view = ComposeView(presentation.context).apply {
            setViewTreeLifecycleOwner(activity)
            setViewTreeViewModelStoreOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setViewTreeOnBackPressedDispatcherOwner(activity)
            setContent {
                CompositionLocalProvider(LocalPresentationWindow provides true) {
                    DroidDeckTheme {
                        GlideDialog(onDismissRequest = {}) {
                            SideEffect { dialogComposed = true }
                            Text("Panel")
                        }
                    }
                }
            }
        }
        presentation.setContentView(view)
        try {
            presentation.show()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(dialogComposed)
            compose.onNodeWithText("Panel").assertIsDisplayed()
                .assert(hasAnyAncestor(isPopup()))
            compose.onNode(isDialog()).assertDoesNotExist()
        } finally {
            presentation.dismiss()
            activityController.pause().stop().destroy()
        }
    }

    @Test fun dialogOutsidePresentationKeepsThePlatformDialog() {
        compose.setContent { GlideDialog(onDismissRequest = {}) { Text("Panel") } }
        compose.onNodeWithText("Panel").assertIsDisplayed()
            .assert(hasAnyAncestor(isDialog()))
        compose.onNode(isPopup()).assertDoesNotExist()
    }

    @Test fun alertOutsidePresentationKeepsThePlatformDialog() {
        compose.setContent {
            GlideAlertDialog(
                onDismissRequest = {},
                confirmButton = { TextButton(onClick = {}) { Text("Confirm") } },
                title = { Text("Title") },
                text = { Text("Body") },
            )
        }
        compose.onNodeWithText("Title").assertIsDisplayed()
            .assert(hasAnyAncestor(isDialog()))
        compose.onNodeWithText("Body").assertIsDisplayed()
        compose.onNode(isPopup()).assertDoesNotExist()
    }
}
