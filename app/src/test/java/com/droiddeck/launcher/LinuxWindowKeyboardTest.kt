package com.droiddeck.launcher

import android.app.Application
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import com.droiddeck.launcher.input.KeyboardHost
import com.droiddeck.launcher.session.LinuxWindowEditor
import com.droiddeck.launcher.session.LinuxWindows
import com.droiddeck.launcher.session.SessionPrefs
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34], manifest = Config.NONE, application = Application::class)
class LinuxWindowKeyboardTest {
    private lateinit var activity: LinuxWindowActivity
    private lateinit var keyboard: KeyboardHost
    private lateinit var imm: InputMethodManager
    private val editor = LinuxWindowEditor("test-window", "test-editor", 0)

    @Before fun setUp() {
        val controller = Robolectric.buildActivity(LinuxWindowActivity::class.java)
        activity = controller.get()
        keyboard = KeyboardHost(activity)
        imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        activity.setContentView(FrameLayout(activity).apply { addView(keyboard) })
        ReflectionHelpers.setField(activity, "key", editor.key)
        ReflectionHelpers.setField(activity, "keyboard", keyboard)
        LinuxWindows.claim(editor.key)
        controller.visible().windowFocusChanged(true)
        assertTrue(activity.window.decorView.hasWindowFocus())
        SessionPrefs.setDesktopAutoKeyboard(activity, false)
    }

    @After fun close() {
        keyboard.hide()
        LinuxWindows.desktop()
        activity.finish()
    }

    @Test fun anOpenWindowReadsChangesToTheMainKeyboardSetting() {
        textFocusAfterTap()
        assertFalse(keyboard.shown)
        assertFalse(shadowOf(imm).isSoftInputVisible)

        SessionPrefs.setDesktopAutoKeyboard(activity, true)
        textFocusAfterTap()
        assertTrue(keyboard.shown)
        assertTrue(shadowOf(imm).isSoftInputVisible)

        keyboard.hide()
        SessionPrefs.setDesktopAutoKeyboard(activity, false)
        textFocusAfterTap()
        assertFalse(keyboard.shown)
        assertFalse(shadowOf(imm).isSoftInputVisible)
    }

    @Test fun disablingAutomaticOpeningCancelsAPendingFocusRequestAndKeepsManualOpening() {
        SessionPrefs.setDesktopAutoKeyboard(activity, true)
        ReflectionHelpers.setField(activity, "lastTapAt", SystemClock.uptimeMillis())
        keyboard.postDelayed({ activity.textFocusChanged(editor) }, 180)
        SessionPrefs.setDesktopAutoKeyboard(activity, false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(180))
        assertFalse(keyboard.shown)
        assertFalse(shadowOf(imm).isSoftInputVisible)

        keyboard.toggle()
        assertTrue(keyboard.shown)
        assertTrue(shadowOf(imm).isSoftInputVisible)
    }

    private fun textFocusAfterTap() {
        ReflectionHelpers.setField(activity, "lastTapAt", SystemClock.uptimeMillis())
        activity.textFocusChanged(editor)
    }
}
