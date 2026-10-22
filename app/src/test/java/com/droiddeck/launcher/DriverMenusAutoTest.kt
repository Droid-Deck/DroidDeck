package com.droiddeck.launcher

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.droiddeck.launcher.session.QuestExperimental
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.gpu.TurnipReleases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DriverMenusAutoTest {
    @Before fun reset() {
        RuntimeEnvironment.getApplication().getSharedPreferences("session", Context.MODE_PRIVATE)
            .edit().clear().commit()
        ShadowBuild.setModel("Quest 3")
        ShadowBuild.setDevice("eureka")
    }

    @Test fun selectingAutoUsesTheQuestPairWithoutStartingADownload() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        SessionPrefs.setGpuDriverMode(activity, SessionPrefs.GPU_DRIVERS_MANUAL)
        SessionPrefs.setAndroidDriver(activity, "chosen-display")
        SessionPrefs.setLinuxDriver(activity, "chosen-linux")
        val menus = DriverMenus(activity, Handler(Looper.getMainLooper()))
        menus.setMode(true)
        assertEquals(QuestExperimental.DRIVER, SessionPrefs.androidDriver(activity))
        assertEquals("", SessionPrefs.linuxDriver(activity))
        assertEquals(activity.getString(R.string.drivers_using_quest), menus.autoStatus)
        assertFalse(menus.releaseChecking)
        assertNull(menus.pairBusy)
        menus.ensureAuto(force = true)
        assertFalse(menus.releaseChecking)
    }

    @Test fun openingManualDriverSettingsRetainsTheSelectedPair() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        SessionPrefs.setGpuDriverMode(activity, SessionPrefs.GPU_DRIVERS_MANUAL)
        SessionPrefs.setAndroidDriver(activity, "chosen-display")
        SessionPrefs.setLinuxDriver(activity, "chosen-linux")
        DriverMenus(activity, Handler(Looper.getMainLooper())).ensureAuto(force = true)
        assertEquals("chosen-display", SessionPrefs.androidDriver(activity))
        assertEquals("chosen-linux", SessionPrefs.linuxDriver(activity))
    }

    @Test fun sameFilenameFromTwoSourcesResolvesBySourceQualifiedIdentity() {
        val first = TurnipReleases.Asset(
            "first", "v1", "driver.zip", "https://github.com/first/repo/releases/download/v1/driver.zip", 1, false, "first",
            "a".repeat(64), sourceId = "source-a", assetId = 11,
        )
        val second = TurnipReleases.Asset(
            "second", "v1", "driver.zip", "https://github.com/second/repo/releases/download/v1/driver.zip", 1, false, "second",
            "b".repeat(64), sourceId = "source-b", assetId = 22,
        )
        assertEquals(second, releaseAssetById(listOf(first, second), second.id))
    }

    @Test fun sourceChangeAfterAdoptionRollsBackWithoutRecording() {
        var rolledBack = false
        var recorded = false
        assertTrue(runCatching {
            finishRemoteDriverInstall(false, { rolledBack = true }, { recorded = true })
        }.isFailure)
        assertTrue(rolledBack)
        assertFalse(recorded)
    }
}
