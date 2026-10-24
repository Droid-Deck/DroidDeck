package com.droiddeck.launcher.input

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class KeyboardHostTest {
    private lateinit var imm: InputMethodManager
    private lateinit var host: KeyboardHost
    private lateinit var root: FrameLayout
    private lateinit var previousFocus: View

    @Before fun setUp() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        previousFocus = View(activity).apply { isFocusableInTouchMode = true }
        host = KeyboardHost(activity)
        root = FrameLayout(activity).apply { addView(previousFocus); addView(host) }
        activity.setContentView(root)
        previousFocus.requestFocus()
        idle()
    }

    @Config(sdk = [33])
    @Test fun toggleShowsAKeyboardTheSystemDismissed() {
        host.show()
        idle()
        imm.hideSoftInputFromWindow(host.windowToken, 0)
        idle()
        host.toggle()
        idle()
        assertTrue(shadowOf(imm).isSoftInputVisible)
        assertTrue(host.onCheckIsTextEditor())
    }

    @Config(sdk = [28])
    @Test fun toggleHidesAKeyboardThatIsShowing() {
        host.show()
        idle()
        applyImeVisibility(true)
        host.toggle()
        idle()
        assertFalse(shadowOf(imm).isSoftInputVisible)
        assertFalse(host.onCheckIsTextEditor())
    }

    @Config(sdk = [28, 33])
    @Test fun closingRestoresFocusEvenAfterRepeatedShowRequests() {
        host.show()
        host.show()
        assertTrue(host.hasFocus())
        host.hide()
        assertTrue(previousFocus.hasFocus())
        assertFalse(host.isFocusable)
        assertNull(host.onCreateInputConnection(EditorInfo()))
    }

    @Config(sdk = [33])
    @Test fun systemDismissalEndsEditingAndRestoresFocus() {
        host.show()
        applyImeVisibility(true)
        assertTrue(host.imeVisible)
        applyImeVisibility(false)
        assertFalse(host.imeVisible)
        assertFalse(host.onCheckIsTextEditor())
        assertNull(host.onCreateInputConnection(EditorInfo()))
        assertTrue(previousFocus.hasFocus())
        host.toggle()
        idle()
        assertTrue(shadowOf(imm).isSoftInputVisible)
        assertTrue(host.hasFocus())
    }

    @Config(sdk = [33])
    @Test fun removingHostClosesItsKeyboardAndClearsVisibility() {
        host.show()
        applyImeVisibility(true)
        assertTrue(host.imeVisible)
        root.removeView(host)
        idle()
        assertFalse(shadowOf(imm).isSoftInputVisible)
        assertFalse(host.onCheckIsTextEditor())
        assertFalse(host.imeVisible)
    }

    private fun applyImeVisibility(visible: Boolean) {
        val height = if (visible) 300 else 0
        val builder = WindowInsetsCompat.Builder()
        if (Build.VERSION.SDK_INT < 30) {
            @Suppress("DEPRECATION")
            builder.setSystemWindowInsets(Insets.of(0, 0, 0, height))
        } else {
            builder.setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, height))
                .setVisible(WindowInsetsCompat.Type.ime(), visible)
        }
        val insets = builder.build()
        host.dispatchApplyWindowInsets(insets.toWindowInsets()!!)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}
