package com.droiddeck.launcher.ui

import android.content.ComponentName
import android.os.Handler
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.snapshots.Snapshot
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext

fun createRobolectricComposeRule() = createComposeRule().also {
    RingVeil.reset()
    val context = RuntimeEnvironment.getApplication()
    shadowOf(context.packageManager).addActivityIfNotPresent(
        ComponentName(context, ComponentActivity::class.java),
    )
    resumeDroppedUiDispatch()
    Snapshot.sendApplyNotifications()
}

internal fun uiDispatchDropped(): Boolean {
    val dispatcher = uiDispatcher()
    val handler = AndroidUiDispatcher::class.java.getDeclaredField("handler").apply { isAccessible = true }.get(dispatcher) as Handler
    return uiDispatchFlags().first().getBoolean(dispatcher) && !handler.hasMessages(0)
}

internal fun resumeDroppedUiDispatch() {
    val dispatcher = uiDispatcher()
    val lock = AndroidUiDispatcher::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(dispatcher)
    val dropped = synchronized(lock!!) {
        uiDispatchDropped().also { if (it) uiDispatchFlags().forEach { flag -> flag.setBoolean(dispatcher, false) } }
    }
    if (dropped) dispatcher.dispatch(EmptyCoroutineContext, Runnable {})
}

private fun uiDispatcher() = AndroidUiDispatcher.Main[ContinuationInterceptor] as AndroidUiDispatcher

private fun uiDispatchFlags() = listOf("scheduledTrampolineDispatch", "scheduledFrameDispatch").map {
    AndroidUiDispatcher::class.java.getDeclaredField(it).apply { isAccessible = true }
}
