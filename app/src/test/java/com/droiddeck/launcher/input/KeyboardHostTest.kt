package com.droiddeck.launcher.input

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
@Config(sdk = [28, 33])
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

    @Config(sdk = [28, 29])
    @Test fun showReopensAKeyboardCollapsedWithoutBackOnOlderAndroid() {
        host.show()
        idle()
        assertTrue(host.hasFocus())
        imm.hideSoftInputFromWindow(host.windowToken, 0)
        idle()
        assertTrue(host.shown)
        assertFalse(shadowOf(imm).isSoftInputVisible)
        host.show()
        idle()
        assertTrue(shadowOf(imm).isSoftInputVisible)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun linuxEditorForwardsUnicodeAndCompositionWithoutGeneratingDuplicateKeys() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val keys = mutableListOf<KeyEvent>()
        val commits = mutableListOf<String>()
        val preedits = mutableListOf<Pair<String, Int>>()
        val host = KeyboardHost(activity) { keys.add(it); true }
        activity.setContentView(FrameLayout(activity).apply { addView(host) })
        host.onCommitText = { commits.add(it); true }
        host.onComposingText = { text, cursor -> preedits.add(text to cursor); true }
        assertNull(host.onCreateInputConnection(EditorInfo()))
        host.show()
        val connection = host.onCreateInputConnection(EditorInfo())
        assertNotNull(connection)
        assertTrue(connection!!.setComposingText("zhong", 1))
        assertTrue(connection.commitText("中文🙂", 1))
        assertTrue(connection.finishComposingText())
        assertEquals(listOf("中文🙂"), commits)
        assertEquals(listOf("zhong" to 1, "" to 0), preedits)
        assertTrue(keys.isEmpty())
        host.hide()
        assertFalse(host.onCheckIsTextEditor())
        assertNull(host.onCreateInputConnection(EditorInfo()))
        activity.finish()
    }

    @Test fun deletionAndEnterKeepTheExistingKeyPathAndPasswordEditorType() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val keys = mutableListOf<KeyEvent>()
        val host = KeyboardHost(activity) { keys.add(it); true }
        activity.setContentView(FrameLayout(activity).apply { addView(host) })
        host.editorInputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        host.show()
        val info = EditorInfo()
        val connection = host.onCreateInputConnection(info)!!
        assertEquals(host.editorInputType, info.inputType)
        connection.deleteSurroundingText(1, 1)
        connection.performEditorAction(EditorInfo.IME_ACTION_DONE)
        assertEquals(listOf(KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_ENTER),
            keys.filter { it.action == KeyEvent.ACTION_DOWN }.map { it.keyCode })
        assertEquals(6, keys.size)
        activity.finish()
    }

    @Test fun finishingCompositionCommitsTheLatestUnicodeTextExactlyOnce() {
        val commits = mutableListOf<String>()
        val preedits = mutableListOf<Pair<String, Int>>()
        host.onCommitText = { commits.add(it); true }
        host.onComposingText = { text, cursor -> preedits.add(text to cursor); true }
        host.show()
        val connection = host.onCreateInputConnection(EditorInfo())!!
        assertTrue(connection.setComposingText("zhong", 1))
        assertTrue(connection.setComposingText("中文🙂", 1))
        assertTrue(connection.finishComposingText())
        assertTrue(connection.finishComposingText())
        assertEquals(listOf("中文🙂"), commits)
        assertEquals(listOf("zhong" to 1, "中文🙂" to 1, "" to 0, "" to 0), preedits)
    }

    @Test fun cancellingCompositionOrCommittingEmptyTextDiscardsPendingText() {
        val commits = mutableListOf<String>()
        host.onCommitText = { commits.add(it); true }
        host.show()
        val connection = host.onCreateInputConnection(EditorInfo())!!
        for (empty in listOf("", null)) {
            connection.setComposingText("discard", 1)
            connection.setComposingText(empty, 1)
            assertTrue(connection.finishComposingText())
            connection.setComposingText("discard", 1)
            connection.commitText(empty, 1)
            assertTrue(connection.finishComposingText())
        }
        assertTrue(commits.isEmpty())
    }

    @Test fun hidingAndReopeningRejectsInputFromThePreviousContext() {
        val commits = mutableListOf<String>()
        host.onCommitText = { commits.add("old:$it"); true }
        host.show()
        val old = host.onCreateInputConnection(EditorInfo())!!
        old.setComposingText("old text", 1)
        host.hide()
        host.onCommitText = { commits.add("new:$it"); true }
        host.show()
        val current = host.onCreateInputConnection(EditorInfo())!!
        assertFalse(old.finishComposingText())
        assertFalse(old.commitText("late commit", 1))
        assertFalse(old.setComposingText("late composition", 1))
        assertFalse(old.deleteSurroundingText(1, 0))
        assertFalse(old.performEditorAction(EditorInfo.IME_ACTION_DONE))
        assertFalse(old.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)))
        assertTrue(current.finishComposingText())
        current.setComposingText("new text", 1)
        assertTrue(current.finishComposingText())
        assertEquals(listOf("new:new text"), commits)
    }

    @Test fun replacingAnInputConnectionDiscardsItsPendingComposition() {
        val commits = mutableListOf<String>()
        host.onCommitText = { commits.add(it); true }
        host.show()
        val old = host.onCreateInputConnection(EditorInfo())!!
        old.setComposingText("old text", 1)
        val current = host.onCreateInputConnection(EditorInfo())!!
        assertFalse(old.finishComposingText())
        old.closeConnection()
        current.setComposingText("current text", 1)
        assertTrue(current.finishComposingText())
        assertEquals(listOf("current text"), commits)
    }

    @Test fun closingOrLosingFocusDiscardsPendingComposition() {
        val commits = mutableListOf<String>()
        host.onCommitText = { commits.add(it); true }
        val cancellations = listOf<() -> Unit>(
            { host.clearFocus() },
            { host.onWindowFocusChanged(false) },
            { (host.parent as FrameLayout).removeView(host) },
        )
        for (cancel in cancellations) {
            host.show()
            val connection = host.onCreateInputConnection(EditorInfo())!!
            connection.setComposingText("discard", 1)
            cancel()
            assertFalse(connection.finishComposingText())
            assertFalse(connection.commitText("late commit", 1))
        }
        assertTrue(commits.isEmpty())
    }

    @Test fun closingTheCurrentConnectionRejectsLateComposition() {
        val commits = mutableListOf<String>()
        host.onCommitText = { commits.add(it); true }
        host.show()
        val connection = host.onCreateInputConnection(EditorInfo())!!
        connection.setComposingText("discard", 1)
        connection.closeConnection()
        assertFalse(connection.finishComposingText())
        assertFalse(connection.commitText("late commit", 1))
        assertTrue(commits.isEmpty())
    }

    @Test fun finishingCompositionUsesTheExistingKeyFallbackWhenNoTextCallbackExists() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val keys = mutableListOf<KeyEvent>()
        val host = KeyboardHost(activity) { keys.add(it); true }
        activity.setContentView(FrameLayout(activity).apply { addView(host) })
        host.show()
        val connection = host.onCreateInputConnection(EditorInfo())!!
        connection.setComposingText("a", 1)
        assertTrue(keys.isEmpty())
        assertTrue(connection.finishComposingText())
        assertTrue(connection.finishComposingText())
        assertEquals(listOf(KeyEvent.KEYCODE_A), keys.filter { it.action == KeyEvent.ACTION_DOWN }.map { it.keyCode })
        activity.finish()
    }
}
