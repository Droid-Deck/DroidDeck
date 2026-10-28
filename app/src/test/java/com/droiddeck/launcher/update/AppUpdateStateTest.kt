package com.droiddeck.launcher.update

import android.content.Context
import android.os.Looper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppUpdateStateTest {
    @Before fun resetTask() = resetUpdateTask()

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun apk(name: String = "update.apk") = AppUpdates.Apk(
        name, "https://example.com/$name", 3, "0".repeat(64), context.packageName, 1, "0".repeat(64),
    )

    @Test fun duplicateStartKeepsOriginalTargetAndProgress() {
        assertTrue(AppUpdateState.begin(apk()))
        AppUpdateState.progress(42)
        assertFalse(AppUpdateState.begin(apk("other.apk")))
        assertEquals("update.apk", AppUpdateState.snapshot.apk?.name)
        assertEquals(42, AppUpdateState.snapshot.percent)
    }

    @Test fun recreatedObserverImmediatelyReceivesCurrentState() {
        val seen = mutableListOf<AppUpdateState.Snapshot>()
        val observer: (AppUpdateState.Snapshot) -> Unit = { seen.add(it) }
        AppUpdateState.begin(apk())
        AppUpdateState.subscribe(observer)
        AppUpdateState.unsubscribe(observer)
        AppUpdateState.progress(61)
        assertEquals(0, seen.last().percent)
        AppUpdateState.subscribe(observer)
        assertEquals(61, seen.last().percent)
        AppUpdateState.fail(context, "offline")
        AppUpdateState.unsubscribe(observer)
        AppUpdateState.subscribe(observer)
        assertEquals("offline", seen.last().error)
        AppUpdateState.unsubscribe(observer)
    }

    @Test fun completionWaitsForForegroundConsumerAndPersistsReadyFile() {
        AppUpdateState.begin(apk())
        val file = File(context.cacheDir, "update.apk")
        AppUpdateState.ready(context, file)
        assertEquals(AppUpdateState.Phase.READY, AppUpdateState.snapshot.phase)
        assertTrue(AppUpdateState.snapshot.automatic)
        assertTrue(context.getSharedPreferences("app_update_task", 0).contains("ready"))
        val seen = mutableListOf<AppUpdateState.Snapshot>()
        val observer: (AppUpdateState.Snapshot) -> Unit = { seen.add(it) }
        AppUpdateState.subscribe(observer)
        assertEquals(file, seen.single().file)
        AppUpdateState.unsubscribe(observer)
    }

    @Test fun missingReadyFileFailsBeforeCreatingInstallerSession() {
        AppUpdateState.begin(apk())
        AppUpdateState.ready(context, File(context.cacheDir, "missing.apk"))
        AppUpdateState.install(context)
        AppUpdateState.install(context)
        awaitPhase(AppUpdateState.Phase.FAILED)
        assertTrue(context.packageManager.packageInstaller.mySessions.isEmpty())
        assertFalse(context.getSharedPreferences("app_update_task", 0).contains("ready"))
    }

    @Test fun installationCanOnlyBeClaimedOnce() {
        AppUpdateState.begin(apk())
        AppUpdateState.ready(context, File(context.cacheDir, "update.apk"))
        assertNotNull(AppUpdateState.claimInstallation())
        assertNull(AppUpdateState.claimInstallation())
        assertEquals(AppUpdateState.Phase.INSTALLING, AppUpdateState.snapshot.phase)
    }

    @Test fun installationStartedByObserverDoesNotPublishStaleReadyState() {
        AppUpdateState.begin(apk())
        val first: (AppUpdateState.Snapshot) -> Unit = {
            if (it.phase == AppUpdateState.Phase.READY) AppUpdateState.claimInstallation()
        }
        val seen = mutableListOf<AppUpdateState.Phase>()
        val second: (AppUpdateState.Snapshot) -> Unit = { seen.add(it.phase) }
        AppUpdateState.subscribe(first)
        AppUpdateState.subscribe(second)
        AppUpdateState.ready(context, File(context.cacheDir, "update.apk"))
        assertEquals(AppUpdateState.Phase.INSTALLING, seen.last())
        AppUpdateState.unsubscribe(first)
        AppUpdateState.unsubscribe(second)
    }

    @Test fun restartWithMissingCompletedFileDoesNotOfferInstallation() {
        AppUpdateState.begin(apk())
        AppUpdateState.ready(context, File(context.cacheDir, "missing.apk"))
        AppUpdateState.initialize(context)
        awaitPhase(AppUpdateState.Phase.FAILED)
        assertFalse(AppUpdateState.snapshot.automatic)
        assertTrue(context.packageManager.packageInstaller.mySessions.isEmpty())
    }

    private fun awaitPhase(phase: AppUpdateState.Phase) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (AppUpdateState.snapshot.phase != phase && System.nanoTime() < deadline) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(phase, AppUpdateState.snapshot.phase)
    }
}
