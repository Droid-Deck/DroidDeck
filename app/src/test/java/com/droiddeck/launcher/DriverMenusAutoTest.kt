package com.droiddeck.launcher

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.droiddeck.launcher.gpu.DriverBundle
import com.droiddeck.launcher.gpu.DriverPairs
import com.droiddeck.launcher.gpu.GpuInfo
import com.droiddeck.launcher.gpu.GpuInfo.Family
import com.droiddeck.launcher.gpu.LinuxVulkanDriverManager
import com.droiddeck.launcher.gpu.TpUbwcHint
import com.droiddeck.launcher.gpu.TurnipDriver
import com.droiddeck.launcher.gpu.TurnipReleases
import com.droiddeck.launcher.session.QuestExperimental
import com.droiddeck.launcher.session.SessionPrefs
import org.json.JSONArray
import org.json.JSONObject
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

    @Test fun pickingTheAutoDisplayHandsBothHalvesToAuto() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        SessionPrefs.setGpuDriverMode(activity, SessionPrefs.GPU_DRIVERS_MIX)
        SessionPrefs.setAndroidDriver(activity, "chosen-display")
        SessionPrefs.setLinuxDriver(activity, "chosen-linux")
        val menus = DriverMenus(activity, Handler(Looper.getMainLooper()))
        menus.selectDriver(TurnipDriver.AUTO, linux = false)
        assertEquals(SessionPrefs.GPU_DRIVERS_AUTO, SessionPrefs.gpuDriverMode(activity))
        assertEquals(QuestExperimental.DRIVER, SessionPrefs.androidDriver(activity))
        assertEquals("", SessionPrefs.linuxDriver(activity))
    }

    @Test fun pickingTheAutoDisplayWithNoPairForTheGpuStillPutsTheDisplayOnAuto() {
        ShadowBuild.setModel("Generic phone")
        ShadowBuild.setDevice("generic")
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        SessionPrefs.setGpuDriverMode(activity, SessionPrefs.GPU_DRIVERS_MIX)
        SessionPrefs.setAndroidDriver(activity, "chosen-display")
        SessionPrefs.setLinuxDriver(activity, "chosen-linux")
        DriverMenus(activity, Handler(Looper.getMainLooper())).selectDriver(TurnipDriver.AUTO, linux = false)
        assertEquals(SessionPrefs.GPU_DRIVERS_AUTO, SessionPrefs.gpuDriverMode(activity))
        assertEquals(TurnipDriver.AUTO, SessionPrefs.androidDriver(activity))
        assertEquals("chosen-linux", SessionPrefs.linuxDriver(activity))
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
        assertFalse(menus.tpUbwcRestartRequired())
    }

    @Test fun changingUbwcBeforeTheFirstSessionDoesNotRequireRestart() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val menus = DriverMenus(activity, Handler(Looper.getMainLooper()))
        menus.refreshDrivers()
        SessionPrefs.setTpUbwcMode(activity, SessionPrefs.TP_UBWC_ON)
        menus.refreshDrivers()
        assertFalse(menus.tpUbwcRestartRequired())
    }

    @Test fun recreatingTheMenuKeepsTheActiveHintRestartWarning() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        TpUbwcHint.forThisDevice(activity)
        SessionPrefs.setTpUbwcMode(activity, SessionPrefs.TP_UBWC_ON)
        val menus = DriverMenus(activity, Handler(Looper.getMainLooper()))
        menus.refreshDrivers()
        assertTrue(menus.tpUbwcRestartRequired())
    }

    private fun library(libc: String) = ByteArray(64).also {
        byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1).copyInto(it)
        it[18] = 0xB7.toByte()
    } + libc.toByteArray() + ByteArray(1)

    private fun bundleZip(context: Context): Uri {
        val manifest = JSONObject()
            .put("package", "PanVK Mali JM").put("version", "1.0").put("tag", "panvk-mali-jm")
            .put("drivers", JSONObject()
                .put("android", JSONObject().put("path", "android"))
                .put("linux", JSONObject().put("path", "linux")))
        val file = File.createTempFile("bundle", ".zip", context.cacheDir)
        ZipOutputStream(file.outputStream()).use { z ->
            fun put(name: String, data: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() }
            put("manifest.json", manifest.toString().toByteArray())
            put("android/meta.json", JSONObject().put("name", "PanVK Mali JM").put("libraryName", "libvulkan_freedreno.so").toString().toByteArray())
            put("android/libvulkan_freedreno.so", library("libc.so"))
            put("linux/meta.json", JSONObject().put("name", "PanVK Mali JM").toString().toByteArray())
            put("linux/libvulkan_freedreno.so", library("libc.so.6"))
        }
        return Uri.fromFile(file)
    }

    private fun setCachedMaliJmPair(context: Context, assetName: String = "PanVK-JM-Pair.zip") {
        val asset = JSONObject()
            .put("source", TurnipReleases.NON_ADRENO_LABEL).put("tag", "panvk-mali-jm")
            .put("name", assetName).put("url", "https://example/$assetName")
            .put("size", 1L).put("linux", false).put("label", "Android + Linux · PanVK Mali JM")
            .put("sha256", "0".repeat(64)).put("pair", DriverPairs.NAD_MALI_JM)
            .put("bundle", true).put("id", 1L).put("updated_at", "2026-01-01T00:00:00Z")
        val stored = JSONObject().put("assets", JSONArray().put(asset))
            .put("latest", JSONArray()).put("failed", JSONArray())
            .put("checkedAt", System.currentTimeMillis())
        context.getSharedPreferences("turnip_releases", Context.MODE_PRIVATE)
            .edit().putString("latest", stored.toString()).commit()
    }

    private fun prepareActiveMaliJmPair(): Pair<Activity, DriverMenus> {
        ShadowBuild.setModel("Generic phone")
        ShadowBuild.setDevice("generic")
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val bundle = DriverBundle.install(activity, bundleZip(activity))
        val assetName = "PanVK-JM-Pair.zip"
        setCachedMaliJmPair(activity, assetName)
        TurnipReleases.recordDownload(activity, TurnipReleases.cached(activity)!!.assets.first(), bundle.id)
        SessionPrefs.setGpuDriverMode(activity, SessionPrefs.GPU_DRIVERS_AUTO)
        SessionPrefs.setAndroidDriver(activity, TurnipDriver.AUTO)
        SessionPrefs.setLinuxDriver(activity, bundle.linuxId)
        val menus = DriverMenus(activity, Handler(Looper.getMainLooper()))
        ReflectionHelpers.setField(menus, "gpu", GpuInfo("Mali-G78", 78, Family.MALI_JM, "", false))
        menus.refreshDrivers()
        return activity to menus
    }

    @Test fun activeRecommendedPairWithNullRefreshFailureShowsCheckFailureStatus() {
        val (activity, menus) = prepareActiveMaliJmPair()
        val original = TurnipReleases.fetch
        TurnipReleases.fetch = { _, _, _ -> throw java.io.IOException(null as String?) }
        try {
            menus.ensureAuto(force = true)
            for (i in 0..40) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(50)
                shadowOf(Looper.getMainLooper()).idle()
                if (!menus.releaseChecking) break
            }
            assertEquals(activity.getString(R.string.drivers_up_to_date_check_failed, TurnipReleases.NON_ADRENO_LABEL), menus.autoStatus)
        } finally { TurnipReleases.fetch = original }
    }

    @Test fun activeRecommendedPairWithSuccessfulRefreshShowsCurrentStatus() {
        val (activity, menus) = prepareActiveMaliJmPair()
        menus.ensureAuto(force = false)
        for (i in 0..40) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idle()
            if (!menus.releaseChecking) break
        }
        assertEquals(activity.getString(R.string.upd_current_status), menus.autoStatus)
    }
}
