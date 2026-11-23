package com.droiddeck.launcher.ui

import android.os.Looper
import android.view.Choreographer
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.AndroidUiDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RobolectricComposeRuleTest {
    private fun dropPendingUiDispatch() {
        val ui = UiDispatch()
        ui.handler.removeCallbacksAndMessages(null)
        dropPendingFrame()
    }

    private fun dropPendingFrame() {
        val ui = UiDispatch()
        ui.choreographer.removeFrameCallback(ui.callback as Choreographer.FrameCallback)
    }

    private fun awaitFrameAfterTrampoline(ran: MutableIntState) {
        CoroutineScope(AndroidUiDispatcher.Main).launch { withFrameNanos { ran.intValue++ } }
        shadowOf(Looper.getMainLooper()).runOneTask()
    }

    @Test
    fun uiWorkDroppedByAnEarlierTestsLooperResetRunsAgainForTheNextComposeTest() {
        val ran = mutableIntStateOf(0)
        CoroutineScope(AndroidUiDispatcher.Main).launch { ran.intValue++ }
        dropPendingUiDispatch()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, ran.intValue)
        assertTrue(uiDispatchDropped())
        createRobolectricComposeRule()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, ran.intValue)
        assertFalse(uiDispatchDropped())
    }

    @Test
    fun aDispatchStillQueuedIsLeftAlone() {
        val ran = mutableIntStateOf(0)
        CoroutineScope(AndroidUiDispatcher.Main).launch { ran.intValue++ }
        assertFalse(uiDispatchDropped())
        resumeDroppedUiDispatch()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, ran.intValue)
        assertFalse(uiDispatchDropped())
    }

    @Test
    fun aFrameDroppedAfterTheTrampolineDrainedRunsAgainForTheNextComposeTest() {
        val ran = mutableIntStateOf(0)
        awaitFrameAfterTrampoline(ran)
        dropPendingFrame()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(0, ran.intValue)
        assertTrue(uiDispatchDropped())
        createRobolectricComposeRule()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(1, ran.intValue)
        assertFalse(uiDispatchDropped())
    }

    @Test
    fun aFrameStillQueuedIsLeftAlone() {
        val ran = mutableIntStateOf(0)
        awaitFrameAfterTrampoline(ran)
        assertEquals(0, ran.intValue)
        assertFalse(uiDispatchDropped())
        resumeDroppedUiDispatch()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(1, ran.intValue)
        assertFalse(uiDispatchDropped())
    }
}
