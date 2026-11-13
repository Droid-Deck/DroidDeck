package com.droiddeck.launcher.ui

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.snapshots.Snapshot
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

fun createRobolectricComposeRule() = createComposeRule().also {
    RingVeil.reset()
    val context = RuntimeEnvironment.getApplication()
    shadowOf(context.packageManager).addActivityIfNotPresent(
        ComponentName(context, ComponentActivity::class.java),
    )
    Snapshot.sendApplyNotifications()
}

fun ComposeContentTestRule.renderPendingChanges() {
    // Snapshot notifications use Android's main queue, separately from the Compose clock.
    repeat(2) {
        runOnIdle { Snapshot.sendApplyNotifications() }
        mainClock.advanceTimeByFrame()
        waitForIdle()
    }
}
