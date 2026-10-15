package com.droiddeck.launcher.session

import android.app.Activity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28, 29, 33], manifest = Config.NONE)
class ActivityDisplayCompatibilityTest {
    @Test fun visibleActivityUsesItsDisplayIdAcrossSupportedAndroidVersions() {
        val wasRunning = SessionState.running
        val previousDisplayId = SessionState.activityDisplayId
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        try {
            SessionState.running = false
            SessionService.setActivityVisible(activity, true)

            assertEquals(activity.windowManager.defaultDisplay.displayId, SessionState.activityDisplayId)
        } finally {
            SessionState.running = wasRunning
            SessionState.activityDisplayId = previousDisplayId
            activity.finish()
        }
    }
}
