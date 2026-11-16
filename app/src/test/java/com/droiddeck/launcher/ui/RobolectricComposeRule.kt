package com.droiddeck.launcher.ui

import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.atomic.AtomicBoolean

fun createRobolectricComposeRule(): ComposeContentTestRule = AppliedWritesComposeRule(createComposeRule()).also {
    RingVeil.reset()
    val context = RuntimeEnvironment.getApplication()
    shadowOf(context.packageManager).addActivityIfNotPresent(
        ComponentName(context, ComponentActivity::class.java),
    )
    Snapshot.sendApplyNotifications()
}

// Compose applies writes made outside composition from one process-wide main-thread task. When an earlier test
// ends with that task queued, Robolectric drops it and Compose never schedules another, so later tests never idle.
private class AppliedWritesComposeRule(private val rule: ComposeContentTestRule) : ComposeContentTestRule by rule {
    override fun apply(base: Statement, description: Description): Statement = rule.apply(object : Statement() {
        override fun evaluate() {
            val main = Handler(Looper.getMainLooper())
            val queued = AtomicBoolean(false)
            val observer = Snapshot.registerGlobalWriteObserver {
                if (queued.compareAndSet(false, true)) main.post {
                    queued.set(false)
                    Snapshot.sendApplyNotifications()
                }
            }
            try {
                base.evaluate()
            } finally {
                observer.dispose()
            }
        }
    }, description)
}
