package com.droiddeck.launcher.ui

import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import androidx.activity.ComponentActivity
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import kotlin.coroutines.ContinuationInterceptor

fun createRobolectricComposeRule(): ComposeContentTestRule = DrainingComposeRule(createComposeRule()).also {
    RingVeil.reset()
    val context = RuntimeEnvironment.getApplication()
    shadowOf(context.packageManager).addActivityIfNotPresent(
        ComponentName(context, ComponentActivity::class.java),
    )
    resumeDroppedUiDispatch()
    Snapshot.sendApplyNotifications()
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

/**
 * Runs what a test left on the main looper before Robolectric clears it. A Compose state write queues Compose's
 * snapshot dispatch there; once Robolectric drops it, the sandbox's shared Compose dispatcher never runs again and
 * every later Compose test in that sandbox times out as not idle. Use it in any test that changes Compose state
 * outside composition without a Compose rule.
 */
class MainLooperDrain : TestRule {
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            val failure = runCatching { base.evaluate() }.exceptionOrNull()
            val drain = runCatching { shadowOf(Looper.getMainLooper()).idle() }.exceptionOrNull()
            (failure ?: drain)?.let { throw it }
        }
    }
}

@OptIn(ExperimentalTestApi::class)
private class DrainingComposeRule(private val rule: ComposeContentTestRule) : ComposeContentTestRule by rule {
    override fun apply(base: Statement, description: Description): Statement =
        MainLooperDrain().apply(rule.apply(base, description), description)
}
