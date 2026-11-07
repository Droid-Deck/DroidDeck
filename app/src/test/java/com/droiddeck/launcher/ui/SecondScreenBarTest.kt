package com.droiddeck.launcher.ui

import android.hardware.display.DisplayManager
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.currentComposer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.droiddeck.launcher.R
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.input.DeckPanel
import com.droiddeck.launcher.input.InputLibraries
import com.droiddeck.launcher.input.InputSurfaceElement
import com.droiddeck.launcher.input.SecondScreenMode
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w800dp-h600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SecondScreenBarTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private var previousMode = ""
    private var previousDeckPad = false

    @Before fun setUp() {
        previousMode = SessionState.mode
        previousDeckPad = SessionState.deckPad
        context.getSharedPreferences("input-profiles", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        SessionPrefs.setDeckPanel(context, DeckPanel.BOTH)
    }

    @After fun tearDown() {
        SessionState.mode = previousMode
        SessionState.deckPad = previousDeckPad
    }

    private fun presentation(swap: (() -> Unit)? = {}): SecondScreenPresentation {
        val activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        val display = activity.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        return SecondScreenPresentation(activity, display, {}, {}, {}, onMenu = {}, onSwap = swap)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun rootChildren(panel: SecondScreenPresentation): List<View> {
        val root: ViewGroup = ReflectionHelpers.getField(panel, "root")
        return (0 until root.childCount).map { root.getChildAt(it) }
    }

    @Test fun trackpadPanelHasTheBarWithTheKeyboardToggleState() {
        SessionState.mode = SessionService.MODE_STEAM
        val panel = presentation()
        try {
            panel.show()
            panel.showMode(SecondScreenMode.KEYBOARD_TRACKPAD)
            val children = rootChildren(panel)
            assertTrue(children[0] is ComposeView)
            assertTrue(children[1] is InputSurfaceView)
            val bar = panel.barModel(SecondScreenMode.KEYBOARD_TRACKPAD)!!
            assertEquals(context.getString(R.string.second_mode_keyboard_trackpad), bar.title)
            assertNotNull(bar.onClose); assertNotNull(bar.onMenu); assertNotNull(bar.onSteam); assertNotNull(bar.onQam)
            assertNull(bar.tabs)
            val keyboard = bar.extras.single()
            assertEquals(context.getString(R.string.second_show_keyboard), keyboard.description)
            assertFalse(keyboard.active)
            ReflectionHelpers.getField<MutableState<Boolean>>(panel, "keyboardShown").value = true
            val shown = panel.barModel(SecondScreenMode.KEYBOARD_TRACKPAD)!!.extras.single()
            assertEquals(context.getString(R.string.second_hide_keyboard), shown.description)
            assertTrue(shown.active)
        } finally { panel.closeControls() }
    }

    @Test fun deckPanelHasTheBarWithSwapAndTabsAndProfileControlsStayFullScreen() {
        SessionState.mode = SessionService.MODE_STEAM
        SessionState.deckPad = true
        var swaps = 0
        val panel = presentation { swaps++ }
        try {
            panel.show()
            panel.showMode(SecondScreenMode.DECK_CONTROLS)
            assertTrue(rootChildren(panel)[0] is ComposeView)
            val bar = panel.barModel(SecondScreenMode.DECK_CONTROLS)!!
            assertEquals(context.getString(R.string.second_mode_deck_controls), bar.title)
            assertEquals(listOf(context.getString(R.string.swap_screens)), bar.extras.map { it.description })
            bar.extras.single().onClick()
            assertEquals(1, swaps)
            assertEquals(DeckPanel.entries.map { context.getString(it.label) }, bar.tabs!!.labels)
            assertEquals(DeckPanel.BOTH.ordinal, bar.tabs!!.selected)
            panel.showMode(SecondScreenMode.CUSTOM_CONTROLS)
            assertNull(panel.barModel(SecondScreenMode.CUSTOM_CONTROLS))
            assertTrue(rootChildren(panel)[0] is InputSurfaceView)
        } finally { panel.closeControls() }
        val noSwap = presentation(swap = null)
        try {
            noSwap.show()
            noSwap.showMode(SecondScreenMode.DECK_CONTROLS)
            assertTrue(noSwap.barModel(SecondScreenMode.DECK_CONTROLS)!!.extras.isEmpty())
        } finally { noSwap.closeControls() }
    }

    @Test fun nonSteamSessionsHideSteamAndQuickAccess() {
        for (mode in listOf(SessionService.MODE_DESKTOP, SessionService.MODE_RUN)) {
            SessionState.mode = mode
            SessionState.deckPad = true
            val panel = presentation()
            try {
                panel.show()
                for (panelMode in listOf(SecondScreenMode.KEYBOARD_TRACKPAD, SecondScreenMode.DECK_CONTROLS)) {
                    panel.showMode(panelMode)
                    val bar = panel.barModel(panelMode)!!
                    assertNull(bar.onSteam); assertNull(bar.onQam)
                    assertNotNull(bar.onClose); assertNotNull(bar.onMenu)
                }
            } finally { panel.closeControls() }
        }
        val stock = InputLibraries.read(context).layouts.filter { it.id == InputLibraries.DECK || it.id == InputLibraries.TRACKPAD }
        assertTrue(stock.all { layout -> layout.elements.none { it.id.startsWith("command:") } })
    }

    @Test fun deckTabsSwitchTheLayoutAndAreRemembered() {
        SessionState.mode = SessionService.MODE_STEAM
        SessionState.deckPad = true
        val library = InputLibraries.read(context)
        fun shown(panel: SecondScreenPresentation): List<InputSurfaceElement> {
            val controls = descendants(panel.window!!.decorView).filterIsInstance<InputSurfaceView>().single()
            return descendants(controls).mapNotNull { it.tag as? InputSurfaceElement }.toList()
        }
        val first = presentation()
        try {
            first.show()
            first.showMode(SecondScreenMode.DECK_CONTROLS)
            assertEquals(library.layouts.single { it.id == DeckPanel.BOTH.layout }.elements, shown(first))
            val controls = descendants(first.window!!.decorView).filterIsInstance<InputSurfaceView>().single()
            for (panel in listOf(DeckPanel.GRIPS, DeckPanel.LEFT, DeckPanel.RIGHT, DeckPanel.GAMEPAD)) {
                first.barModel(SecondScreenMode.DECK_CONTROLS)!!.tabs!!.onSelect(panel.ordinal)
                assertEquals(panel, SessionPrefs.deckPanel(context))
                assertEquals(panel.ordinal, first.barModel(SecondScreenMode.DECK_CONTROLS)!!.tabs!!.selected)
                assertSame(controls, descendants(first.window!!.decorView).filterIsInstance<InputSurfaceView>().single())
                assertEquals(library.layouts.single { it.id == panel.layout }.elements, shown(first))
            }
            first.barModel(SecondScreenMode.DECK_CONTROLS)!!.tabs!!.onSelect(DeckPanel.GRIPS.ordinal)
        } finally { first.closeControls() }
        val second = presentation()
        try {
            second.show()
            second.showMode(SecondScreenMode.DECK_CONTROLS)
            assertEquals(DeckPanel.GRIPS.ordinal, second.barModel(SecondScreenMode.DECK_CONTROLS)!!.tabs!!.selected)
            assertEquals(library.layouts.single { it.id == DeckPanel.GRIPS.layout }.elements, shown(second))
            second.showMode(SecondScreenMode.KEYBOARD_TRACKPAD)
            assertEquals(library.layouts.single { it.id == InputLibraries.TRACKPAD }.elements, shown(second))
        } finally { second.closeControls() }
        assertEquals(DeckPanel.GRIPS.bindings, SecondScreenMode.DECK_CONTROLS.panel(context))
        assertEquals(SecondScreenMode.KEYBOARD_TRACKPAD.bindings, SecondScreenMode.KEYBOARD_TRACKPAD.panel(context))
    }

    @Test fun barTabRowSelectsTabs() {
        var picked = -1
        compose.setContent {
            DroidDeckTheme {
                BottomScreenBar(BottomBarModel("Deck", {}, {}, null, null, tabs = deckPanelTabs(context, DeckPanel.BOTH) { picked = it.ordinal }))
            }
        }
        for (panel in DeckPanel.entries) compose.onNodeWithText(context.getString(panel.label)).assertIsDisplayed()
        compose.onNodeWithTag("tab-${DeckPanel.LEFT.ordinal}").performClick()
        compose.runOnIdle { assertEquals(DeckPanel.LEFT.ordinal, picked) }
    }

    @Test fun swappedTopScreenShowsTheTerminalWithItsBar() {
        SessionState.mode = SessionService.MODE_STEAM
        val activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        ReflectionHelpers.getField<MutableState<SecondScreenMode>>(activity, "secondScreenMode\$delegate").value = SecondScreenMode.TERMINAL
        val method = SessionActivity::class.java.getDeclaredMethod("SwappedTopPanel", androidx.compose.runtime.Composer::class.java, Int::class.javaPrimitiveType)
        method.isAccessible = true
        compose.setContent { DroidDeckTheme { method.invoke(activity, currentComposer, 0) } }
        compose.onNodeWithText(context.getString(R.string.second_mode_terminal)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.drawer_keyboard)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.swap_screens)).assertIsDisplayed()
        compose.onNodeWithTag("bottom-bar-steam").assertIsDisplayed()
    }

    @Test fun swappedTopScreenDeckPanelHasTabs() {
        SessionState.mode = SessionService.MODE_DESKTOP
        SessionState.deckPad = true
        val activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        ReflectionHelpers.getField<MutableState<SecondScreenMode>>(activity, "secondScreenMode\$delegate").value = SecondScreenMode.DECK_CONTROLS
        val method = SessionActivity::class.java.getDeclaredMethod("SwappedTopPanel", androidx.compose.runtime.Composer::class.java, Int::class.javaPrimitiveType)
        method.isAccessible = true
        compose.setContent { DroidDeckTheme { method.invoke(activity, currentComposer, 0) } }
        compose.onNodeWithText(context.getString(R.string.second_mode_deck_controls)).assertIsDisplayed()
        compose.onNodeWithTag("bottom-bar-tabs").assertIsDisplayed()
        compose.onNodeWithTag("bottom-bar-steam").assertDoesNotExist()
        compose.onNodeWithTag("bottom-bar-close").assertIsDisplayed()
    }

    @Test fun unfoldedControlsGetTheSessionBar() {
        SessionState.mode = SessionService.MODE_STEAM
        val activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        assertTrue(ReflectionHelpers.callInstanceMethod<View>(activity, "unfoldedBar") is ComposeView)
    }
}
