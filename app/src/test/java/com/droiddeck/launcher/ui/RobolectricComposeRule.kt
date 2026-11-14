package com.droiddeck.launcher.ui

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.snapshots.Snapshot
import com.droiddeck.launcher.session.SessionPrefs
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

fun createRobolectricComposeRule() = createComposeRule().also {
    RingVeil.reset()
    val context = RuntimeEnvironment.getApplication()
    SessionPrefs.setAnimationsEnabled(context, false)
    Motion.refresh(context)
    shadowOf(context.packageManager).addActivityIfNotPresent(
        ComponentName(context, ComponentActivity::class.java),
    )
    Snapshot.sendApplyNotifications()
}
