package com.droiddeck.launcher.gpu

import com.droiddeck.launcher.session.SessionPrefs
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class QualcommDriverPackageTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val id = "v891.7"
    private val runtimeId get() = QualcommDriverPackage.runtimeId(id)
    private val directory get() = File(TurnipDriver(context).driverPath(id))

    @Before fun reset() {
        File(context.filesDir, "graphics_driver").deleteRecursively()
        context.getSharedPreferences("session", 0).edit().clear().commit()
    }

    @Test fun previouslyImportedQualcommPackagesAreAvailableWithoutReimporting() {
        install()
        assertEquals(listOf(runtimeId), QualcommDriverPackage.available(context))
        assertTrue(QualcommSystemDriver.handles(runtimeId))
        assertTrue(QualcommDriverPackage.label(context, runtimeId).contains(id))
    }

    @Test fun turnipAndPackagesRequiringANewerAndroidAreExcluded() {
        install(vendor = "Mesa")
        assertTrue(QualcommDriverPackage.available(context).isEmpty())
        install(minApi = 36)
        assertTrue(QualcommDriverPackage.available(context).isEmpty())
    }

    @Test fun mountsKeepTheAndroidLoaderAndBridgeOnTheSameImportedHal() {
        install()
        elf(File(directory, "libc++.so"))
        elf(File(directory, "libllvm-qgl.so"))
        elf(File(directory, "libc.so"))
        elf(File(directory, "wrong-architecture.so"), arm64 = false)
        val binds = QualcommDriverPackage.mounts(context, runtimeId)
        assertTrue("${File(directory, "vulkan.ad891.so").path}:${QualcommSystemDriver.HAL}" in binds)
        assertTrue("${File(directory, "libc++.so").path}:/system/lib64/libc++.so" in binds)
        assertTrue("${File(directory, "libc++.so").path}:/vendor/lib64/libc++.so" in binds)
        assertTrue("${File(directory, "libllvm-qgl.so").path}:/vendor/lib64/libllvm-qgl.so" in binds)
        assertFalse(binds.any { "wrong-architecture" in it || it.endsWith("/libc.so") })
    }

    @Test fun deletingTheDisplayPackageAlsoClearsItsLinuxSelection() {
        install()
        SessionPrefs.setLinuxDriver(context, runtimeId)
        SessionPrefs.setAndroidDriver(context, id)
        TurnipDriver(context).remove(id)
        assertEquals("", SessionPrefs.linuxDriver(context))
        assertEquals("", SessionPrefs.androidDriver(context))
        assertFalse(QualcommDriverPackage.isInstalled(context, runtimeId))
    }

    @Test fun invalidAndMissingPackagesCannotSilentlyUseTheSystemDriver() {
        assertNull(QualcommDriverPackage.importedId("qualcomm-adrenotools:../escape"))
        assertNull(QualcommDriverPackage.importedId("qualcomm-adrenotools:/absolute"))
        assertNull(QualcommDriverPackage.importedId("qualcomm-adrenotools:a:b"))
        assertThrows(IllegalStateException::class.java) { QualcommDriverPackage.mounts(context, runtimeId) }
        assertThrows(IllegalStateException::class.java) { LinuxVulkanDriver.resolveIcdPath(context, runtimeId) }
    }

    private fun install(vendor: String = "Qualcomm", minApi: Int = 28) {
        directory.mkdirs()
        File(directory, "meta.json").writeText(JSONObject().put("name", id).put("vendor", vendor)
            .put("libraryName", "vulkan.ad891.so").put("minApi", minApi).put("driverVersion", "Vulkan 1.4.359").toString())
        elf(File(directory, "vulkan.ad891.so"))
    }

    private fun elf(file: File, arm64: Boolean = true) {
        file.writeBytes(ByteArray(20).apply {
            this[0] = 0x7f; this[1] = 'E'.code.toByte(); this[2] = 'L'.code.toByte(); this[3] = 'F'.code.toByte()
            this[4] = 2; this[5] = 1; this[18] = (if (arm64) 0xb7 else 0x3e).toByte()
        })
    }
}
