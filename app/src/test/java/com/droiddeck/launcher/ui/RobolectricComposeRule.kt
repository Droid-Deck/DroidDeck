package com.droiddeck.launcher.ui

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.snapshots.Snapshot
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

fun createRobolectricComposeRule() = createComposeRule().also {
    val context = RuntimeEnvironment.getApplication()
    // Register only in Robolectric, without adding a host activity to either APK variant.
    shadowOf(context.packageManager).addActivityIfNotPresent(
        ComponentName(context, ComponentActivity::class.java),
    )
    // Pure state tests can leave unapplied snapshots before a Compose host starts its snapshot
    // manager. Apply them before the rule's initial idle check, which runs before the test body.
    Snapshot.sendApplyNotifications()
}
