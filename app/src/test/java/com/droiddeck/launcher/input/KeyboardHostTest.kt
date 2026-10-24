package com.droiddeck.launcher.input

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.text.InputType
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
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

    @Before fun setUp() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        host = KeyboardHost(activity)
        activity.setContentView(FrameLayout(activity).apply { addView(host) })
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
        assertTrue(host.shown)
    }

    @Config(sdk = [28])
    @Test fun toggleHidesAKeyboardThatIsShowing() {
        host.show()
        idle()
        host.toggle()
        idle()
        assertFalse(shadowOf(imm).isSoftInputVisible)
        assertFalse(host.shown)
    }

    @Test fun inputConnectionUsesNonPasswordUriTextWithoutSuggestionsOrPersonalizedLearning() {
        host.show()
        val outAttrs = EditorInfo()

        assertNotNull(host.onCreateInputConnection(outAttrs))
        assertEquals(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            outAttrs.inputType,
        )
        assertEquals(
            EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_FORCE_ASCII or
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            outAttrs.imeOptions,
        )
    }

    @Test fun inputConnectionForwardsCommittedTextAndExplicitReplacementDeletesToTheGuest() {
        val guest = StringBuilder()
        val keyboard = KeyboardHost(host.context) { event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.keyCode == KeyEvent.KEYCODE_DEL) {
                    if (guest.isNotEmpty()) guest.deleteCharAt(guest.lastIndex)
                } else if (event.unicodeChar != 0) {
                    guest.append(event.unicodeChar.toChar())
                }
            }
            true
        }
        keyboard.show()
        val connection = requireNotNull(keyboard.onCreateInputConnection(EditorInfo()))
        connection.commitText("teh", 1)
        assertEquals("teh", guest.toString())
        connection.deleteSurroundingText(3, 0)
        connection.commitText("the ", 1)
        assertEquals("the ", guest.toString())
        keyboard.hide()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}
