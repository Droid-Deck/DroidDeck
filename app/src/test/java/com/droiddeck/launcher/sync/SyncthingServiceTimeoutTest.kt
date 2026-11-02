package com.droiddeck.launcher.sync

import android.app.Activity
import android.content.pm.ServiceInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import com.droiddeck.launcher.core.DroidDeckTestRunner as RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35, 37], manifest = Config.NONE)
class SyncthingServiceTimeoutTest {
    @Test fun timeoutStopsRestartLoopAndUserReturnResumesWithoutChangingPreferences() {
        val app = RuntimeEnvironment.getApplication()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val controller = Robolectric.buildService(SyncthingService::class.java).create()
        try {
            SyncPrefs.setEnabled(app, false)
            Syncthing.init(app)
            SyncPrefs.setRunMode(app, SyncPrefs.RUN_BACKGROUND)
            SyncPrefs.setEnabled(app, true)
            Syncthing.evaluate()
            val shadow = shadowOf(app)
            assertEquals(SyncthingService::class.java.name, shadow.nextStartedService?.component?.className)
            controller.get().onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            Syncthing.evaluate()
            assertNull(shadow.nextStartedService)
            assertTrue(SyncPrefs.enabled(app))
            assertEquals(SyncPrefs.RUN_BACKGROUND, SyncPrefs.runMode(app))
            val activity = Robolectric.buildActivity(Activity::class.java).setup()
            assertEquals(SyncthingService::class.java.name, shadow.nextStartedService?.component?.className)
            activity.pause().stop().destroy()
        } finally {
            SyncPrefs.setEnabled(app, false)
            Syncthing.evaluate()
            controller.destroy()
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
