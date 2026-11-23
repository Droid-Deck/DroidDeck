package com.droiddeck.launcher.ui

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import androidx.compose.runtime.mutableIntStateOf
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
import kotlin.coroutines.ContinuationInterceptor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RobolectricComposeRuleTest {
    private fun dropPendingUiDispatch() {
        val dispatcher = AndroidUiDispatcher.Main[ContinuationInterceptor] as AndroidUiDispatcher
        val type = AndroidUiDispatcher::class.java
        val callback = type.getDeclaredField("dispatchCallback").apply { isAccessible = true }.get(dispatcher)
        (type.getDeclaredField("handler").apply { isAccessible = true }.get(dispatcher) as Handler).removeCallbacksAndMessages(null)
        (type.getDeclaredField("choreographer").apply { isAccessible = true }.get(dispatcher) as Choreographer)
            .removeFrameCallback(callback as Choreographer.FrameCallback)
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
}
