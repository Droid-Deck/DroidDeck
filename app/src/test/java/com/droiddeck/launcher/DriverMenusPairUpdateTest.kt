package com.droiddeck.launcher

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.droiddeck.launcher.gpu.DriverBundle
import com.droiddeck.launcher.gpu.DriverPairs
import com.droiddeck.launcher.gpu.GpuInfo
import com.droiddeck.launcher.gpu.LinuxVulkanDriverManager
import com.droiddeck.launcher.gpu.TurnipDriver
import com.droiddeck.launcher.gpu.TurnipReleases
import com.droiddeck.launcher.session.MaliGraphics
import com.droiddeck.launcher.session.MaliGraphicsMode
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, instrumentedPackages = ["com.droiddeck.launcher.gpu.TurnipReleases"],
    shadows = [DriverMenusPairUpdateTest.LocalDownload::class])
class DriverMenusPairUpdateTest {
    private lateinit var activity: Activity
    private lateinit var menus: DriverMenus
    private val tag = "PanVK-Mali-G720"
    private val key = DriverPairs.NAD_MALI_G720

    @Before fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        for (name in listOf("session", "driver_bundles", "turnip_releases")) {
            activity.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        File(activity.filesDir, "graphics_driver").deleteRecursively()
        File(activity.filesDir, LinuxVulkanDriverManager.DIR_NAME).deleteRecursively()
        SessionState.running = false
        SessionState.phase = com.droiddeck.launcher.session.SessionPhase.IDLE
        LocalDownload.entered = null
        LocalDownload.resume = null
        menus = DriverMenus(activity, Handler(Looper.getMainLooper()))
        ReflectionHelpers.setField(menus, "gpu", GpuInfo("Mali-G720", 720, GpuInfo.Family.MALI_G720, "", false))
    }

    @Test fun updatingTheActiveBundlePreservesRuntimeAndIntentInEveryMode() {
        for (overall in listOf(SessionPrefs.GPU_DRIVERS_AUTO, SessionPrefs.GPU_DRIVERS_MANUAL, SessionPrefs.GPU_DRIVERS_MIX)) {
            for (choice in MaliGraphicsMode.entries) {
                val old = installed()
                SessionPrefs.setAndroidDriver(activity, TurnipDriver.AUTO)
                SessionPrefs.setLinuxDriver(activity, old.linuxId)
                SessionPrefs.setGpuDriverMode(activity, overall)
                SessionPrefs.setMaliGraphicsMode(activity, choice)
                offerUpdate()
                menus.refreshDrivers()
                assertTrue(menus.pairRows.single().active)
                assertNotNull(menus.pairRows.single().update)

                menus.updatePair(key)
                finishInstall()

                val replacement = DriverBundle.get(activity, tag)!!
                assertEquals("new", replacement.version)
                assertNotEquals(old.linuxId, replacement.linuxId)
                assertFalse(LinuxVulkanDriverManager(activity).isInstalled(old.linuxId))
                assertFalse(TurnipDriver(activity).isInstalled(old.androidId))
                assertEquals(replacement.linuxId, SessionPrefs.linuxDriver(activity))
                assertNotNull(MaliGraphics.panvkIcd(activity))
                assertEquals(TurnipDriver.AUTO, SessionPrefs.androidDriver(activity))
                assertEquals(choice, SessionPrefs.maliGraphicsMode(activity))
                assertEquals(overall, SessionPrefs.gpuDriverMode(activity))
                assertTrue(menus.pairRows.single().active)
                assertNull(menus.pairRows.single().update)
            }
        }
    }

    @Test fun updatingAnInactiveBundleDoesNotSelectIt() {
        installed()
        SessionPrefs.setLinuxDriver(activity, "chosen-runtime")
        SessionPrefs.setAndroidDriver(activity, "chosen-display")
        SessionPrefs.setMaliGraphicsMode(activity, MaliGraphicsMode.VENUS)
        offerUpdate()
        menus.refreshDrivers()
        assertFalse(menus.pairRows.single().active)
        menus.updatePair(key)
        finishInstall()
        assertEquals("new", DriverBundle.get(activity, tag)?.version)
        assertEquals("chosen-runtime", SessionPrefs.linuxDriver(activity))
        assertEquals("chosen-display", SessionPrefs.androidDriver(activity))
        assertEquals(MaliGraphicsMode.VENUS, SessionPrefs.maliGraphicsMode(activity))
        assertFalse(menus.pairRows.single().active)
    }

