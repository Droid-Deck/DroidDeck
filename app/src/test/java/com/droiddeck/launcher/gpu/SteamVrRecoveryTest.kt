package com.droiddeck.launcher.gpu

import android.app.Activity
import android.content.Context
import android.os.Looper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class SteamVrRecoveryTest {
    private lateinit var activity: Activity

    @Before fun setup() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.getSharedPreferences(SteamVrRecovery.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun countdownLaunchesPrivateRestartActivityAndPersistsLoopGuard() {
        val recovery = SteamVrRecovery(activity) { true }
        recovery.detected()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(7))
        assertNull(shadowOf(activity).nextStartedActivity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(SteamVrRestartActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
        assertTrue(activity.getSharedPreferences(SteamVrRecovery.PREFS, Context.MODE_PRIVATE)
            .getLong(SteamVrRecovery.LAST_ATTEMPT, 0) > 0)
        recovery.close()
    }

    @Test fun cancellationPreventsRestartAndRepeatedPrompts() {
        val recovery = SteamVrRecovery(activity) { true }
        recovery.detected()
        ShadowAlertDialog.getLatestAlertDialog().getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        recovery.detected()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(ShadowAlertDialog.getLatestAlertDialog().isShowing)
    }

    @Test fun recentRecoveryDoesNotAutomaticallyRestartAgain() {
        activity.getSharedPreferences(SteamVrRecovery.PREFS, Context.MODE_PRIVATE).edit()
            .putLong(SteamVrRecovery.LAST_ATTEMPT, System.currentTimeMillis()).commit()
        val recovery = SteamVrRecovery(activity) { true }
        recovery.detected()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing)
        val outside = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_OUTSIDE, -10f, -10f, 0)
        try {
            assertFalse(ShadowAlertDialog.getLatestAlertDialog().onTouchEvent(outside))
            assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing)
        } finally { outside.recycle() }
        recovery.close()
    }

    @Test fun backgroundingCancelsTheCountdownUntilTheSessionReturns() {
        var foreground = true
        val recovery = SteamVrRecovery(activity) { foreground }
        recovery.detected()
        foreground = false
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(ShadowAlertDialog.getLatestAlertDialog().isShowing)
        recovery.detected()
        assertFalse(ShadowAlertDialog.getLatestAlertDialog().isShowing)
        foreground = true
        recovery.detected()
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))
        assertEquals(SteamVrRestartActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
        recovery.close()
    }
}
