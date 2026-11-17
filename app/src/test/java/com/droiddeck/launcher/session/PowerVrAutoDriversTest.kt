package com.droiddeck.launcher.session

import android.content.Context
import android.net.Uri
import com.droiddeck.launcher.DriverMenusAutoTest
import com.droiddeck.launcher.core.DeviceSupport
import com.droiddeck.launcher.gpu.DriverBundle
import com.droiddeck.launcher.gpu.GpuInfo
import com.droiddeck.launcher.gpu.LinuxVulkanDriverManager
import com.droiddeck.launcher.gpu.TurnipDriver
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [DriverMenusAutoTest.XclipseDriver::class])
class PowerVrAutoDriversTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        DriverMenusAutoTest.XclipseDriver.enabled = false
        clearBundles()
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
        SessionPrefs.setLinuxDriver(context, "imported-linux")
        SessionPrefs.setAndroidDriver(context, "imported-display")
    }

    @After fun cleanupBundles() {
        clearBundles()
    }

    private fun clearBundles() {
        File(context.filesDir, "graphics_driver").deleteRecursively()
        File(context.filesDir, LinuxVulkanDriverManager.DIR_NAME).deleteRecursively()
        context.getSharedPreferences("driver_bundles", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun installAndSelectNonAdrenoBundle(
        tag: String = "RADV-Xclipse",
        packageName: String = "RADV-Xclipse",
    ): DriverBundle.Bundle {
        fun library(libc: String) = ByteArray(64).also {
            byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1).copyInto(it)
            it[18] = 0xB7.toByte()
        } + libc.toByteArray() + ByteArray(1)

        val manifest = JSONObject()
            .put("package", packageName)
            .put("version", "0.1.0")
            .put("tag", tag)
            .put(
                "drivers",
                JSONObject()
                    .put("android", JSONObject().put("path", "android"))
                    .put("linux", JSONObject().put("path", "linux")),
            )
        val entries = mapOf(
            "android/meta.json" to JSONObject().put("name", "radv v0.1.0").put("libraryName", "libvulkan_radeon.so").toString().toByteArray(),
            "android/libvulkan_radeon.so" to library("libc.so"),
            "linux/meta.json" to JSONObject().put("name", "radv v0.1.0").put("driverVersion", "radv-test").toString().toByteArray(),
            "linux/libvulkan_radeon.so" to library("libc.so.6"),
            "manifest.json" to manifest.toString().toByteArray(),
        )
        val file = File.createTempFile("radv-bundle", ".zip", context.cacheDir)
        return try {
            ZipOutputStream(file.outputStream()).use { z ->
                for ((name, data) in entries) {
                    z.putNextEntry(ZipEntry(name))
                    z.write(data)
                    z.closeEntry()
                }
            }
            val bundle = DriverBundle.install(context, Uri.fromFile(file))
            DriverBundle.select(context, bundle)
            bundle
        } finally {
            file.delete()
        }
    }

    @Test fun autoRepairsStaleAdrenoSelectionsOnPowerVr() {
        SessionPrefs.setGpuDriverMode(context, SessionPrefs.GPU_DRIVERS_AUTO)
        SessionPrefs.ensurePowerVrAutoDrivers(context, powerVr = true)
        assertEquals("", SessionPrefs.linuxDriver(context))
        assertEquals(TurnipDriver.AUTO, SessionPrefs.androidDriver(context))
    }

    @Test fun nonXclipsePowerVrKeepsManualPair() {
        SessionPrefs.setGpuDriverMode(context, SessionPrefs.GPU_DRIVERS_AUTO)
        SessionPrefs.ensurePowerVrAutoDrivers(context, powerVr = true, autoOwnedOnly = true)
        assertEquals("imported-linux", SessionPrefs.linuxDriver(context))
        assertEquals("imported-display", SessionPrefs.androidDriver(context))
    }

    @Test fun unsupportedXclipseClearsAnAutoOwnedOldPair() {
        SessionPrefs.setGpuDriverMode(context, SessionPrefs.GPU_DRIVERS_AUTO)
        SessionPrefs.setLinuxDriver(context, "old-linux")
        SessionPrefs.setAndroidDriver(context, "old-display")
        SessionPrefs.setGpuAutoInstalled(context, setOf("old-display", "old-linux"))
        SessionPrefs.ensurePowerVrAutoDrivers(context, powerVr = true, autoOwnedOnly = true)
        assertEquals("", SessionPrefs.linuxDriver(context))
        assertEquals(TurnipDriver.AUTO, SessionPrefs.androidDriver(context))
    }

    @Test fun unsupportedXclipseKeepsAManualPair() {
        SessionPrefs.setGpuDriverMode(context, SessionPrefs.GPU_DRIVERS_MANUAL)
        SessionPrefs.setLinuxDriver(context, "old-linux")
        SessionPrefs.setAndroidDriver(context, "old-display")
        SessionPrefs.setGpuAutoInstalled(context, setOf("old-display", "old-linux"))
        SessionPrefs.ensurePowerVrAutoDrivers(context, powerVr = true, autoOwnedOnly = true)
        assertEquals("old-linux", SessionPrefs.linuxDriver(context))
        assertEquals("old-display", SessionPrefs.androidDriver(context))
    }

    @Test fun autoClearsOwnedDisplayButKeepsManualLinux() {
        SessionPrefs.setGpuDriverMode(context, SessionPrefs.GPU_DRIVERS_AUTO)
        SessionPrefs.setGpuAutoInstalled(context, setOf("imported-display"))
        SessionPrefs.ensurePowerVrAutoDrivers(context, powerVr = true, autoOwnedOnly = true)
        assertEquals(TurnipDriver.AUTO, SessionPrefs.androidDriver(context))
        assertEquals("imported-linux", SessionPrefs.linuxDriver(context))
    }

    @Test fun autoClearsOwnedLinuxButKeepsManualDisplay() {
        SessionPrefs.setGpuDriverMode(context, SessionPrefs.GPU_DRIVERS_AUTO)
        SessionPrefs.setGpuAutoInstalled(context, setOf("imported-linux"))
        SessionPrefs.ensurePowerVrAutoDrivers(context, powerVr = true, autoOwnedOnly = true)
        assertEquals("imported-display", SessionPrefs.androidDriver(context))
        assertEquals("", SessionPrefs.linuxDriver(context))
    }

    @Test fun adrenoAutoChoicesRemain() {
        SessionPrefs.setGpuDriverMode(context, SessionPrefs.GPU_DRIVERS_AUTO)
        SessionPrefs.ensurePowerVrAutoDrivers(context, powerVr = false)
        assertEquals("imported-linux", SessionPrefs.linuxDriver(context))
        assertEquals("imported-display", SessionPrefs.androidDriver(context))
    }

    @Test fun sessionStartKeepsStandaloneDriverOnUnsupportedXclipse() {
        SessionPrefs.setGpuDriverMode(context, SessionPrefs.GPU_DRIVERS_AUTO)
        SessionPrefs.setAndroidDriver(context, "standalone-display")
        SessionPrefs.setGpuAutoInstalled(context, setOf("imported-linux"))
        val ownedOnly = SessionPrefs.unsupportedXclipse(true) { "Samsung Xclipse 540" }
        SessionPrefs.ensurePowerVrAutoDrivers(context, powerVr = true, autoOwnedOnly = ownedOnly)
        assertEquals("standalone-display", SessionPrefs.androidDriver(context))
        assertEquals("", SessionPrefs.linuxDriver(context))
    }

    @Test fun sessionStartOwnershipRuleIgnoresOtherGpus() {
        assertEquals(false, SessionPrefs.unsupportedXclipse(false) { "Samsung Xclipse 540" })
        assertEquals(false, SessionPrefs.unsupportedXclipse(true) { "Samsung Xclipse 920" })
    }

    @Test fun sessionStartNamesTheXclipseFromVulkanBeforeTheOwnershipRule() {
        val queried = mapOf("device" to "Samsung Xclipse 920")
        assertEquals("Xclipse", GpuInfo.systemDriverName("Xclipse", null))
        assertEquals("Samsung Xclipse 920", GpuInfo.systemDriverName("Xclipse", queried))
        assertEquals("PowerVR", GpuInfo.systemDriverName("PowerVR", queried))
        SessionPrefs.setGpuDriverMode(context, SessionPrefs.GPU_DRIVERS_AUTO)
        SessionPrefs.setGpuAutoInstalled(context, setOf("imported-linux"))
        val ownedOnly = SessionPrefs.unsupportedXclipse(true) { GpuInfo.systemDriverName("Xclipse", queried) }
        SessionPrefs.ensurePowerVrAutoDrivers(context, powerVr = true, autoOwnedOnly = ownedOnly)
        assertEquals("", SessionPrefs.linuxDriver(context))
        assertEquals(TurnipDriver.AUTO, SessionPrefs.androidDriver(context))
    }

    @Test fun sessionStartLeavesTheGpuUnqueriedOffXclipse() {
        var asked = false
        assertEquals(false, SessionPrefs.unsupportedXclipse(false) { asked = true; "Xclipse" })
        assertEquals(false, asked)
    }

    @Test fun cachedOnlySessionStartLeavesPrefsWhenTheXclipseIsUnnamed() {
        SessionPrefs.setGpuDriverMode(context, SessionPrefs.GPU_DRIVERS_AUTO)
        SessionPrefs.setGpuAutoInstalled(context, setOf("imported-display", "imported-linux"))
        var reads = 0
        SessionPrefs.reconcilePowerVrAutoDrivers(context, powerVr = true, xclipse = true) { reads++; null }
        assertEquals(1, reads)
        assertEquals("imported-linux", SessionPrefs.linuxDriver(context))
        assertEquals("imported-display", SessionPrefs.androidDriver(context))
    }

    @Test fun sessionStartWithANamedXclipseClearsOnlyAutoOwnedSelections() {
        DriverMenusAutoTest.XclipseDriver.enabled = true
        SessionPrefs.setGpuDriverMode(context, SessionPrefs.GPU_DRIVERS_AUTO)
        SessionPrefs.setGpuAutoInstalled(context, setOf("imported-linux"))
        SessionPrefs.reconcilePowerVrAutoDrivers(context, powerVr = true, xclipse = true) {
            mapOf("device" to "Samsung Xclipse 540")
        }
        assertEquals("", SessionPrefs.linuxDriver(context))
        assertEquals("imported-display", SessionPrefs.androidDriver(context))
    }

    @Test fun sessionStartUnsupportedXclipseResetsAutoOwnedEvenWithBothOpenDriversSelected() {
        DriverMenusAutoTest.XclipseDriver.enabled = true
        val bundle = installAndSelectNonAdrenoBundle()
        assertTrue(DeviceSupport.openDriverActive(context))
        SessionPrefs.setGpuDriverMode(context, SessionPrefs.GPU_DRIVERS_AUTO)
        SessionPrefs.setGpuAutoInstalled(context, setOf(bundle.androidId, bundle.linuxId))
        SessionPrefs.reconcilePowerVrAutoDrivers(context, powerVr = true, xclipse = true) {
            mapOf("device" to "Samsung Xclipse 540")
        }
        assertFalse(DeviceSupport.openDriverActive(context))
        assertEquals("", SessionPrefs.linuxDriver(context))
        assertEquals(TurnipDriver.AUTO, SessionPrefs.androidDriver(context))
    }

    @Test fun sessionStartUnsupportedXclipseKeepsAutoOwnedUpstreamBundle() {
        DriverMenusAutoTest.XclipseDriver.enabled = true
        val bundle = installAndSelectNonAdrenoBundle(
            tag = "RADV-Xclipse-upstream",
            packageName = "RADV-Xclipse-upstream",
        )
        assertTrue(DeviceSupport.openDriverActive(context))
        SessionPrefs.setGpuDriverMode(context, SessionPrefs.GPU_DRIVERS_AUTO)
        SessionPrefs.setGpuAutoInstalled(context, setOf(bundle.androidId, bundle.linuxId))
        SessionPrefs.reconcilePowerVrAutoDrivers(context, powerVr = true, xclipse = true) {
            mapOf("device" to "Samsung Xclipse 540")
        }
        assertTrue(DeviceSupport.openDriverActive(context))
        assertEquals(bundle.linuxId, SessionPrefs.linuxDriver(context))
        assertEquals(bundle.androidId, SessionPrefs.androidDriver(context))
    }

    @Test fun sessionStartSupportedXclipseKeepsActiveOpenDriverBundle() {
        DriverMenusAutoTest.XclipseDriver.enabled = true
        val bundle = installAndSelectNonAdrenoBundle()
        assertTrue(DeviceSupport.openDriverActive(context))
        SessionPrefs.setGpuDriverMode(context, SessionPrefs.GPU_DRIVERS_AUTO)
        SessionPrefs.setGpuAutoInstalled(context, setOf(bundle.androidId, bundle.linuxId))
        SessionPrefs.reconcilePowerVrAutoDrivers(context, powerVr = true, xclipse = true) {
            mapOf("device" to "Samsung Xclipse 920")
        }
        assertTrue(DeviceSupport.openDriverActive(context))
        assertEquals(bundle.linuxId, SessionPrefs.linuxDriver(context))
        assertEquals(bundle.androidId, SessionPrefs.androidDriver(context))
    }

    @Test fun sessionStartWithASupportedXclipseClearsUnownedSelections() {
        DriverMenusAutoTest.XclipseDriver.enabled = true
        SessionPrefs.setGpuDriverMode(context, SessionPrefs.GPU_DRIVERS_AUTO)
        SessionPrefs.reconcilePowerVrAutoDrivers(context, powerVr = true, xclipse = true) {
            mapOf("device" to "Samsung Xclipse 920")
        }
        assertEquals("", SessionPrefs.linuxDriver(context))
        assertEquals(TurnipDriver.AUTO, SessionPrefs.androidDriver(context))
    }

    @Test fun sessionStartReadsNoVulkanNameOffXclipse() {
        SessionPrefs.setGpuDriverMode(context, SessionPrefs.GPU_DRIVERS_AUTO)
        SessionPrefs.reconcilePowerVrAutoDrivers(context, powerVr = true, xclipse = false) {
            throw AssertionError("queried")
        }
        assertEquals("", SessionPrefs.linuxDriver(context))
        assertEquals(TurnipDriver.AUTO, SessionPrefs.androidDriver(context))
    }
}
