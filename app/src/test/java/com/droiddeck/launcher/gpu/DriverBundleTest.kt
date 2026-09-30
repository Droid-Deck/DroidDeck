package com.droiddeck.launcher.gpu

import android.content.Context
import android.net.Uri
import com.droiddeck.launcher.session.SessionPrefs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
@Config(sdk = [28], manifest = Config.NONE)
class DriverBundleTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "graphics_driver").deleteRecursively()
        File(context.filesDir, LinuxVulkanDriverManager.DIR_NAME).deleteRecursively()
        context.getSharedPreferences("driver_bundles", Context.MODE_PRIVATE).edit().clear().commit()
        SessionPrefs.setAndroidDriver(context, "")
        SessionPrefs.setLinuxDriver(context, "")
    }

    /** Enough of an AArch64 ELF for the importers: the header they read, then the libc they look for. */
    private fun library(libc: String) = ByteArray(64).also {
        byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1).copyInto(it)
        it[18] = 0xB7.toByte()
    } + libc.toByteArray() + ByteArray(1)

    private val manifest = JSONObject()
        .put("package", "DD-Turnip").put("version", "0.1.0").put("tag", "DD-Turnip-v0.1.0")
        .put("drivers", JSONObject()
            .put("android", JSONObject().put("path", "android"))
            .put("linux", JSONObject().put("path", "linux")))

    private fun zip(entries: Map<String, ByteArray>): Uri {
        val file = File.createTempFile("bundle", ".zip", context.cacheDir)
        ZipOutputStream(file.outputStream()).use { z ->
            for ((name, data) in entries) { z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() }
        }
        return Uri.fromFile(file)
    }

    private fun bundle(linuxLibc: String = "libc.so.6", extra: Map<String, ByteArray> = emptyMap()) = zip(mapOf(
        "android/meta.json" to JSONObject().put("name", "DD-Turnip v0.1.0").put("libraryName", "libvulkan_freedreno.so").toString().toByteArray(),
        "android/libvulkan_freedreno.so" to library("libc.so"),
        "linux/meta.json" to JSONObject().put("name", "DD-Turnip v0.1.0").put("driverVersion", "DD-Turnip-v0.1.0").toString().toByteArray(),
        "linux/libvulkan_freedreno.so" to library(linuxLibc),
        "manifest.json" to manifest.toString().toByteArray(),
    ) + extra)

    @Test fun manifestNeedsBothHalvesInSeparateSafeDirectories() {
        val m = DriverBundle.parseManifest(manifest)!!
        assertEquals("android", m.androidPath)
        assertEquals("linux", m.linuxPath)
        assertNull(DriverBundle.parseManifest(JSONObject().put("drivers", JSONObject().put("android", JSONObject().put("path", "a")))))
        val same = JSONObject(manifest.toString()).apply { getJSONObject("drivers").getJSONObject("linux").put("path", "android/") }
        assertNull(DriverBundle.parseManifest(same))
        val escape = JSONObject(manifest.toString()).apply { getJSONObject("drivers").getJSONObject("linux").put("path", "../x") }
        assertNull(DriverBundle.parseManifest(escape))
    }

    @Test fun singleDriverZipsAreNotBundles() {
        val legacy = zip(mapOf("meta.json" to "{}".toByteArray(), "libvulkan_freedreno.so" to library("libc.so")))
        assertFalse(DriverBundle.isBundle(context, legacy))
        assertTrue(DriverBundle.isBundle(context, bundle()))
    }

    @Test fun installsBothHalvesAndPicksAndDeletesThemTogether() {
        val b = DriverBundle.install(context, bundle())
        assertEquals("DD-Turnip-v0.1.0", b.id)
        assertEquals("DD-Turnip 0.1.0", b.label)
        assertTrue(TurnipDriver(context).isInstalled(b.androidId))
        assertTrue(LinuxVulkanDriverManager(context).isInstalled(b.linuxId))
        assertEquals(b.id, DriverBundle.containing(context, b.linuxId, linux = true)?.id)
        assertEquals(b.id, DriverBundle.containing(context, b.androidId, linux = false)?.id)

        assertNull(DriverBundle.active(context))
        DriverBundle.select(context, b)
        assertEquals(b.androidId, SessionPrefs.androidDriver(context))
        assertEquals(b.linuxId, SessionPrefs.linuxDriver(context))
        assertNotNull(DriverBundle.active(context))

        DriverBundle.remove(context, b)
        assertFalse(TurnipDriver(context).isInstalled(b.androidId))
        assertFalse(LinuxVulkanDriverManager(context).isInstalled(b.linuxId))
        assertEquals("", SessionPrefs.androidDriver(context))
        assertEquals("", SessionPrefs.linuxDriver(context))
        assertTrue(DriverBundle.all(context).isEmpty())
    }

    @Test fun aRefusedHalfLeavesNothingInstalled() {
        try {
            DriverBundle.install(context, bundle(linuxLibc = "libc.so"))
            fail("a bionic library in linux/ must be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("glibc"))
        }
        assertTrue(TurnipDriver(context).enumerateImported().isEmpty())
        assertTrue(LinuxVulkanDriverManager(context).enumerateInstalledDrivers().isEmpty())
    }

    @Test fun entriesOutsideTheBundleAreRefused() {
        try {
            DriverBundle.install(context, bundle(extra = mapOf("../escape.so" to ByteArray(4))))
            fail("a zip-slip entry must be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("outside"))
        }
        assertFalse(File(context.cacheDir.parentFile, "escape.so").exists())
    }

    @Test fun twoInstallsOfOneReleaseGetTheirOwnIds() {
        val first = DriverBundle.install(context, bundle())
        val second = DriverBundle.install(context, bundle())
        assertEquals("DD-Turnip-v0.1.0-2", second.id)
        assertEquals(2, DriverBundle.all(context).size)
        assertFalse(first.androidId == second.androidId)
    }
}
