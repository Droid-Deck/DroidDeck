package com.droiddeck.launcher.update

import android.content.BroadcastReceiver
import org.robolectric.RuntimeEnvironment

internal fun resetUpdateTask() {
    val context = RuntimeEnvironment.getApplication()
    val type = AppUpdateState::class.java
    fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
    runCatching { context.unregisterReceiver(field("receiver").get(null) as BroadcastReceiver) }
    (field("listeners").get(null) as MutableSet<*>).clear()
    field("initialized").setBoolean(null, false)
    AppUpdateState.fail(context, null)
}
