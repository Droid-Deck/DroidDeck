package com.droiddeck.launcher.ui

import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties.StateDescription
import androidx.compose.runtime.MutableState
import androidx.compose.ui.geometry.Offset
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
import com.droiddeck.launcher.input.InputLibraries
import com.droiddeck.launcher.input.InputRole
import com.droiddeck.launcher.input.InputElementKind
import com.droiddeck.launcher.input.InputSurfaceElement
import com.droiddeck.launcher.input.InputSurfaceStyle
import com.droiddeck.launcher.input.InputControl
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun desktopMenuOffersProfileControlsAndTerminal() = checkModes(steam = false)

    @Test fun steamMenuOffersProfileControlsAndTerminal() = checkModes(steam = true)

    @Test fun desktopQuickMenuChangesTheRunningDesktopLayout() = checkQuickSelection(steam = false, desktop = true, direct = true)

    @Test fun steamQuickMenuLeavesTheDesktopSelectionAlone() = checkQuickSelection(steam = true, desktop = false, direct = false)

    @Test fun directGameQuickMenuLeavesTheDesktopSelectionAlone() = checkQuickSelection(steam = false, desktop = false, direct = true)

    @Test fun closingTheDockedDrawerKeepsTheSelectedControlsPanel() {
        SessionState.mode = SessionService.MODE_STEAM
        var closes = 0
        var selected = SecondScreenMode.CUSTOM_CONTROLS
        compose.setContent {
            DroidDeckTheme {
                SessionDrawer(true, SessionDrawerPage.SECOND_SCREEN, false, {},
                    actions(true, listOf(SecondScreenDisplay(1, "Bottom screen")),
                        onClose = { closes++ }, select = { selected = it }), docked = true)
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.common_close)).performClick()
        assertEquals(1, closes)
        assertEquals(SecondScreenMode.CUSTOM_CONTROLS, selected)
    }

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

    @Test fun provisionalBottomScreenPanelSettlesOnlyOnceTheSessionIsInFront() {
        SessionState.mode = SessionService.MODE_STEAM
        val previousMode = SessionState.secondScreenMode
        val previousProvisional = SessionState.secondScreenProvisional
        SessionState.secondScreenMode = SecondScreenMode.NONE
        try {
            val activity = Robolectric.buildActivity(SessionActivity::class.java).get()
            val pip: MutableState<Boolean> = ReflectionHelpers.getField(activity, "pipUi\$delegate")
            fun settle() = ReflectionHelpers.callInstanceMethod<Unit>(activity, "settleDualScreenControls")
            SessionState.secondScreenProvisional = true
            ReflectionHelpers.setField(activity, "started", true)
            pip.value = true
            settle()
            assertTrue(SessionState.secondScreenProvisional)
            pip.value = false
            ReflectionHelpers.setField(activity, "started", false)
            settle()
            assertTrue(SessionState.secondScreenProvisional)
            ReflectionHelpers.setField(activity, "started", true)
            settle()
            assertFalse(SessionState.secondScreenProvisional)
        } finally {
            SessionState.secondScreenMode = previousMode
            SessionState.secondScreenProvisional = previousProvisional
        }
    }

    @Test fun terminalOpenedBeforeItWasReadyIsRetriedButOtherPanelsAreLeftAlone() {
        SessionState.mode = SessionService.MODE_STEAM
        val activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        val display = activity.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val panel = SecondScreenPresentation(activity, display, {}, {})
        try {
            panel.show()
            panel.showMode(SecondScreenMode.KEYBOARD_TRACKPAD)
            val root = panel.window!!.decorView
            val trackpad = descendants(root).toList()
            panel.retryTerminal()
            assertEquals(trackpad, descendants(root).toList())
            panel.showMode(SecondScreenMode.TERMINAL)
            val notReady = context.getString(R.string.second_terminal_not_ready)
            val before = descendants(root).filter { (it as? TextView)?.text == notReady }.single()
            panel.retryTerminal()
            val after = descendants(root).filter { (it as? TextView)?.text == notReady }.single()
            assertTrue(before !== after)
        } finally {
            panel.closeControls()
        }
    }

    @Test fun profileRefreshLeavesTerminalAndDashboardIntact() {
        val activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        val display = activity.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val panel = SecondScreenPresentation(activity, display, {}, {}, dashboard = { androidx.compose.material3.Text("Dashboard") })
        try {
            panel.show()
            for (mode in listOf(SecondScreenMode.TERMINAL, SecondScreenMode.DASHBOARD)) {
                panel.showMode(mode)
                val root = panel.window!!.decorView
                val before = descendants(root).toList()
                val terminal: Any? = ReflectionHelpers.getField(panel, "terminalPanel")
                panel.refreshControls()
                assertEquals(before, descendants(root).toList())
                assertSame(terminal, ReflectionHelpers.getField<Any?>(panel, "terminalPanel"))
            }
        } finally { panel.closeControls() }
    }

    @Test fun profileRefreshUpdatesControlsWithoutClosingTheFieldKeyboard() {
        SessionState.mode = SessionService.MODE_STEAM
        val activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        val display = activity.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        var closed = 0
        val panel = SecondScreenPresentation(activity, display, {}, {}, onKeyboardClosed = { closed++ })
        try {
            panel.show()
            panel.showMode(SecondScreenMode.CUSTOM_CONTROLS)
            panel.requestKeyboard(forField = true)
            val host: Any = ReflectionHelpers.getField(panel, "keyboardHost")
            val shown: MutableState<Boolean> = ReflectionHelpers.getField(panel, "keyboardShown")
            shown.value = true
            ReflectionHelpers.setField(panel, "imeSeen", true)
            val controls = descendants(panel.window!!.decorView).filterIsInstance<InputSurfaceView>().single()
            val element = InputSurfaceElement(InputControl.A, .3f, .2f, .2f, .2f, "updated")
            InputLibraries.update(activity) { library ->
                val layout = library.activeLayout(false, InputRole.SECONDARY)!!
                library.withLayout(layout.copy(elements = listOf(element), style = InputSurfaceStyle(opacity = 35)))
            }
            panel.refreshControls()
            assertSame(controls, descendants(panel.window!!.decorView).filterIsInstance<InputSurfaceView>().single())
            val child = descendants(controls).single { it.tag == element }
            assertEquals(.35f, child.alpha, .001f)
            assertSame(host, ReflectionHelpers.getField<Any>(panel, "keyboardHost"))
            assertTrue(panel.fieldKeyboardOpen)
            assertTrue(shown.value)
            assertTrue(ReflectionHelpers.getField<Boolean>(panel, "imeSeen"))
            assertEquals(0, closed)
        } finally { panel.closeControls() }
    }

    @Test fun profileRefreshPreservesTheLowerScreenChatPanel() {
        val activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        val display = activity.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        var lost = 0
        val panel = SecondScreenPresentation(activity, display, {}, {})
        try {
            panel.show()
            panel.showMode(SecondScreenMode.CUSTOM_CONTROLS)
            panel.showChat(onLost = { lost++ }) { androidx.compose.material3.Text("Chat draft") }
            val chat: View? = ReflectionHelpers.getField(panel, "chatView")
            assertNotNull(chat)
            panel.refreshControls()
            assertSame(chat, ReflectionHelpers.getField<View?>(panel, "chatView"))
            assertTrue(descendants(panel.window!!.decorView).any { it === chat })
            assertEquals(0, lost)
        } finally { panel.closeControls() }
    }

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
            state<Boolean>("drawerOpenState").value = true
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
        val open: MutableState<Boolean> = ReflectionHelpers.getField(activity, "drawerOpenState\$delegate")
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

    private fun checkQuickSelection(steam: Boolean, desktop: Boolean, direct: Boolean) {
        SessionState.mode = if (desktop) SessionService.MODE_DESKTOP else if (steam) SessionService.MODE_STEAM else SessionService.MODE_RUN
        val original = InputLibraries.update(context) { it.chooseLayout("desktop", InputRole.SECONDARY, InputLibraries.DECK) }
        val activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        val display = activity.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val panel = SecondScreenPresentation(activity, display, {}, {})
        val open = androidx.compose.runtime.mutableStateOf(true)
        try {
            panel.show()
            panel.showMode(SecondScreenMode.CUSTOM_CONTROLS)
            compose.setContent {
                DroidDeckTheme {
                    SessionDrawer(open.value, SessionDrawerPage.CONTROLLER, false, {},
                        actions(steam, emptyList(), desktop = desktop, direct = direct,
                            onInputSelection = { panel.refreshControls() }, onClose = { open.value = false }))
                }
            }
            compose.onNodeWithTag("setting-input-profile").performScrollTo().performClick()
            compose.onNodeWithText("Desktop").performScrollTo().performClick()
            compose.onNodeWithTag("input-profile-expand").performScrollTo().performClick()
            compose.onNodeWithTag("setting-input-part-SECONDARY").performScrollTo().performClick()
            compose.onNodeWithText("Trackpad + keyboard").performScrollTo().performClick()
            compose.onNode(hasContentDescription(context.getString(R.string.common_close)) and
                SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button)).performClick()
            compose.runOnIdle {
                val updated = InputLibraries.read(context)
                assertEquals("desktop", updated.selection(desktop).profile)
                assertEquals(InputLibraries.TRACKPAD, updated.activeLayout(desktop, InputRole.SECONDARY)?.id)
                assertEquals(original.selection(!desktop), updated.selection(!desktop))
                assertEquals(original.profiles, updated.profiles)
                val controls = descendants(panel.window!!.decorView).filterIsInstance<InputSurfaceView>().single()
                val pads = descendants(controls).mapNotNull { it.tag as? InputSurfaceElement }.filter { it.kind == InputElementKind.TRACKPAD }.toList()
                assertEquals(1, pads.size)
            }
        } finally { panel.closeControls() }
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
        val modes = listOf(SecondScreenMode.TERMINAL, SecondScreenMode.CUSTOM_CONTROLS)
        for (mode in modes) {
            compose.onNodeWithText(context.getString(mode.label)).performScrollTo().assertIsDisplayed().performClick()
        }
        compose.onNodeWithText(context.getString(SecondScreenMode.DECK_CONTROLS.label)).assertDoesNotExist()
        assertEquals(modes, selected)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun actions(steam: Boolean, displays: List<SecondScreenDisplay>,
        micAvailable: Boolean = false, micEnabled: Boolean = false, onMic: (Boolean) -> Unit = {},
        desktop: Boolean = !steam, direct: Boolean = false, onInputSelection: (InputRole?) -> Unit = {},
        onClose: () -> Unit = {},
        select: (SecondScreenMode) -> Unit = {}) =
        DrawerActions(steam = steam, desktop = desktop, direct = direct, onInputSelection = onInputSelection,
            isHomeApp = false, androidApps = emptyList(), hudOn = false,
            micAvailable = micAvailable, micEnabled = micEnabled, onMicEnabled = onMic,
            frameGen = FrameGen.Mode.OFF, lossless = Lossless.State.NONE, oscMode = "never",
            suspendPolicy = "auto", backActionsInverted = false,
            touchMode = "auto", touchAuto = "trackpad", fexPreset = "default",
            secondScreenMode = SecondScreenMode.NONE, secondScreenDisplays = displays, selectedSecondScreenDisplay = 1,
            onHud = {}, onFrameGenPick = {}, onImportLossless = {}, onKeyboard = {}, onHardwareKeyboard = {},
            onSteamMenu = null, onQam = null, onOsc = {}, onSuspendPolicy = {}, onBackActionsInverted = {},
            onTouch = {}, onFexPreset = {}, onSecondScreenMode = select, onSecondScreenDisplay = {},
            onLaunchAndroidApp = { _, _ -> }, onBackground = {}, onShareLogs = {}, onStop = {}, onClose = onClose)
}
