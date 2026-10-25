package com.droiddeck.launcher.input

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class KeyboardHostTest {
    private lateinit var imm: InputMethodManager
    private lateinit var host: KeyboardHost
    private val keys = mutableListOf<KeyEvent>()
    private val texts = mutableListOf<String>()

    @Before fun setUp() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        host = KeyboardHost(activity, { keys += it; true }, { texts += it })
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

    @Config(sdk = [33])
    @Test fun shownEditorIsNotDescribedAsAPasswordField() {
        host.show()
        idle()

        val attrs = EditorInfo()
        val connection = host.onCreateInputConnection(attrs)

        assertNotNull("shown host must expose an input connection", connection)
        assertEquals(
            "editor must be declared as a text field",
            InputType.TYPE_CLASS_TEXT,
            attrs.inputType and InputType.TYPE_MASK_CLASS,
        )

        val variation = attrs.inputType and InputType.TYPE_MASK_VARIATION
        assertFalse(
            "text field must not be a password field",
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )
        assertFalse(
            "text field must not be a visible password field",
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        )
        assertFalse(
            "text field must not be a web password field",
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )
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

    @Config(sdk = [33])
    @Test fun mixedScriptTextIsCommittedOnceWithoutKeyEvents() {
        host.show()
        idle()
        val text = "中文ABC123，。é😀"

        assertTrue(connection().commitText(text, 1))
        idle()

        assertEquals("the whole string must reach the guest once", listOf(text), texts)
        assertTrue("non-ASCII text must not be turned into key events", keys.isEmpty())
    }

    @Config(sdk = [33])
    @Test fun composingWithoutACommitReachesNoGuest() {
        host.show()
        idle()
        val connection = connection()

        connection.setComposingText("zhong", 1)
        connection.setComposingText("中文", 1)
        connection.finishComposingText()
        idle()

        assertTrue(keys.isEmpty())
        assertTrue(texts.isEmpty())
    }

    @Config(sdk = [33])
    @Test fun finishedCandidateIsCommittedExactlyOnce() {
        host.show()
        idle()
        val connection = connection()

        connection.setComposingText("zhong", 1)
        connection.commitText("中文", 1)
        idle()

        assertEquals(listOf("中文"), texts)
        assertTrue(keys.isEmpty())
    }

    @Config(sdk = [33])
    @Test fun asciiTextIsCommittedAsOneTextCallback() {
        host.show()
        idle()

        assertTrue(connection().commitText("ab", 1))
        idle()

        assertEquals("ASCII must reach the guest as one whole commit", listOf("ab"), texts)
        assertTrue("ASCII must not take the key event path", keys.isEmpty())
    }

    @Config(sdk = [33])
    @Test fun adjacentCommitsKeepTheirOrder() {
        host.show()
        idle()
        val connection = connection()

        assertTrue(connection.commitText("中文", 1))
        assertTrue(connection.commitText(" ", 1))
        assertTrue(connection.commitText("abc", 1))
        idle()

        assertEquals("every commit must keep the order the IME sent it", listOf("中文", " ", "abc"), texts)
        assertTrue("no commit may take the key event path", keys.isEmpty())
    }

    @Config(sdk = [33])
    @Test fun backspaceAndEnterQueueOrderedControls() {
        host.show()
        idle()
        val connection = connection()

        connection.deleteSurroundingText(1, 0)
        connection.performEditorAction(EditorInfo.IME_ACTION_SEND)
        idle()

        assertEquals(
            "soft edits must ride the ordered text FIFO, not the key event route",
            listOf("\b", "\r"), texts,
        )
        assertTrue("soft edits must not become key events", keys.isEmpty())
    }

    @Config(sdk = [33])
    @Test fun deleteSurroundingTextQueuesBackspaceThenForwardDelete() {
        host.show()
        idle()

        assertTrue(connection().deleteSurroundingText(2, 1))
        idle()

        assertEquals(listOf("\b\b\u007f"), texts)
        assertTrue("deletions must not become key events", keys.isEmpty())
    }

    @Config(sdk = [33])
    @Test fun commitThenDeleteThenEditorActionKeepTheirOrder() {
        host.show()
        idle()
        val connection = connection()

        assertTrue(connection.commitText("中文", 1))
        assertTrue(connection.deleteSurroundingText(1, 0))
        assertTrue(connection.performEditorAction(EditorInfo.IME_ACTION_SEND))
        idle()

        assertEquals("a Chinese commit must not be overtaken by the soft edits after it",
                     listOf("中文", "\b", "\r"), texts)
        assertTrue(keys.isEmpty())
    }

    @Config(sdk = [33])
    @Test fun softEditKeysQueueExactlyOneControlPerPress() {
        host.show()
        idle()
        val connection = connection()

        for ((keyCode, control) in listOf(
            KeyEvent.KEYCODE_DEL to "\b",
            KeyEvent.KEYCODE_FORWARD_DEL to "\u007f",
            KeyEvent.KEYCODE_ENTER to "\r",
            KeyEvent.KEYCODE_TAB to "\t",
        )) {
            texts.clear()
            assertTrue(connection.sendKeyEvent(keyEvent(KeyEvent.ACTION_DOWN, keyCode)))
            assertTrue(connection.sendKeyEvent(keyEvent(KeyEvent.ACTION_UP, keyCode)))
            assertEquals("one down plus one up must queue exactly one control",
                         listOf(control), texts)
        }
        assertTrue("soft edit keys must not reach the key event route", keys.isEmpty())
    }

    @Config(sdk = [33])
    @Test fun modifiedSoftKeysStillTravelAsKeyEvents() {
        host.show()
        idle()
        val connection = connection()

        assertTrue(connection.sendKeyEvent(
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER,
                     KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)))
        idle()

        assertEquals("a modifier shortcut must keep using key events",
                     listOf(KeyEvent.KEYCODE_ENTER), keys.map { it.keyCode })
        assertTrue(texts.isEmpty())
    }

    private fun keyEvent(action: Int, keyCode: Int, metaState: Int = 0) =
        KeyEvent(0L, 0L, action, keyCode, 0, metaState)

    private fun connection(): InputConnection {
        val connection = host.onCreateInputConnection(EditorInfo())
        assertNotNull("shown host must expose an input connection", connection)
        return connection!!
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}
