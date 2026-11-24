package com.droiddeck.launcher.session

import android.os.Handler
import android.os.Looper
import android.os.Messenger
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import com.droiddeck.launcher.input.KeyboardHost
import com.droiddeck.launcher.wayland.BottomDesktopCompositorService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class BottomDesktopInputTest {

    private data class Sent(val what: Int, val arg1: Int, val arg2: Int, val text: String?)

    private val sent = ArrayList<Sent>()
    private val key = BottomDesktopCompositorService.MSG_KEY
    private val text = BottomDesktopCompositorService.MSG_TEXT
    private val textKey = BottomDesktopCompositorService.KEY_TEXT

    private lateinit var desktop: BottomDesktop
    private var original: Messenger? = null

    @Before
    fun setUp() {
        desktop = BottomDesktop
        original = ReflectionHelpers.getField<Messenger>(desktop, "service")
        sent.clear()
        val recorder = Handler(Looper.getMainLooper(), Handler.Callback { msg ->
            sent.add(Sent(msg.what, msg.arg1, msg.arg2, msg.data?.getString(textKey)))
            true
        })
        ReflectionHelpers.setField(desktop, "service", Messenger(recorder))
    }

    @After
    fun tearDown() {
        shadowOf(Looper.getMainLooper()).idle()
        ReflectionHelpers.setField(desktop, "service", original)
    }

    @Test
    fun pureControlBatchSendsOneDownAndOneUpPerChar() {
        BottomDesktop.sendText("\r\n\b\u007f\t")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            press(28) + press(28) + press(14) + press(111) + press(15),
            sent
        )
        assertTrue(sent.none { it.what == text })
    }

    @Test
    fun ordinaryTextIncludingSupplementaryAndNewlineIsOneTextMessage() {
        val value = "中文😀\nline"
        BottomDesktop.sendText(value)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(Sent(text, 0, 0, value)), sent)
    }

    @Test
    fun keyboardHostOrderMatchesImeEditingSequence() {
        val host = KeyboardHost(RuntimeEnvironment.getApplication(), { true }, { BottomDesktop.sendText(it) })
        ReflectionHelpers.setField(host, "shown", true)
        val connection = host.onCreateInputConnection(EditorInfo())!!
        connection.commitText("中文", 1)
        connection.performEditorAction(EditorInfo.IME_ACTION_NONE)
        connection.deleteSurroundingText(1, 0)
        connection.deleteSurroundingText(0, 1)
        assertTrue(connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB)))
        assertTrue(connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB)))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            listOf(Sent(text, 0, 0, "中文")) + press(28) + press(14) + press(111) + press(15),
            sent
        )
        assertEquals(1, sent.count { it.what == key && it.arg1 == 15 && it.arg2 == 1 })
        assertEquals(1, sent.count { it.what == key && it.arg1 == 15 && it.arg2 == 0 })
    }

    private fun press(evdev: Int) = listOf(Sent(key, evdev, 1, null), Sent(key, evdev, 0, null))
}
