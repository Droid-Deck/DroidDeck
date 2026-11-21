package com.droiddeck.launcher.ui

import android.content.ComponentName
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

fun createRobolectricComposeRule(): ComposeContentTestRule = DrainingComposeRule(createComposeRule()).also {
    RingVeil.reset()
    val context = RuntimeEnvironment.getApplication()
    shadowOf(context.packageManager).addActivityIfNotPresent(
        ComponentName(context, ComponentActivity::class.java),
    )
    Snapshot.sendApplyNotifications()
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
