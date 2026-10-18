package com.droiddeck.launcher.ui

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.snapshots.Snapshot
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

fun createRobolectricComposeRule() = createComposeRule().also {
    val context = RuntimeEnvironment.getApplication()
    shadowOf(context.packageManager).addActivityIfNotPresent(
        ComponentName(context, ComponentActivity::class.java),
    )
    Snapshot.sendApplyNotifications()
}
