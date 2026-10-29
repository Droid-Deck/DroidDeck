package com.droiddeck.launcher.ui

import android.app.Dialog
import android.content.Context
import android.graphics.PixelFormat
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.NativeKeyEvent
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land")
class ActiveGlideWindowsTest {

    @get:Rule
    val compose = createRobolectricComposeRule()

    private class TestOverlayView(context: Context) : FrameLayout(context) {
        val receivedEvents = mutableListOf<KeyEvent>()
        var consumeEvents = false

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            receivedEvents.add(event)
            return if (consumeEvents) true else super.dispatchKeyEvent(event)
        }
    }

    private class ControllableView(
        context: Context,
        var attached: Boolean = true,
        var visible: Boolean = true,
        var customRoot: View? = null,
    ) : View(context) {
        val dispatchedKeys = mutableListOf<KeyEvent>()
        var consumeKeys = false

        override fun isAttachedToWindow(): Boolean = attached
        override fun isShown(): Boolean = visible
        override fun getRootView(): View = customRoot ?: this

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            dispatchedKeys.add(event)
            return if (consumeKeys) true else super.dispatchKeyEvent(event)
        }
    }

    @Before
    @After
    fun resetRegistry() {
        // Clear internal ActiveGlideWindows entries between tests for isolation
        val entriesField = ActiveGlideWindows::class.java.getDeclaredField("entries")
        entriesField.isAccessible = true
        (entriesField.get(ActiveGlideWindows) as? MutableList<*>)?.clear()
        HeldKeys.confirm = false
        HeldKeys.focusWakeup = 0
    }

    // =========================================================================
    // 1. ActiveGlideWindows.register and unregister lifecycle
    // =========================================================================

    @Test
    fun registerAndUnregisterLifecycle() {
        val context = RuntimeEnvironment.getApplication()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val activityDecor = activity.window.decorView

        val overlay = ControllableView(context, attached = true, visible = true)
        var dismissed = false

        assertFalse(ActiveGlideWindows.hasActiveOverlay(activityDecor))

        ActiveGlideWindows.register(overlay) { dismissed = true }
        assertTrue(ActiveGlideWindows.hasActiveOverlay(activityDecor))

        ActiveGlideWindows.unregister(overlay)
        assertFalse(ActiveGlideWindows.hasActiveOverlay(activityDecor))
    }

    @Test
    fun duplicateRegisterUpdatesCallbackWithoutDuplicateEntries() {
        val context = RuntimeEnvironment.getApplication()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val activityDecor = activity.window.decorView

        val overlay = ControllableView(context, attached = true, visible = true)
        var callback1Called = false
        var callback2Called = false

        ActiveGlideWindows.register(overlay) { callback1Called = true }
        ActiveGlideWindows.register(overlay) { callback2Called = true }

        // Dispatch unhandled BUTTON_B to trigger the dismiss callback
        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B)
        val handled = ActiveGlideWindows.dispatchKeyEvent(activityDecor, event)

        assertTrue(handled)
        assertFalse(callback1Called)
        assertTrue(callback2Called)

        // Single unregister should completely remove it
        ActiveGlideWindows.unregister(overlay)
        assertFalse(ActiveGlideWindows.hasActiveOverlay(activityDecor))
    }

    @Test
    fun unregisterNonRegisteredViewIsSafeNoOp() {
        val context = RuntimeEnvironment.getApplication()
        val view = View(context)
        ActiveGlideWindows.unregister(view)
        assertFalse(ActiveGlideWindows.hasActiveOverlay(null))
    }

    @Test
    fun multipleOverlaysLIFOPriorityAndTeardownOrder() {
        val context = RuntimeEnvironment.getApplication()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val activityDecor = activity.window.decorView

        val overlay1 = ControllableView(context, attached = true, visible = true)
        val overlay2 = ControllableView(context, attached = true, visible = true)

        var dismiss1 = 0
        var dismiss2 = 0

        ActiveGlideWindows.register(overlay1) { dismiss1++ }
        ActiveGlideWindows.register(overlay2) { dismiss2++ }

        // Topmost should be overlay2 (the latest registered)
        val backEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B)
        assertTrue(ActiveGlideWindows.dispatchKeyEvent(activityDecor, backEvent))
        assertEquals(0, dismiss1)
        assertEquals(1, dismiss2)
        assertEquals(1, overlay2.dispatchedKeys.size)
        assertEquals(0, overlay1.dispatchedKeys.size)

        // When overlay2 is unregistered, overlay1 becomes topmost
        ActiveGlideWindows.unregister(overlay2)
        assertTrue(ActiveGlideWindows.hasActiveOverlay(activityDecor))

        assertTrue(ActiveGlideWindows.dispatchKeyEvent(activityDecor, backEvent))
        assertEquals(1, dismiss1)
        assertEquals(1, dismiss2)
        assertEquals(1, overlay1.dispatchedKeys.size)

        // When overlay1 is unregistered, no overlays remain
        ActiveGlideWindows.unregister(overlay1)
        assertFalse(ActiveGlideWindows.hasActiveOverlay(activityDecor))
    }

    // =========================================================================
    // 2. Topmost entry selection with separate decor views (root !== activityRoot)
    // =========================================================================

    @Test
    fun entryWithSameRootAsActivityDecorViewIsIgnored() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val activityDecor = activity.window.decorView

        // View that belongs to the activity's own window hierarchy
        val activityChildView = ControllableView(
            activity,
            attached = true,
            visible = true,
            customRoot = activityDecor.rootView,
        )

        ActiveGlideWindows.register(activityChildView) { }

        // Must be ignored because root === activityRoot
        assertFalse(ActiveGlideWindows.hasActiveOverlay(activityDecor))
        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)
        assertFalse(ActiveGlideWindows.dispatchKeyEvent(activityDecor, event))
        assertEquals(0, activityChildView.dispatchedKeys.size)
    }

    @Test
    fun entryDetachedOrHiddenIsIgnored() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val activityDecor = activity.window.decorView

        val detachedOverlay = ControllableView(activity, attached = false, visible = true)
        ActiveGlideWindows.register(detachedOverlay) { }
        assertFalse(ActiveGlideWindows.hasActiveOverlay(activityDecor))

        ActiveGlideWindows.unregister(detachedOverlay)

        val hiddenOverlay = ControllableView(activity, attached = true, visible = false)
        ActiveGlideWindows.register(hiddenOverlay) { }
        assertFalse(ActiveGlideWindows.hasActiveOverlay(activityDecor))
    }

    @Test
    fun nullActivityDecorViewTreatsAnyAttachedShownEntryAsOverlay() {
        val context = RuntimeEnvironment.getApplication()
        val overlay = ControllableView(context, attached = true, visible = true)

        ActiveGlideWindows.register(overlay) { }
        assertTrue(ActiveGlideWindows.hasActiveOverlay(null))

        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(ActiveGlideWindows.dispatchKeyEvent(null, event))
        assertEquals(1, overlay.dispatchedKeys.size)
    }

    @Test
    fun realDialogWindowDecorViewDiffersFromActivityRoot() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val activityDecor = activity.window.decorView

        val dialog = Dialog(activity)
        val dialogView = TestOverlayView(dialog.context)
        dialog.setContentView(dialogView)
        dialog.show()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        try {
            var dismissed = false
            ActiveGlideWindows.register(dialogView) { dismissed = true }

            assertTrue(dialogView.isAttachedToWindow)
            assertTrue(dialogView.isShown)
            assertTrue(ActiveGlideWindows.hasActiveOverlay(activityDecor))

            val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B)
            assertTrue(ActiveGlideWindows.dispatchKeyEvent(activityDecor, event))
            assertTrue(dismissed)
        } finally {
            dialog.dismiss()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
    }

    // =========================================================================
    // 3. Key dispatching & interception (D-pad, BUTTON_A, BUTTON_B, Volume)
    // =========================================================================

    @Test
    fun volumeKeysBypassOverlayDirectly() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val activityDecor = activity.window.decorView

        val overlay = ControllableView(activity, attached = true, visible = true)
        ActiveGlideWindows.register(overlay) { }

        val volumeKeys = listOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
        )

        for (keyCode in volumeKeys) {
            val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            assertFalse(ActiveGlideWindows.dispatchKeyEvent(activityDecor, down))
            assertFalse(ActiveGlideWindows.dispatchKeyEvent(activityDecor, up))
        }

        // Overlay must receive no volume events
        assertEquals(0, overlay.dispatchedKeys.size)
    }

    @Test
    fun forwardDpadAndActionKeysToOverlayRoot() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val activityDecor = activity.window.decorView

        val overlay = ControllableView(activity, attached = true, visible = true)
        overlay.consumeKeys = true
        ActiveGlideWindows.register(overlay) { }

        val testKeys = listOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_ENTER,
        )

        for (keyCode in testKeys) {
            val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            assertTrue(ActiveGlideWindows.dispatchKeyEvent(activityDecor, down))
            assertTrue(ActiveGlideWindows.dispatchKeyEvent(activityDecor, up))
        }

        assertEquals(testKeys.size * 2, overlay.dispatchedKeys.size)
        for (i in testKeys.indices) {
            assertEquals(testKeys[i], overlay.dispatchedKeys[i * 2].keyCode)
            assertEquals(KeyEvent.ACTION_DOWN, overlay.dispatchedKeys[i * 2].action)
            assertEquals(testKeys[i], overlay.dispatchedKeys[i * 2 + 1].keyCode)
            assertEquals(KeyEvent.ACTION_UP, overlay.dispatchedKeys[i * 2 + 1].action)
        }
    }

    // =========================================================================
    // 4. Unhandled KEYCODE_BUTTON_B / KEYCODE_BACK invoking onDismissRequest
    // =========================================================================

    @Test
    fun unhandledButtonBDownInvokesDismiss() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val activityDecor = activity.window.decorView

        val overlay = ControllableView(activity, attached = true, visible = true)
        overlay.consumeKeys = false // unhandled
        var dismissCount = 0
        ActiveGlideWindows.register(overlay) { dismissCount++ }

        val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B)
        assertTrue(ActiveGlideWindows.dispatchKeyEvent(activityDecor, downEvent))
        assertEquals(1, dismissCount)
    }

    @Test
    fun unhandledBackDownInvokesDismiss() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val activityDecor = activity.window.decorView

        val overlay = ControllableView(activity, attached = true, visible = true)
        overlay.consumeKeys = false // unhandled
        var dismissCount = 0
        ActiveGlideWindows.register(overlay) { dismissCount++ }

        val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
        assertTrue(ActiveGlideWindows.dispatchKeyEvent(activityDecor, downEvent))
        assertEquals(1, dismissCount)
    }

    @Test
    fun handledButtonBOrBackDoesNotInvokeDismiss() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val activityDecor = activity.window.decorView

        val overlay = ControllableView(activity, attached = true, visible = true)
        overlay.consumeKeys = true // handled by dialog internally
        var dismissCount = 0
        ActiveGlideWindows.register(overlay) { dismissCount++ }

        val bEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B)
        assertTrue(ActiveGlideWindows.dispatchKeyEvent(activityDecor, bEvent))
        assertEquals(0, dismissCount)

        val backEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
        assertTrue(ActiveGlideWindows.dispatchKeyEvent(activityDecor, backEvent))
        assertEquals(0, dismissCount)
    }

    @Test
    fun buttonBActionUpDoesNotInvokeDismiss() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val activityDecor = activity.window.decorView

        val overlay = ControllableView(activity, attached = true, visible = true)
        overlay.consumeKeys = false
        var dismissCount = 0
        ActiveGlideWindows.register(overlay) { dismissCount++ }

        val upEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B)
        assertTrue(ActiveGlideWindows.dispatchKeyEvent(activityDecor, upEvent))
        assertEquals(0, dismissCount)
    }

    @Test
    fun buttonBRepeatDoesNotInvokeDismiss() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val activityDecor = activity.window.decorView

        val overlay = ControllableView(activity, attached = true, visible = true)
        overlay.consumeKeys = false
        var dismissCount = 0
        ActiveGlideWindows.register(overlay) { dismissCount++ }

        // repeatCount = 1 (held down repeat)
        val repeatEvent = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B, 1)
        assertTrue(ActiveGlideWindows.dispatchKeyEvent(activityDecor, repeatEvent))
        assertEquals(0, dismissCount)
    }

    @Test
    fun unhandledNonDismissKeysDoNotInvokeDismiss() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val activityDecor = activity.window.decorView

        val overlay = ControllableView(activity, attached = true, visible = true)
        overlay.consumeKeys = false
        var dismissCount = 0
        ActiveGlideWindows.register(overlay) { dismissCount++ }

        val aEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)
        assertTrue(ActiveGlideWindows.dispatchKeyEvent(activityDecor, aEvent))
        assertEquals(0, dismissCount)

        val dpadEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(ActiveGlideWindows.dispatchKeyEvent(activityDecor, dpadEvent))
        assertEquals(0, dismissCount)
    }

    @Test
    fun nullDismissCallbackDoesNotThrow() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val activityDecor = activity.window.decorView

        val overlay = ControllableView(activity, attached = true, visible = true)
        overlay.consumeKeys = false
        ActiveGlideWindows.register(overlay, null)

        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B)
        assertTrue(ActiveGlideWindows.dispatchKeyEvent(activityDecor, event))
    }

    // =========================================================================
    // 5. TrackActiveGlideWindow, HeldKeys.focusWakeup, and InputMode.Keyboard
    // =========================================================================

    @Test
    fun trackActiveGlideWindowRegistrationLifecycle() {
        var show by mutableStateOf(true)
        var dismissed = false

        compose.setContent {
            if (show) {
                TrackActiveGlideWindow(onDismissRequest = { dismissed = true })
            }
        }

        compose.waitForIdle()
        // Overlay view was registered during composition
        assertTrue(ActiveGlideWindows.hasActiveOverlay(null))

        // Trigger dismissal
        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B)
        assertTrue(ActiveGlideWindows.dispatchKeyEvent(null, event))
        assertTrue(dismissed)

        // When removed from composition, DisposableEffect unregisters it
        show = false
        compose.waitForIdle()
        assertFalse(ActiveGlideWindows.hasActiveOverlay(null))
    }

    @Test
    fun focusWakeupTriggersKeyboardInputMode() {
        val testInputModeManager = object : InputModeManager {
            override var inputMode: InputMode = InputMode.Touch
            val requestedModes = mutableListOf<InputMode>()

            override fun requestInputMode(inputMode: InputMode): Boolean {
                requestedModes.add(inputMode)
                this.inputMode = inputMode
                return true
            }
        }

        compose.setContent {
            CompositionLocalProvider(LocalInputModeManager provides testInputModeManager) {
                TrackActiveGlideWindow(onDismissRequest = {})
            }
        }

        compose.waitForIdle()
        assertEquals(InputMode.Touch, testInputModeManager.inputMode)
        assertEquals(0, testInputModeManager.requestedModes.size)

        // Increment focusWakeup
        compose.runOnIdle {
            HeldKeys.focusWakeup++
        }
        compose.waitForIdle()

        // LaunchedEffect detects focusWakeup > 0 and inputMode != Keyboard, triggering requestInputMode(Keyboard)
        assertEquals(listOf(InputMode.Keyboard), testInputModeManager.requestedModes)
        assertEquals(InputMode.Keyboard, testInputModeManager.inputMode)
    }

    @Test
    fun composeGlideDialogRegistersAndInterceptsButtonB() {
        var dismissed = false
        var showDialog by mutableStateOf(true)

        compose.setContent {
            if (showDialog) {
                GlideDialog(onDismissRequest = {
                    dismissed = true
                    showDialog = false
                }) {
                    Text("Dialog Content")
                }
            }
        }

        compose.waitForIdle()
        assertTrue(ActiveGlideWindows.hasActiveOverlay(null))

        val backEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B)
        assertTrue(ActiveGlideWindows.dispatchKeyEvent(null, backEvent))
        compose.waitForIdle()

        assertTrue(dismissed)
        assertFalse(ActiveGlideWindows.hasActiveOverlay(null))
    }

    // =========================================================================
    // 6. Modifier.controllerConfirm handling KEYCODE_BUTTON_A
    // =========================================================================

    @Test
    fun controllerConfirmHandlesButtonADownAndInvokesOnClick() {
        val focusRequester = FocusRequester()
        var clicked = false

        compose.setContent {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .focusRequester(focusRequester)
                    .focusable()
                    .controllerConfirm(enabled = true) { clicked = true }
            )
        }

        compose.runOnIdle { focusRequester.requestFocus() }
        compose.waitForIdle()

        // ACTION_DOWN KEYCODE_BUTTON_A (repeatCount = 0)
        val buttonADown = androidx.compose.ui.input.key.KeyEvent(
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)
        )
        val consumedDown = compose.onRoot().performKeyPress(buttonADown)
        compose.waitForIdle()

        assertTrue(consumedDown)
        assertTrue(clicked)

        // ACTION_UP KEYCODE_BUTTON_A
        clicked = false
        val buttonAUp = androidx.compose.ui.input.key.KeyEvent(
            KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A)
        )
        val consumedUp = compose.onRoot().performKeyPress(buttonAUp)
        compose.waitForIdle()

        assertTrue(consumedUp)
        assertFalse(clicked) // ACTION_UP does not re-invoke onClick

        // KEYCODE_BUTTON_A repeatCount = 1
        clicked = false
        val buttonARepeat = androidx.compose.ui.input.key.KeyEvent(
            KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, 1)
        )
        val consumedRepeat = compose.onRoot().performKeyPress(buttonARepeat)
        compose.waitForIdle()

        assertTrue(consumedRepeat)
        assertFalse(clicked) // repeat does not re-invoke onClick
    }

    @Test
    fun controllerConfirmDisabledConsumesButtonAButDoesNotInvokeOnClick() {
        val focusRequester = FocusRequester()
        var clicked = false

        compose.setContent {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .focusRequester(focusRequester)
                    .focusable()
                    .controllerConfirm(enabled = false) { clicked = true }
            )
        }

        compose.runOnIdle { focusRequester.requestFocus() }
        compose.waitForIdle()

        val buttonADown = androidx.compose.ui.input.key.KeyEvent(
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)
        )
        val consumed = compose.onRoot().performKeyPress(buttonADown)
        compose.waitForIdle()

        assertTrue(consumed)
        assertFalse(clicked)
    }

    @Test
    fun controllerConfirmIgnoresOtherKeyCodes() {
        val focusRequester = FocusRequester()
        var clicked = false

        compose.setContent {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .focusRequester(focusRequester)
                    .focusable()
                    .controllerConfirm(enabled = true) { clicked = true }
            )
        }

        compose.runOnIdle { focusRequester.requestFocus() }
        compose.waitForIdle()

        val otherKeys = listOf(
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
        )

        for (keyCode in otherKeys) {
            val keyEvent = androidx.compose.ui.input.key.KeyEvent(
                KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            )
            val consumed = compose.onRoot().performKeyPress(keyEvent)
            assertFalse("Expected keyCode $keyCode to return false (not consumed)", consumed)
            assertFalse(clicked)
        }
    }
}