    @Test fun automaticReplacementPreservesImportedBundleWhenChoiceChangesDuringDownload() {
        val old = DriverBundle.install(activity, Uri.fromFile(bundle("old")))
        SessionPrefs.setLinuxDriver(activity, old.linuxId)
        SessionPrefs.setAndroidDriver(activity, TurnipDriver.AUTO)
        offerUpdate()
        SessionPrefs.setGpuDriverMode(activity, SessionPrefs.GPU_DRIVERS_AUTO)
        SessionPrefs.setMaliGraphicsMode(activity, MaliGraphicsMode.AUTO)
        pauseDownload()
        val pair = DriverPairs.from(TurnipReleases.cached(activity)).single()
        DriverMenus::class.java.getDeclaredMethod("installPair", DriverPairs.DriverPair::class.java,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(menus, pair, true, false, true)
        assertTrue(LocalDownload.entered!!.await(10, TimeUnit.SECONDS))
        SessionPrefs.setMaliGraphicsMode(activity, MaliGraphicsMode.PANVK)
        LocalDownload.resume!!.countDown()
        finishInstall()
        assertEquals("old", DriverBundle.get(activity, tag)?.version)
        assertEquals(old.linuxId, SessionPrefs.linuxDriver(activity))
        assertNotNull(MaliGraphics.panvkIcd(activity))
        assertEquals(TurnipDriver.AUTO, SessionPrefs.androidDriver(activity))
        assertEquals(MaliGraphicsMode.PANVK, SessionPrefs.maliGraphicsMode(activity))
    }

    @Test fun updateFollowsOnlySlotsStillSelectedAfterDownload() {
        for (keepDisplay in listOf(false, true)) {
            val old = installed()
            DriverBundle.select(activity, old)
            SessionPrefs.setGpuDriverMode(activity, SessionPrefs.GPU_DRIVERS_AUTO)
            offerUpdate()
            pauseDownload()
            menus.updatePair(key)
            assertTrue(LocalDownload.entered!!.await(10, TimeUnit.SECONDS))
            SessionPrefs.setGpuDriverMode(activity, SessionPrefs.GPU_DRIVERS_MIX)
            SessionPrefs.setMaliGraphicsMode(activity, MaliGraphicsMode.PANVK)
            if (keepDisplay) SessionPrefs.setLinuxDriver(activity, "later-linux")
            else SessionPrefs.setAndroidDriver(activity, "later-display")
            LocalDownload.resume!!.countDown()
            finishInstall()
            val replacement = DriverBundle.get(activity, tag)!!
            assertEquals(if (keepDisplay) replacement.androidId else "later-display", SessionPrefs.androidDriver(activity))
            assertEquals(if (keepDisplay) "later-linux" else replacement.linuxId, SessionPrefs.linuxDriver(activity))
            assertEquals(SessionPrefs.GPU_DRIVERS_MIX, SessionPrefs.gpuDriverMode(activity))
            assertEquals(MaliGraphicsMode.PANVK, SessionPrefs.maliGraphicsMode(activity))
        }
    }

    @Test fun mixPartialSelectionFollowsOnlyItsOwnSlot() {
        for (displayOnly in listOf(false, true)) {
            val old = installed()
            SessionPrefs.setGpuDriverMode(activity, SessionPrefs.GPU_DRIVERS_MIX)
            SessionPrefs.setAndroidDriver(activity, if (displayOnly) old.androidId else "other-display")
            SessionPrefs.setLinuxDriver(activity, if (displayOnly) "other-linux" else old.linuxId)
            offerUpdate()
            menus.refreshDrivers()
            assertFalse(menus.pairRows.single().active)
            menus.updatePair(key)
            finishInstall()
            val replacement = DriverBundle.get(activity, tag)!!
            assertEquals(if (displayOnly) replacement.androidId else "other-display", SessionPrefs.androidDriver(activity))
            assertEquals(if (displayOnly) "other-linux" else replacement.linuxId, SessionPrefs.linuxDriver(activity))
        }
    }

    @Test fun launchDuringDownloadKeepsCapturedFilesAndSelection() {
        val old = installed()
        SessionPrefs.setLinuxDriver(activity, old.linuxId)
        SessionPrefs.setMaliGraphicsMode(activity, MaliGraphicsMode.PANVK)
        offerUpdate()
        pauseDownload()
        menus.updatePair(key)
        assertTrue(LocalDownload.entered!!.await(10, TimeUnit.SECONDS))
        synchronized(SessionPrefs) { SessionState.running = true }
        val launch = MaliGraphics.capture(activity)
        LocalDownload.resume!!.countDown()
        finishInstall()
        assertEquals(old.linuxId, launch.linux)
        assertTrue(File(launch.icd!!).isFile)
        assertEquals("old", DriverBundle.get(activity, tag)?.version)
        assertEquals(old.linuxId, SessionPrefs.linuxDriver(activity))
        SessionState.running = false
    }

    private fun pauseDownload() {
        LocalDownload.entered = CountDownLatch(1)
        LocalDownload.resume = CountDownLatch(1)
    }

    @Test fun automaticCallbackPreservesChoiceMadeAfterReplacement() {
        installed()
        offerUpdate()
        SessionPrefs.setGpuDriverMode(activity, SessionPrefs.GPU_DRIVERS_AUTO)
        SessionPrefs.setMaliGraphicsMode(activity, MaliGraphicsMode.AUTO)
        val pair = DriverPairs.from(TurnipReleases.cached(activity)).single()
        DriverMenus::class.java.getDeclaredMethod("installPair", DriverPairs.DriverPair::class.java,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(menus, pair, true, false, true)
        waitForInstallThread()
        assertEquals("new", DriverBundle.get(activity, tag)?.version)
        SessionPrefs.setMaliGraphicsMode(activity, MaliGraphicsMode.VENUS)
        SessionPrefs.setLinuxDriver(activity, "later-linux")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("later-linux", SessionPrefs.linuxDriver(activity))
        assertEquals(MaliGraphicsMode.VENUS, SessionPrefs.maliGraphicsMode(activity))
        assertFalse(menus.autoStatus.contains("Downloading", ignoreCase = true))
    }

    @Test fun compositorStartupAlsoBlocksDestructiveReplacement() {
        val old = installed()
        SessionPrefs.setLinuxDriver(activity, old.linuxId)
        offerUpdate()
        pauseDownload()
        menus.updatePair(key)
        assertTrue(LocalDownload.entered!!.await(10, TimeUnit.SECONDS))
        synchronized(SessionPrefs) { SessionState.phase = com.droiddeck.launcher.session.SessionPhase.STARTING_COMPOSITOR }
        LocalDownload.resume!!.countDown()
        finishInstall()
        assertEquals("old", DriverBundle.get(activity, tag)?.version)
        assertEquals(old.linuxId, SessionPrefs.linuxDriver(activity))
        SessionState.phase = com.droiddeck.launcher.session.SessionPhase.IDLE
    }

    @Test fun automaticCallbackWaitsForCompositorStartupBeforeSelectingOrPruning() {
        installed()
        offerUpdate()
        SessionPrefs.setGpuDriverMode(activity, SessionPrefs.GPU_DRIVERS_AUTO)
        SessionPrefs.setMaliGraphicsMode(activity, MaliGraphicsMode.AUTO)
        SessionPrefs.setLinuxDriver(activity, "launch-linux")
        val pair = DriverPairs.from(TurnipReleases.cached(activity)).single()
        DriverMenus::class.java.getDeclaredMethod("installPair", DriverPairs.DriverPair::class.java,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(menus, pair, true, false, true)
        waitForInstallThread()
        SessionState.phase = com.droiddeck.launcher.session.SessionPhase.STARTING_COMPOSITOR
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("launch-linux", SessionPrefs.linuxDriver(activity))
        assertNull(com.droiddeck.launcher.gpu.AutoDriverTrial.current(activity))
        assertEquals(activity.getString(R.string.drivers_auto_waits_for_session), menus.autoStatus)
        SessionState.phase = com.droiddeck.launcher.session.SessionPhase.IDLE
    }

    @Test fun automaticReplacementDoesNotRollBackToTheSameNewRuntime() {
        val old = DriverBundle.install(activity, Uri.fromFile(bundle("old")))
        SessionPrefs.setLinuxDriver(activity, old.linuxId)
        SessionPrefs.setAndroidDriver(activity, TurnipDriver.AUTO)
        SessionPrefs.setGpuDriverMode(activity, SessionPrefs.GPU_DRIVERS_AUTO)
        SessionPrefs.setMaliGraphicsMode(activity, MaliGraphicsMode.AUTO)
        offerUpdate()
        val pair = DriverPairs.from(TurnipReleases.cached(activity)).single()
        DriverMenus::class.java.getDeclaredMethod("installPair", DriverPairs.DriverPair::class.java,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(menus, pair, true, false, true)
        finishInstall()
        val replacement = DriverBundle.get(activity, tag)!!
        assertEquals(replacement.linuxId, SessionPrefs.linuxDriver(activity))
        SessionPrefs.setMaliGraphicsMode(activity, MaliGraphicsMode.PANVK)
        repeat(2) { com.droiddeck.launcher.gpu.AutoDriverTrial.sessionStarting(activity, TurnipDriver.AUTO, replacement.linuxId) }
        menus.ensureAuto(true)
        assertEquals("", SessionPrefs.linuxDriver(activity))
        assertEquals(MaliGraphicsMode.PANVK, SessionPrefs.maliGraphicsMode(activity))
        assertTrue(com.droiddeck.launcher.gpu.AutoDriverTrial.release(pair) in com.droiddeck.launcher.gpu.AutoDriverTrial.bad(activity))
    }

    private fun finishInstall() {
        waitForInstallThread()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(menus.pairBusy)
    }

    private fun waitForInstallThread() {
        Thread.getAllStackTraces().keys.filter { it.name == "gpu-driver-pair" }.forEach {
            it.join(10_000)
            assertFalse("driver install did not finish", it.isAlive)
        }
    }

    private fun asset(version: String) = TurnipReleases.Asset("Non-Adreno", tag, "$tag-Pair.zip",
        "https://invalid.example/pair.zip", 100, false, "PanVK", version, key, true)

    private fun installed(): DriverBundle.Bundle = DriverBundle.install(activity, Uri.fromFile(bundle("old"))).also {
        TurnipReleases.recordDownload(activity, asset("old"), it.id)
    }

    private fun offerUpdate() {
        LocalDownload.archive = bundle("new")
        val a = asset("new")
        val json = JSONObject().put("assets", JSONArray().put(JSONObject()
            .put("source", a.source).put("tag", a.tag).put("name", a.name).put("url", a.url)
            .put("size", a.size).put("linux", false).put("label", a.label).put("sha256", a.sha256)
            .put("pair", a.pair).put("bundle", true))).put("checkedAt", System.currentTimeMillis())
        activity.getSharedPreferences("turnip_releases", Context.MODE_PRIVATE).edit().putString("latest", json.toString()).commit()
    }

    private fun bundle(version: String): File {
        fun library(libc: String) = ByteArray(64).also {
            byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1).copyInto(it)
            it[18] = 0xB7.toByte()
        } + libc.toByteArray() + ByteArray(1)
        val entries = mapOf(
            "manifest.json" to """{"package":"PanVK","version":"$version","tag":"$tag","drivers":{"android":{"path":"android"},"linux":{"path":"linux"}}}""".toByteArray(),
            "android/meta.json" to """{"name":"PanVK $version","libraryName":"libvulkan_panfrost.so"}""".toByteArray(),
            "android/libvulkan_panfrost.so" to library("libc.so"),
            "linux/meta.json" to """{"name":"PanVK $version"}""".toByteArray(),
            "linux/libvulkan_panfrost.so" to library("libc.so.6"),
        )
        return File.createTempFile("panvk", ".zip", activity.cacheDir).also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                for ((name, bytes) in entries) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
    }

    @Implements(value = TurnipReleases::class, isInAndroidSdk = false)
    class LocalDownload {
        @Implementation fun download(context: Context, asset: TurnipReleases.Asset, progress: (Int) -> Unit): File {
            entered?.countDown()
            check(resume?.await(10, TimeUnit.SECONDS) != false) { "download was not resumed" }
            progress(100)
            return archive.copyTo(File(context.cacheDir, asset.name), overwrite = true)
        }
        companion object {
            lateinit var archive: File
            var entered: CountDownLatch? = null
            var resume: CountDownLatch? = null
        }
    }
}
