package com.droiddeck.launcher.ui

import android.app.Activity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LauncherFullscreenInsetsTest {
    @Test fun nonFullscreenDoesNotApplyLauncherCornerInsets() {
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        var observed: FullscreenSafeInsets? = null
        val controller = FullscreenSafeInsetsController(
            activity.window.decorView,
            { false },
            { observed = it },
            preferFullWidthInPortrait = true,
        )
        assertEquals(FullscreenSafeInsets(), observed)
        controller.close()
    }
}
