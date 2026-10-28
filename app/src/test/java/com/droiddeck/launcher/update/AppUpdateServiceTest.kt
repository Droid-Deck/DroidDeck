package com.droiddeck.launcher.update

import android.content.Intent
import android.os.Looper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppUpdateServiceTest {
    @Before fun resetTask() = resetUpdateTask()

    @Test fun failedDownloadReleasesWakeLock() {
        val controller = Robolectric.buildService(AppUpdateService::class.java).create()
        val service = controller.get()
        AppUpdateState.begin(AppUpdates.Apk("update.apk", "invalid://update", 1, "0".repeat(64), service.packageName, 1, "0".repeat(64)))
        service.onStartCommand(Intent(), 0, 1)
        val wake = ShadowPowerManager.getLatestWakeLock()
        assertNotNull(wake)
        val deadline = System.nanoTime() + 5_000_000_000L
        while (AppUpdateState.snapshot.phase != AppUpdateState.Phase.FAILED && System.nanoTime() < deadline) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(AppUpdateState.Phase.FAILED, AppUpdateState.snapshot.phase)
        assertFalse(wake.isHeld)
        controller.destroy()
        assertFalse(wake.isHeld)
    }

    @Test fun destructionReleasesLocksAndCancelsRenewal() {
        val controller = Robolectric.buildService(AppUpdateService::class.java).create()
        val service = controller.get()
        val acquire = AppUpdateService::class.java.getDeclaredMethod("acquireLocks").apply { isAccessible = true }
        acquire.invoke(service)
        val wake = ShadowPowerManager.getLatestWakeLock()
        assertTrue(wake.isHeld)
        controller.destroy()
        assertFalse(wake.isHeld)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(6))
        assertFalse(wake.isHeld)
    }
}
