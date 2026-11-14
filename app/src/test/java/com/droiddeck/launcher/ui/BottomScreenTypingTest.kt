package com.droiddeck.launcher.ui

import android.view.KeyEvent
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.DeleteAllCommand
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.text.input.SetSelectionCommand
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BottomScreenTypingTest {
    @Test fun editsReplaceTheWholeFieldAndKeepTheCursor() {
        val edits = TypingRequest.editsFor(TextFieldValue("fall", TextRange(2, 4)))
        assertEquals(3, edits.size)
        assertTrue(edits[0] is DeleteAllCommand)
        assertEquals("fall", (edits[1] as CommitTextCommand).text)
        val selection = edits[2] as SetSelectionCommand
        assertEquals(2, selection.start)
        assertEquals(4, selection.end)
    }

    @Test fun requestForwardsTextActionAndClose() {
        val sent = mutableListOf<String>()
        val actions = mutableListOf<ImeAction>()
        var closed = 0
        val request = TypingRequest(
            TextFieldValue("a"), ImeOptions(imeAction = ImeAction.Search),
            { sent += it.text }, { actions += it }, { closed++ },
        )
        request.send(TextFieldValue("ab"))
        request.action()
        request.close()
        assertEquals(listOf("ab"), sent)
        assertEquals("ab", request.value.text)
        assertEquals(listOf(ImeAction.Search), actions)
        assertEquals(1, closed)
    }

    @Test fun panelStopsOnlyItsOwnRequest() {
        val first = TypingRequest(TextFieldValue(""), ImeOptions.Default, {}, {})
        val second = TypingRequest(TextFieldValue(""), ImeOptions.Default, {}, {})
        DualScreenPanel.startTyping(second)
        DualScreenPanel.stopTyping(first)
        assertEquals(second, DualScreenPanel.typing)
        DualScreenPanel.stopTyping(second)
        assertNull(DualScreenPanel.typing)
    }

    @Test fun triggersStepThroughSections() {
        assertEquals(-1, dualPageStep(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_L2)))
        assertEquals(1, dualPageStep(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R2)))
        assertNull(dualPageStep(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_L1)))
    }
}
