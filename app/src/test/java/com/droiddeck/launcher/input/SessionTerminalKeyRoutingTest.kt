package com.droiddeck.launcher.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.MutableState
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.ui.SessionTerminalPanel
import com.droiddeck.launcher.wayland.CompositorHost
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [SessionInputPointerTest.CompositorShadow::class])
class SessionTerminalKeyRoutingTest {
    private val activity = Robolectric.buildActivity(SessionActivity::class.java).get()
    private val received = mutableListOf<Pair<Int, Int>>()
    private val previousStarted = CompositorHost.isStarted
    private val previousRunning = SessionState.running

    @Before fun setUp() {
        SessionState.running = false
        ReflectionHelpers.setStaticField(CompositorHost::class.java, "started", true)
        ReflectionHelpers.getField<MutableState<Boolean>>(activity, "bottomOutputActive\$delegate").value = true
        SessionInputPointerTest.CompositorShadow.keys.clear()
    }

    @After fun tearDown() {
        ReflectionHelpers.setStaticField(CompositorHost::class.java, "started", previousStarted)
        SessionState.running = previousRunning
    }

    @Test fun swappedTerminalReceivesTextEnterAndKeyReleasesInsteadOfTheGame() = checkTerminal(consumeRelease = true)

    @Test fun terminalKeyReleasesNeverLeakIntoTheGame() = checkTerminal(consumeRelease = false)

    private fun checkTerminal(consumeRelease: Boolean) {
        val panel = SessionTerminalPanel(activity)
        panel.removeAllViews()
        panel.addView(editor(consumeRelease))
        activity.setContentView(panel)
        layoutWindow()
        assertTrue(panel.getChildAt(0).requestFocus())
        shadowOf(activity).setCurrentFocus(panel.getChildAt(0))
        assertSame(panel.getChildAt(0), activity.currentFocus)
        typeKeys()
        assertEquals(expected(), received)
        assertTrue(SessionInputPointerTest.CompositorShadow.keys.isEmpty())
    }

    @Test fun ordinaryFocusedViewsDoNotStealGameKeyboardInput() {
        val root = FrameLayout(activity)
        root.addView(editor())
        activity.setContentView(root)
        layoutWindow()
        assertTrue(root.getChildAt(0).requestFocus())
        shadowOf(activity).setCurrentFocus(root.getChildAt(0))
        typeKeys()
        assertTrue(received.isEmpty())
        assertEquals(listOf(38 to 1, 38 to 0, 31 to 1, 31 to 0, 28 to 1, 28 to 0), SessionInputPointerTest.CompositorShadow.keys)
    }

    @Test fun aReleaseHandledByTheDrawerDoesNotStealTheNextGameKeyRelease() {
        val terminal = SessionTerminalPanel(activity).apply { removeAllViews(); addView(editor()) }
        activity.setContentView(terminal)
        layoutWindow()
        terminal.getChildAt(0).requestFocus()
        shadowOf(activity).setCurrentFocus(terminal.getChildAt(0))
        fun key(action: Int) = KeyEvent(0L, 0L, action, KeyEvent.KEYCODE_L, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)
        assertTrue(activity.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN)))
        val root = FrameLayout(activity).apply { addView(editor()) }
        activity.setContentView(root)
        layoutWindow()
        root.getChildAt(0).requestFocus()
        shadowOf(activity).setCurrentFocus(root.getChildAt(0))
        val open: MutableState<Boolean> = ReflectionHelpers.getField(activity, "drawerOpenState\$delegate")
        open.value = true
        assertTrue(activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP)))
        open.value = false
        assertTrue(activity.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN)))
        assertTrue(activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP)))
        assertEquals(listOf(38 to 1, 38 to 0), SessionInputPointerTest.CompositorShadow.keys)
    }

    private fun editor(consumeRelease: Boolean = true) = object : View(activity) {
        init { isFocusableInTouchMode = true }
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            received += event.keyCode to event.action
            return consumeRelease || event.action == KeyEvent.ACTION_DOWN
        }
    }

    private fun layoutWindow() {
        activity.window.decorView.apply {
            measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
            layout(0, 0, 800, 600)
        }
    }

    private fun expected() = listOf(KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_ENTER).flatMap { code ->
        listOf(code to KeyEvent.ACTION_DOWN, code to KeyEvent.ACTION_UP)
    }

    private fun typeKeys() {
        for ((code, action) in expected()) assertTrue(activity.dispatchKeyEvent(
            KeyEvent(0L, 0L, action, code, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)))
    }
}
