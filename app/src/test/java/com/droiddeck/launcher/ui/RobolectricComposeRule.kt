package com.droiddeck.launcher.ui

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createComposeRule
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

fun createRobolectricComposeRule() = createComposeRule().also {
    val context = RuntimeEnvironment.getApplication()
    // Register only in Robolectric, without adding a host activity to either APK variant.
    shadowOf(context.packageManager).addActivityIfNotPresent(
        ComponentName(context, ComponentActivity::class.java),
    )
}
