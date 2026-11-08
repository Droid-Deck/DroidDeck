package com.droiddeck.launcher.ui

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Looper
import android.view.Display
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.input.KeyboardHost
import com.droiddeck.launcher.input.SecondScreenMode
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.wayland.WaylandCompositor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w800dp-h600dp", shadows = [SecondScreenImeTest.CompositorShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SecondScreenImeTest {
    private var previousMode = ""
    private var previousDeckPad = false

    @Before fun setUp() {
        previousMode = SessionState.mode
        previousDeckPad = SessionState.deckPad
        SessionState.deckPad = false
        CompositorShadow.commits.clear()
    }

    @After fun tearDown() {
        SessionState.mode = previousMode
        SessionState.deckPad = previousDeckPad
    }

    @Test fun editorActionClosesFieldKeyboardAfterOneCommit() {
        var closes = 0
        val fixture = keyboard(forField = true) { closes++ }
        try {
            assertTrue(fixture.connection.performEditorAction(EditorInfo.IME_ACTION_SEND))
            idle()

            assertEquals(listOf("\r"), CompositorShadow.commits)
            assertEquals(1, closes)
            assertFalse(fixture.host.shown)
            assertFalse(fixture.panel.fieldKeyboardOpen)
        } finally {
            fixture.close()
        }
    }

    @Test fun unmodifiedEnterKeyClosesFieldKeyboard() {
        var closes = 0
        val fixture = keyboard(forField = true) { closes++ }
        try {
            assertTrue(fixture.connection.sendKeyEvent(keyEvent(KeyEvent.ACTION_DOWN)))
            assertTrue(fixture.connection.sendKeyEvent(keyEvent(KeyEvent.ACTION_UP)))
            idle()

            assertEquals(listOf("\r"), CompositorShadow.commits)
            assertEquals(1, closes)
            assertFalse(fixture.host.shown)
            assertFalse(fixture.panel.fieldKeyboardOpen)
        } finally {
            fixture.close()
        }
    }

    @Test fun ordinaryTextAndNewlineLeaveFieldKeyboardOpen() {
        var closes = 0
        val fixture = keyboard(forField = true) { closes++ }
        try {
            assertTrue(fixture.connection.commitText("hello", 1))
            assertTrue(fixture.connection.commitText("line\n", 1))
            idle()

            assertEquals(listOf("hello", "line\n"), CompositorShadow.commits)
            assertEquals(0, closes)
            assertTrue(fixture.host.shown)
            assertTrue(fixture.panel.fieldKeyboardOpen)
        } finally {
            fixture.close()
        }
    }

    @Test fun enterLeavesNonFieldKeyboardOpen() {
        var closes = 0
        val fixture = keyboard(forField = false) { closes++ }
        try {
            assertTrue(fixture.connection.sendKeyEvent(keyEvent(KeyEvent.ACTION_DOWN)))
            assertTrue(fixture.connection.sendKeyEvent(keyEvent(KeyEvent.ACTION_UP)))
            idle()

            assertEquals(listOf("\r"), CompositorShadow.commits)
            assertEquals(0, closes)
            assertTrue(fixture.host.shown)
            assertFalse(fixture.panel.fieldKeyboardOpen)
        } finally {
            fixture.close()
        }
    }

    private fun keyboard(forField: Boolean, onClosed: () -> Unit): Fixture {
        val activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        val display = activity.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val panel = SecondScreenPresentation(activity, display, {}, {}, {}, onKeyboardClosed = onClosed)
        panel.show()
        panel.showMode(SecondScreenMode.CUSTOM_CONTROLS)
        panel.requestKeyboard(forField)
        val host: KeyboardHost = ReflectionHelpers.getField(panel, "keyboardHost")
        val root: View = ReflectionHelpers.getField(panel, "root")
        val owner = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            (root.parent as ViewGroup).removeView(root)
            owner.get().setContentView(root)
            host.show()
            idle()
            assertTrue("Presentation root is not attached to window after host.show()", root.isAttachedToWindow)
            assertTrue(host.shown)
            val connection = host.onCreateInputConnection(EditorInfo())
            assertNotNull(connection)
            return Fixture(panel, host, connection!!, owner)
        } catch (t: Throwable) {
            panel.closeControls()
            owner.pause().stop().destroy()
            throw t
        }
    }

    private fun keyEvent(action: Int) = KeyEvent(0L, 0L, action, KeyEvent.KEYCODE_ENTER, 0)

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))

    private data class Fixture(
        val panel: SecondScreenPresentation,
        val host: KeyboardHost,
        val connection: InputConnection,
        val owner: ActivityController<Activity>,
    ) {
        fun close() {
            panel.closeControls()
            owner.pause().stop().destroy()
        }
    }

    @Implements(WaylandCompositor::class, isInAndroidSdk = false)
    class CompositorShadow {
        companion object {
            val commits = mutableListOf<String>()

            @JvmStatic @Implementation fun __staticInitializer__() {}

            @JvmStatic @Implementation fun textInputCommit(text: String) {
                commits += text
            }
        }
    }
}
