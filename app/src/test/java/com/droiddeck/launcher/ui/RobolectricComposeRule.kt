package com.droiddeck.launcher.ui

import android.content.ComponentName
import android.os.Handler
import android.view.Choreographer
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.snapshots.Snapshot
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import kotlin.coroutines.ContinuationInterceptor

fun createRobolectricComposeRule() = createComposeRule().also {
    RingVeil.reset()
    val context = RuntimeEnvironment.getApplication()
    shadowOf(context.packageManager).addActivityIfNotPresent(
        ComponentName(context, ComponentActivity::class.java),
    )
    resumeDroppedUiDispatch()
    Snapshot.sendApplyNotifications()
}

fun ComposeContentTestRule.renderPendingChanges() {
    repeat(2) {
        runOnIdle { Snapshot.sendApplyNotifications() }
        mainClock.advanceTimeByFrame()
        waitForIdle()
    }
}

internal class UiDispatch {
    private val type = AndroidUiDispatcher::class.java
    private val dispatcher = AndroidUiDispatcher.Main[ContinuationInterceptor] as AndroidUiDispatcher
    private fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
    val lock: Any = field("lock").get(dispatcher)!!
    val handler = field("handler").get(dispatcher) as Handler
    val choreographer = field("choreographer").get(dispatcher) as Choreographer
    val callback: Any = field("dispatchCallback").get(dispatcher)!!
    private val trampolineFlag = field("scheduledTrampolineDispatch")
    private val frameFlag = field("scheduledFrameDispatch")

    fun trampolineDropped() = trampolineFlag.getBoolean(dispatcher) && !handler.hasMessages(0)

    fun frameDropped() = frameFlag.getBoolean(dispatcher) && !frameQueued()

    private fun frameQueued(): Boolean {
        val queues = Choreographer::class.java.getDeclaredField("mCallbackQueues").apply { isAccessible = true }.get(choreographer) as Array<*>
        return queues.any { queue ->
            var record = queue?.let { q -> q.javaClass.getDeclaredField("mHead").apply { isAccessible = true }.get(q) }
            var found = false
            while (record != null && !found) {
                val recordType = record.javaClass
                found = recordType.getDeclaredField("action").apply { isAccessible = true }.get(record) === callback
                record = recordType.getDeclaredField("next").apply { isAccessible = true }.get(record)
            }
            found
        }
    }
}

internal fun uiDispatchDropped(): Boolean = UiDispatch().run { synchronized(lock) { trampolineDropped() || frameDropped() } }

internal fun resumeDroppedUiDispatch() {
    val ui = UiDispatch()
    synchronized(ui.lock) {
        if (ui.trampolineDropped()) ui.handler.post(ui.callback as Runnable)
        if (ui.frameDropped()) ui.choreographer.postFrameCallback(ui.callback as Choreographer.FrameCallback)
    }
}
