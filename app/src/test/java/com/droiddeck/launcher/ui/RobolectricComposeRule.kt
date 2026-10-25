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

fun createRobolectricComposeRule(): ComposeContentTestRule {
    val rule = createComposeRule()
    val context = RuntimeEnvironment.getApplication()
    shadowOf(context.packageManager).addActivityIfNotPresent(
        ComponentName(context, ComponentActivity::class.java),
    )
    Snapshot.sendApplyNotifications()
    return object : ComposeContentTestRule by rule {
        override fun apply(base: Statement, description: Description): Statement {
            val statement = rule.apply(base, description)
            return object : Statement() {
                override fun evaluate() {
                    // Each Robolectric test needs snapshot notifications on its own main looper.
                    val handler = Handler(Looper.getMainLooper())
                    val applyChanges = Runnable { Snapshot.sendApplyNotifications() }
                    val observer = Snapshot.registerGlobalWriteObserver { handler.post(applyChanges) }
                    try {
                        statement.evaluate()
                    } finally {
                        observer.dispose()
                        handler.removeCallbacks(applyChanges)
                        Snapshot.sendApplyNotifications()
                    }
                }
            }
        }
    }
}
