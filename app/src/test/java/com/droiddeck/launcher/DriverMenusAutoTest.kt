package com.droiddeck.launcher

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.droiddeck.launcher.session.QuestExperimental
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.gpu.TpUbwcHint
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
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DriverMenusAutoTest {
    @Before fun reset() {
        ReflectionHelpers.setStaticField(TpUbwcHint::class.java, "resolved", false)
        ReflectionHelpers.setStaticField(TpUbwcHint::class.java, "cached", null)
        ReflectionHelpers.setStaticField(TpUbwcHint::class.java, "activeMode", null)
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

    @Test fun driverMenusCanBeConstructedBeforeActivityContextIsAttached() {
        val menus = DriverMenus(Activity(), Handler(Looper.getMainLooper()))
        assertEquals(SessionPrefs.TP_UBWC_AUTO, menus.tpUbwcMode)
    }

    @Test fun refreshingDriversLoadsTheSavedUbwcModeAfterContextAttachment() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        SessionPrefs.setTpUbwcMode(activity, SessionPrefs.TP_UBWC_ON)
        val menus = DriverMenus(activity, Handler(Looper.getMainLooper()))
        menus.refreshDrivers()
        assertEquals(SessionPrefs.TP_UBWC_ON, menus.tpUbwcMode)
        assertFalse(menus.state().tpUbwcRestartRequired)
    }

    @Test fun changingUbwcBeforeTheFirstSessionDoesNotRequireRestart() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val menus = DriverMenus(activity, Handler(Looper.getMainLooper()))
        menus.refreshDrivers()
        SessionPrefs.setTpUbwcMode(activity, SessionPrefs.TP_UBWC_ON)
        menus.refreshDrivers()
        assertFalse(menus.state().tpUbwcRestartRequired)
    }

    @Test fun recreatingTheMenuKeepsTheActiveHintRestartWarning() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        TpUbwcHint.forThisDevice(activity)
        SessionPrefs.setTpUbwcMode(activity, SessionPrefs.TP_UBWC_ON)
        val menus = DriverMenus(activity, Handler(Looper.getMainLooper()))
        menus.refreshDrivers()
        assertTrue(menus.state().tpUbwcRestartRequired)
    }
}
