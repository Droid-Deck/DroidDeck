package com.droiddeck.launcher.ui

import android.app.Application
import android.content.Intent
import android.hardware.display.DisplayManager
import android.view.Display
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [28, 33])
@LooperMode(LooperMode.Mode.PAUSED)
class SecondScreenActivityTest {
    @Before fun setUp() {
        SessionState.running = true
        SessionState.stopRequested = false
    }

    @After fun tearDown() {
        SessionState.running = false
        SessionState.stopRequested = false
    }

    @Test fun removingControlsTaskDoesNotStopTheRunningSession() {
        val service = Robolectric.buildService(SessionService::class.java).get()
        service.onTaskRemoved(Intent(service, SecondScreenActivity::class.java))
        assertTrue(SessionState.running)
        assertFalse(SessionState.stopRequested)
    }

    @Test fun activityRestoredWithoutItsSessionOwnerFinishes() {
        val activity = Robolectric.buildActivity(SecondScreenActivity::class.java).create().get()
        assertTrue(activity.isFinishing)
        assertTrue(SessionState.running)
    }

    @Test fun closingBeforeTheSecondaryActivityStartsRejectsTheLateLaunch() {
        val session = Robolectric.buildActivity(SessionActivity::class.java).get()
        val display = session.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val controls = SecondScreenWindow(session, display, { _, _, _, _ -> }, { _, _ -> }, {}, {}, {}, {})
        controls.show()
        val intent = shadowOf(session).nextStartedActivity
        controls.closeControls()
        val activity = Robolectric.buildActivity(SecondScreenActivity::class.java, intent).create().get()
        assertTrue(activity.isFinishing)
        assertFalse(controls.isShowing)
        assertTrue(SessionState.running)
    }
}
