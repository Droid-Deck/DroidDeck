package com.droiddeck.launcher

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.droiddeck.launcher.core.DroidDeckTestRunner
import com.google.android.play.core.appupdate.testing.FakeAppUpdateManager
import com.google.android.play.core.install.model.InstallErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

@RunWith(DroidDeckTestRunner::class)
class PlayUpdatesTest {
    @Test fun availablePlayUpdateSetsTheExistingUpdateIndicatorWithoutInstalling() {
        if (!BuildConfig.PLAY_DISTRIBUTION) return
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val activity = controller.get()
            val manager = FakeAppUpdateManager(activity)
            manager.setUpdateAvailable(Int.MAX_VALUE)
            val menu = UpdatesMenu(activity, Handler(Looper.getMainLooper())) { manager }
            menu.check(true)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(menu.hasUpdate)
            assertTrue(menu.state().hasUpdate)
            assertFalse(menu.checking)
            assertNull(menu.stage)
            assertNull(shadowOf(activity).nextStartedActivity)
            assertFalse(manager.isImmediateFlowVisible)
            assertFalse(manager.isConfirmationDialogVisible)
            menu.actions().onOpenPlay()
            val intent = shadowOf(activity).nextStartedActivity
            assertEquals("market://details?id=${activity.packageName}", intent.dataString)
            assertEquals("com.android.vending", intent.`package`)
        }
    }

    @Test fun playCanReportNoUpdateWithoutConsultingAnExternalCatalog() {
        if (!BuildConfig.PLAY_DISTRIBUTION) return
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val manager = FakeAppUpdateManager(controller.get())
            manager.setUpdateNotAvailable()
            val menu = UpdatesMenu(controller.get(), Handler(Looper.getMainLooper())) { manager }
            menu.check(true)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(false, menu.playUpdateAvailable)
            assertFalse(menu.hasUpdate)
            assertNull(menu.catalog)
            assertTrue(menu.state().playCheckedAt > 0)
        }
    }

    @Test fun failedPlayCheckDoesNotClaimTheAppIsCurrent() {
        if (!BuildConfig.PLAY_DISTRIBUTION) return
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val manager = FakeAppUpdateManager(controller.get())
            manager.setInstallErrorCode(InstallErrorCode.ERROR_API_NOT_AVAILABLE)
            val menu = UpdatesMenu(controller.get(), Handler(Looper.getMainLooper())) { manager }
            menu.check(true)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(menu.playUpdateAvailable)
            assertNotNull(menu.error)
            assertFalse(menu.checking)
        }
    }

    @Test fun playCannotOpenTheUnknownPackageInstallationSettings() {
        if (!BuildConfig.PLAY_DISTRIBUTION) return
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val activity = controller.get()
            val menu = UpdatesMenu(activity, Handler(Looper.getMainLooper()))
            menu.actions().onAllowInstalls()
            assertFalse(menu.askPermission)
            assertNull(shadowOf(activity).nextStartedActivity)
        }
    }
}
