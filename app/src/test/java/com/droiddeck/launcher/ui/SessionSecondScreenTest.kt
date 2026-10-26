package com.droiddeck.launcher.ui

import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties.StateDescription
import androidx.compose.runtime.MutableState
import com.droiddeck.launcher.R
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.gpu.FrameGen
import com.droiddeck.launcher.gpu.Lossless
import com.droiddeck.launcher.input.SecondScreenDisplay
import com.droiddeck.launcher.input.SecondScreenMode
import com.droiddeck.launcher.input.PadBridgeRoutingTest
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.InputDeviceBuilder
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w800dp-h600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionSecondScreenTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private var previousMode = ""
    private var previousDeckPad = false

    @Before fun setUp() {
        previousMode = SessionState.mode
        previousDeckPad = SessionState.deckPad
        SessionState.deckPad = false
    }

    @After fun tearDown() {
        SessionState.mode = previousMode
        SessionState.deckPad = previousDeckPad
    }

    @Test fun desktopMenuOffersTheExistingTrackpadAndTerminal() = checkModes(steam = false)

    @Test fun steamMenuKeepsTheExistingTrackpadAndTerminal() = checkModes(steam = true)

    @Test fun desktopWithoutASecondDisplayFallsBackToController() {
        SessionState.mode = SessionService.MODE_DESKTOP
        var page = SessionDrawerPage.SECOND_SCREEN
        compose.setContent {
            DroidDeckTheme {
                SessionDrawer(true, page, false, { page = it }, actions(false, emptyList()))
            }
        }
        compose.runOnIdle { assertEquals(SessionDrawerPage.CONTROLLER, page) }
        compose.onNodeWithText(context.getString(R.string.second_mode_keyboard_trackpad)).assertDoesNotExist()
    }

    @Test fun desktopTrackpadOmitsSteamShortcuts() = checkShortcuts(steam = false)

    @Test fun steamTrackpadKeepsWorkingSteamShortcuts() = checkShortcuts(steam = true)

    @Test
    @Config(sdk = [34], shadows = [PadBridgeRoutingTest.RegisteredKeyEvent::class])
    fun controllerBumpersReachTheSecondScreenInBothSessionModes() {
        val device = InputDeviceBuilder.newBuilder().setId(42).setName("Second screen test pad")
            .setDescriptor("second-screen-pad").setExternal(true).setSources(InputDevice.SOURCE_GAMEPAD).build()
        shadowOf(context.getSystemService(InputManager::class.java)).addInputDevice(device)
        for (mode in listOf(SessionService.MODE_DESKTOP, SessionService.MODE_STEAM)) {
            SessionState.mode = mode
            val activity = Robolectric.buildActivity(SessionActivity::class.java).get()
            fun <T> state(name: String): MutableState<T> = ReflectionHelpers.getField(activity, "$name\$delegate")
            state<Boolean>("drawerOpen").value = true
            state<Boolean>("drawerControllerActive").value = true
            state<List<SecondScreenDisplay>>("secondScreenDisplays").value = listOf(SecondScreenDisplay(1, "Bottom screen"))
            for ((button, page) in listOf(KeyEvent.KEYCODE_BUTTON_R1 to SessionDrawerPage.SECOND_SCREEN,
                KeyEvent.KEYCODE_BUTTON_L1 to SessionDrawerPage.CONTROLLER)) {
                assertTrue(activity.dispatchKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, button, 0, 0,
                    device.id, 0, 0, InputDevice.SOURCE_GAMEPAD)))
                assertEquals(page, state<SessionDrawerPage>("drawerPage").value)
            }
        }
    }

    @Test
    @Config(sdk = [34], shadows = [PadBridgeRoutingTest.RegisteredKeyEvent::class])
    fun drawerShortcutsConsumeRepeatsAndReleasesAfterClosing() {
        val device = InputDeviceBuilder.newBuilder().setId(43).setName("Drawer shortcut test pad")
            .setDescriptor("drawer-shortcut-pad").setExternal(true).setSources(InputDevice.SOURCE_GAMEPAD).build()
        shadowOf(context.getSystemService(InputManager::class.java)).addInputDevice(device)
        SessionState.mode = SessionService.MODE_STEAM
        val activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        val open: MutableState<Boolean> = ReflectionHelpers.getField(activity, "drawerOpen\$delegate")
        val controller: MutableState<Boolean> = ReflectionHelpers.getField(activity, "drawerControllerActive\$delegate")
        controller.value = true
        for (button in listOf(KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START)) {
            fun event(action: Int, repeat: Int = 0) = KeyEvent(0L, 0L, action, button, repeat, 0,
                device.id, 0, 0, InputDevice.SOURCE_GAMEPAD)
            open.value = true
            assertTrue(activity.dispatchKeyEvent(event(KeyEvent.ACTION_DOWN)))
            assertEquals(false, open.value)
            open.value = true
            assertTrue(activity.dispatchKeyEvent(event(KeyEvent.ACTION_DOWN, 1)))
            assertEquals(true, open.value)
            open.value = false
            assertTrue(activity.dispatchKeyEvent(event(KeyEvent.ACTION_UP)))
            val held: Set<Pair<Int, Int>> = ReflectionHelpers.getField(activity, "drawerShortcutKeysDown")
            assertTrue(held.isEmpty())
        }
    }

    @Test fun sessionMicrophoneCanBeTurnedOff() {
        val changes = mutableListOf<Boolean>()
        compose.setContent {
            DroidDeckTheme {
                SessionDrawer(true, SessionDrawerPage.SESSION, false, {},
                    actions(true, emptyList(), micAvailable = true, micEnabled = true, onMic = changes::add))
            }
        }
        compose.onNodeWithTag("setting-microphone").assertIsEnabled()
            .assert(SemanticsMatcher.expectValue(StateDescription, context.getString(R.string.widgets_on))).performClick()
        assertEquals(listOf(false), changes)
    }

    @Test fun sessionMicrophoneCanBeTurnedBackOn() {
        val changes = mutableListOf<Boolean>()
        compose.setContent {
            DroidDeckTheme {
                SessionDrawer(true, SessionDrawerPage.SESSION, false, {},
                    actions(true, emptyList(), micAvailable = true, onMic = changes::add))
            }
        }
        compose.onNodeWithTag("setting-microphone").assertIsEnabled()
            .assert(SemanticsMatcher.expectValue(StateDescription, context.getString(R.string.widgets_off))).performClick()
        assertEquals(listOf(true), changes)
    }

    @Test fun sessionWithoutMicrophoneOmitsCaptureControl() {
        compose.setContent {
            DroidDeckTheme {
                SessionDrawer(true, SessionDrawerPage.SESSION, false, {}, actions(false, emptyList()))
            }
        }
        compose.onNodeWithTag("setting-microphone").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.mode_mic)).assertDoesNotExist()
    }

    private fun checkModes(steam: Boolean) {
        SessionState.mode = if (steam) SessionService.MODE_STEAM else SessionService.MODE_DESKTOP
        val selected = mutableListOf<SecondScreenMode>()
        val displays = listOf(SecondScreenDisplay(1, "Bottom screen"))
        compose.setContent {
            DroidDeckTheme {
                SessionDrawer(true, SessionDrawerPage.SECOND_SCREEN, false, {}, actions(steam, displays) { selected += it })
            }
        }
        for (mode in listOf(SecondScreenMode.KEYBOARD_TRACKPAD, SecondScreenMode.TERMINAL)) {
            compose.onNodeWithText(context.getString(mode.label)).performScrollTo().assertIsDisplayed().performClick()
        }
        compose.onNodeWithText(context.getString(SecondScreenMode.DECK_CONTROLS.label)).assertDoesNotExist()
        assertEquals(listOf(SecondScreenMode.KEYBOARD_TRACKPAD, SecondScreenMode.TERMINAL), selected)
    }

    private fun checkShortcuts(steam: Boolean) {
        SessionState.mode = if (steam) SessionService.MODE_STEAM else SessionService.MODE_DESKTOP
        val activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        val display = activity.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        var menus = 0
        var qams = 0
        val panel = SecondScreenPresentation(activity, display, { _, _, _, _ -> }, { _, _ -> }, {},
            { menus++ }, { qams++ }, {})
        try {
            panel.create()
            panel.showMode(SecondScreenMode.KEYBOARD_TRACKPAD)
            val buttons = descendants(panel.window!!.decorView).filterIsInstance<Button>().toList()
            assertTrue(buttons.any { it.text == context.getString(R.string.second_show_keyboard) })
            val shortcuts = buttons.filter { it.text == "STEAM" || it.text == "…" }
            if (steam) {
                assertEquals(2, shortcuts.size)
                shortcuts.forEach { it.performClick() }
                assertEquals(1, menus)
                assertEquals(1, qams)
            } else {
                assertTrue(shortcuts.isEmpty())
            }
        } finally {
            panel.closeControls()
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun actions(steam: Boolean, displays: List<SecondScreenDisplay>,
        micAvailable: Boolean = false, micEnabled: Boolean = false, onMic: (Boolean) -> Unit = {},
        select: (SecondScreenMode) -> Unit = {}) =
        DrawerActions(steam = steam, isHomeApp = false, androidApps = emptyList(), hudOn = false,
            micAvailable = micAvailable, micEnabled = micEnabled, onMicEnabled = onMic,
            frameGen = FrameGen.Mode.OFF, lossless = Lossless.State.NONE, oscMode = "never",
            onScreenButtonsVisible = false, suspendPolicy = "auto", backActionsInverted = false,
            touchMode = "auto", touchAuto = "trackpad", fexPreset = "default",
            secondScreenMode = SecondScreenMode.NONE, secondScreenDisplays = displays, selectedSecondScreenDisplay = 1,
            onHud = {}, onFrameGenPick = {}, onImportLossless = {}, onKeyboard = {}, onHardwareKeyboard = {},
            onSteamMenu = null, onQam = null, onOsc = {}, onSuspendPolicy = {}, onBackActionsInverted = {},
            onTouch = {}, onFexPreset = {}, onSecondScreenMode = select, onSecondScreenDisplay = {},
            onLaunchAndroidApp = { _, _ -> }, onBackground = {}, onShareLogs = {}, onStop = {}, onClose = {})
}
