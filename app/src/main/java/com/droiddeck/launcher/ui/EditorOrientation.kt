package com.droiddeck.launcher.ui

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.droiddeck.launcher.SessionActivity

private val landscapes = setOf(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE)

/** [requested], or sensor landscape when it allows portrait. */
internal fun landscapeOrientation(requested: Int) = if (requested in landscapes) requested else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

/**
 * Keeps [activity] landscape until the returned release runs. On-screen layouts are stored in landscape, so the editor on the
 * activity's own screen only shows them true to shape there. The launcher re-requests its own orientation on resume, so the hold
 * is applied again after it.
 */
internal fun holdLandscape(activity: ComponentActivity): () -> Unit {
    if (activity is SessionActivity) {
        activity.editorLandscape = true
        return { activity.editorLandscape = false }
    }
    val before = activity.requestedOrientation
    val hold = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) activity.requestedOrientation = landscapeOrientation(activity.requestedOrientation)
    }
    activity.requestedOrientation = landscapeOrientation(before)
    activity.lifecycle.addObserver(hold)
    return {
        activity.lifecycle.removeObserver(hold)
        activity.requestedOrientation = before
    }
}
