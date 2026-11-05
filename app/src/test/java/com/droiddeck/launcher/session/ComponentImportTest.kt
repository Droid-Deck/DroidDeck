package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.core.Hashes
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.github.luben.zstd.ZstdOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ComponentImportTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "components").deleteRecursively()
        LinuxRuntime.rootDir(context).deleteRecursively()
    }

    @Test fun refusesOverwritingReferencedPackageWithDifferentContent() {
        val wcpA = createWcp("src-a.wcp", "A")
        val wcpB = createWcp("src-b.wcp", "B")
        val shaA = Hashes.sha256(wcpA)

        ComponentsManager.importPackage(context, wcpA, "dxvk.wcp")
        ComponentsManager.setGameComponent(context, "10", "dxvk", "dxvk.wcp")

        assertThrows(IllegalStateException::class.java) {
            ComponentsManager.importPackage(context, wcpB, "dxvk.wcp")
        }

        val stored = File(context.filesDir, "components/packages/dxvk.wcp")
        assertEquals(shaA, Hashes.sha256(stored))
        assertFalse(File(context.filesDir, "components/packages/dxvk.wcp.part").exists())
    }

    @Test fun allowsReimportingIdenticalPackageWhileReferenced() {
        val wcpA = createWcp("src-a.wcp", "A")
        val shaA = Hashes.sha256(wcpA)

        ComponentsManager.importPackage(context, wcpA, "dxvk.wcp")
        ComponentsManager.setGameComponent(context, "10", "dxvk", "dxvk.wcp")

        val reimported = ComponentsManager.importPackage(context, wcpA, "dxvk.wcp")
        assertEquals("A", reimported.version)
        assertEquals("dxvk.wcp", reimported.file)

        val stored = File(context.filesDir, "components/packages/dxvk.wcp")
        assertEquals(shaA, Hashes.sha256(stored))
        assertFalse(File(context.filesDir, "components/packages/dxvk.wcp.part").exists())
    }

    @Test fun invalidatesUnpackedStoreWhenReplacingUnreferencedPackage() {
        val wcpA = createWcp("src-a.wcp", "A")
        val wcpB = createWcp("src-b.wcp", "B")
        val shaB = Hashes.sha256(wcpB)

        ComponentsManager.importPackage(context, wcpA, "dxvk.wcp")
        val unpacked = File(LinuxRuntime.rootDir(context), "root/.local/share/droiddeck-components/store/dxvk")
        unpacked.mkdirs()
        File(unpacked, ".complete").writeText("ok")
        assertTrue(File(unpacked, ".complete").isFile)

        val replaced = ComponentsManager.importPackage(context, wcpB, "dxvk.wcp")
        assertEquals("B", replaced.version)

        val stored = File(context.filesDir, "components/packages/dxvk.wcp")
        assertEquals(shaB, Hashes.sha256(stored))
        assertFalse(unpacked.exists())
    }

    @Test fun deletingAPackageAGameProfileUsesClearsThatProfileEntry() {
        val wcpA = createWcp("src-a.wcp", "A")
        ComponentsManager.importPackage(context, wcpA, "dxvk.wcp")
        writeGameProfile("10", "dxvk", "dxvk.wcp")

        ComponentsManager.deletePackage(context, "dxvk.wcp")

        assertFalse(File(context.filesDir, "components/packages/dxvk.wcp").exists())
        val state = JSONObject(File(context.filesDir, "components/state.json").readText())
        assertFalse(state.optJSONObject("profiles")?.optJSONObject("10")?.has("dxvk") == true)
    }

    private fun writeGameProfile(scope: String, comp: String, file: String) {
        val state = File(context.filesDir, "components/state.json")
        state.parentFile!!.mkdirs()
        state.writeText(
            JSONObject()
                .put("active", JSONObject())
                .put("queued", JSONObject())
                .put("profiles", JSONObject().put(scope, JSONObject().put(comp, file)))
                .toString(),
        )
    }

    private fun createWcp(fileName: String, version: String): File {
        val out = File(context.cacheDir, fileName)
        val profile = JSONObject()
            .put("type", "DXVK-Linux")
            .put("versionName", version)
            .put("description", "DXVK $version")
            .toString()
            .toByteArray(Charsets.UTF_8)
        val dll = "dxvk-dll-$version".toByteArray(Charsets.UTF_8)

        TarArchiveOutputStream(ZstdOutputStream(BufferedOutputStream(FileOutputStream(out)))).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            val profEntry = TarArchiveEntry("profile.json").apply { size = profile.size.toLong() }
            tar.putArchiveEntry(profEntry)
            tar.write(profile)
            tar.closeArchiveEntry()

            val dllEntry = TarArchiveEntry("files/lib/wine/dxvk/aarch64-windows/d3d11.dll").apply { size = dll.size.toLong() }
            tar.putArchiveEntry(dllEntry)
            tar.write(dll)
            tar.closeArchiveEntry()
        }
        return out
    }
}
