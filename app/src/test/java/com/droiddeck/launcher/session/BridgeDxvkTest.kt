package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.gpu.AppVulkan
import com.droiddeck.launcher.gpu.GpuInfo
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.github.luben.zstd.ZstdOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
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
class BridgeDxvkTest {
    private val old = ComponentsManager.DXVK_FOR_VULKAN_1_1
    private val mid = ComponentsManager.DXVK_FOR_VULKAN_1_3_NO_INT64
    private lateinit var context: Context

    private val g710 = AppVulkan(1, 1, shaderInt64 = false, maxPushConstants = 128, source = "driver")
    private val promoted = g710.copy(minor = 2, source = "bridge")
    private val full = AppVulkan(1, 3, shaderInt64 = true, maxPushConstants = 256, source = "driver")
    private val mali = GpuInfo("Mali-G710", 0, GpuInfo.Family.NOT_ADRENO, "", false)

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "components").deleteRecursively()
        LinuxRuntime.rootDir(context).deleteRecursively()
    }

    @Test fun theNewestDxvkThatStartsIsChosen() {
        assertEquals(old, ComponentsManager.bridgeDxvk(g710)?.file)
        assertEquals(old, ComponentsManager.bridgeDxvk(promoted)?.file)
        assertEquals(mid, ComponentsManager.bridgeDxvk(full.copy(shaderInt64 = false))?.file)
        assertEquals(mid, ComponentsManager.bridgeDxvk(full.copy(maxPushConstants = 128))?.file)
        assertNull(ComponentsManager.bridgeDxvk(full))
        assertNull(ComponentsManager.bridgeDxvk(full.copy(shaderInt64 = null, maxPushConstants = null)))
        assertNull(ComponentsManager.bridgeDxvk(AppVulkan(1, 0, null, null, "driver")))
        assertNull(ComponentsManager.bridgeDxvk(null))
        assertEquals("Dxvk-Linux", ComponentsManager.bridgeDxvk(g710)?.release)
        assertEquals("dxvk", ComponentsManager.bridgeDxvk(g710)?.comp)
    }

    @Test fun theDriversLinesAreRead() {
        val vk = AppVulkan.fromDriver(mapOf("device" to "Mali-G710 MC10", "api" to "1.1.177", "shader_int64" to "no", "max_push_constants" to "128"))
        assertEquals(g710, vk)
        assertNull(AppVulkan.fromDriver(mapOf("error" to "no Vulkan device")))
        assertNull(AppVulkan.fromDriver(mapOf("api" to "garbage")))
        val older = AppVulkan.fromDriver(mapOf("api" to "1.3.250"))!!
        assertNull(older.shaderInt64)
        assertNull(older.maxPushConstants)
    }

    @Test fun theBridgesFileWinsForItsOwnGpuOnly() {
        val text = "device=Mali-G710 MC10\napi=1.2.177\ndriver_api=1.1.177\npromotion=on\nshader_int64=0\nmax_push_constants=128\n"
        val on = promoted.copy(source = "bridge, promoted")
        assertEquals(on, AppVulkan.fromBridgeFile(text, "Mali-G710 MC10"))
        assertEquals(on, AppVulkan.fromBridgeFile(text, null))
        assertEquals(on, AppVulkan.fromBridgeFile(text, "Mali-G710 MC10", "1.1.177"))
        assertNull(AppVulkan.fromBridgeFile(text, "Mali-G720 Immortalis MC12"))
        assertNull(AppVulkan.fromBridgeFile("device=x\n", null))
    }

    @Test fun withoutPromotionOnTheDriversVersionCounts() {
        val g710File = g710.copy(source = "bridge")
        val off = "device=Mali-G710 MC10\napi=1.1.177\ndriver_api=1.1.177\npromotion=off\nshader_int64=0\nmax_push_constants=128\n"
        assertEquals(g710File, AppVulkan.fromBridgeFile(off, "Mali-G710 MC10", "1.1.177"))
        val old = "device=Mali-G710 MC10\napi=1.3.177\ndriver_api=1.1.177\nshader_int64=0\nmax_push_constants=128\n"
        assertEquals(g710File, AppVulkan.fromBridgeFile(old, "Mali-G710 MC10"))
        assertNull(AppVulkan.fromBridgeFile(off, "Mali-G710 MC10", "1.3.212"))
    }

    @Test fun anAdrenoSessionIsNotTouched() {
        proton("proton-exp")
        assertNull(run(g710, bridge = false))
    }

    @Test fun aShippedDxvk3IsReplacedOnAVulkan11Phone() {
        proton("proton-exp")
        proton("ge")
        val applied = mutableListOf<String>()
        val result = run(g710, applyAuto = { id, file -> applied += "$id $file"; "swapped $id" })
        assertEquals(listOf("ge $old", "proton-exp $old"), applied.sorted())
        assertTrue(result!!.startsWith("Vulkan 1.1, shaderInt64 no, push constants 128 (driver): DXVK $old"))
    }

    @Test fun thePlayersOwnChoiceIsKept() {
        proton("proton-exp")
        writeState("proton-exp", JSONObject().put("file", "dxvk-3.1-linux.wcp").put("protonVersion", "experimental-11"))
        assertNull(run(g710))
        assertNull(run(full))
    }

    @Test fun anAutomaticChoiceFollowsTheDevice() {
        proton("proton-exp")
        writeState("proton-exp", JSONObject().put("file", mid).put("protonVersion", "experimental-11").put("auto", true))
        val applied = mutableListOf<String>()
        run(g710, applyAuto = { id, file -> applied += "$id $file"; "swapped" })
        assertEquals(listOf("proton-exp $old"), applied)

        writeState("proton-exp", JSONObject().put("file", old).put("protonVersion", "experimental-11").put("auto", true))
        assertNull(run(g710))
        val undone = mutableListOf<String>()
        run(full, undoAuto = { id, comp -> undone += "$id $comp"; "undone" })
        assertEquals(listOf("proton-exp dxvk"), undone)
    }

    @Test fun theSwapAndItsUndoReachTheProton() {
        proton("proton-exp")
        val d3d11 = File(protonDir("proton-exp"), "files/lib/wine/dxvk/aarch64-windows/d3d11.dll")
        writePackage(old, "DXVK 1.10.3")
        val auto: (String, String) -> String = { id, file -> ComponentsManager.swap(context, id, file, auto = true) }
        val undo: (String, String) -> String = { id, comp -> ComponentsManager.undoAutoChoice(context, id, comp) }

        assertNotNull(run(g710, applyAuto = auto, undoAuto = undo))
        assertEquals("DXVK 1.10.3", d3d11.readText())
        val entry = state().getJSONObject("active").getJSONObject("proton-exp").getJSONObject("dxvk")
        assertEquals(old, entry.getString("file"))
        assertTrue(entry.getBoolean("auto"))
        assertNull(run(g710, applyAuto = auto, undoAuto = undo))

        run(full, applyAuto = auto, undoAuto = undo)
        assertEquals("bundled DXVK experimental-11", d3d11.readText())
        assertNull(state().getJSONObject("active").optJSONObject("proton-exp")?.optJSONObject("dxvk"))
    }

    @Test fun aFailedDetectionKeepsTheLastAutomaticChoice() {
        proton("proton-exp")
        writeState("proton-exp", JSONObject().put("file", old).put("protonVersion", "experimental-11").put("auto", true))
        assertNull(run(null))
        assertEquals(old, state().getJSONObject("active").getJSONObject("proton-exp").getJSONObject("dxvk").getString("file"))
    }

    @Test fun oneProtonFailingDoesNotStopTheOthers() {
        proton("ge")
        proton("proton-exp")
        val applied = mutableListOf<String>()
        val result = run(g710, applyAuto = { id, file -> if (id == "ge") error("disk full"); applied += "$id $file"; "swapped $id" })
        assertEquals(listOf("proton-exp $old"), applied)
        assertTrue(result!!, result.contains("disk full") && result.contains("swapped proton-exp"))
    }

    @Test fun aChoiceWhoseSavedCopyIsGoneIsKept() {
        proton("proton-exp")
        writeState("proton-exp", JSONObject().put("file", old).put("label", "DXVK 1.10.3").put("protonVersion", "experimental-11").put("auto", true))
        val result = run(full, undoAuto = { id, comp -> ComponentsManager.undoAutoChoice(context, id, comp) })
        assertTrue(result!!, result.contains("its own copy is gone"))
        assertEquals(old, state().getJSONObject("active").getJSONObject("proton-exp").getJSONObject("dxvk").getString("file"))
    }

    @Test fun aProtonUpdateWithNoRuleLeftJustForgetsTheChoice() {
        proton("proton-exp", "experimental-12")
        writeState("proton-exp", JSONObject().put("file", old).put("protonVersion", "experimental-11").put("auto", true))
        run(full, undoAuto = { id, comp -> ComponentsManager.undoAutoChoice(context, id, comp) })
        assertNull(state().getJSONObject("active").optJSONObject("proton-exp")?.optJSONObject("dxvk"))
        assertEquals("bundled DXVK experimental-12", File(protonDir("proton-exp"), "files/lib/wine/dxvk/aarch64-windows/d3d11.dll").readText())
    }

    @Test fun thePlayerCanKeepProtonsOwnDxvk() {
        proton("proton-exp")
        assertNull("nothing chosen when declined", run(g710, keep = true))
        writeState("proton-exp", JSONObject().put("file", old).put("protonVersion", "experimental-11").put("auto", true))
        val undone = mutableListOf<String>()
        run(g710, keep = true, undoAuto = { id, comp -> undone += "$id $comp"; "undone" })
        assertEquals("an earlier automatic choice goes back", listOf("proton-exp dxvk"), undone)
    }

    @Test fun turningTheBridgeOffRestoresProtonsOwn() {
        proton("proton-exp")
        val d3d11 = File(protonDir("proton-exp"), "files/lib/wine/dxvk/aarch64-windows/d3d11.dll")
        writePackage(old, "DXVK 1.10.3")
        val auto: (String, String) -> String = { id, file -> ComponentsManager.swap(context, id, file, auto = true) }
        val undo: (String, String) -> String = { id, comp -> ComponentsManager.undoAutoChoice(context, id, comp) }
        run(g710, applyAuto = auto, undoAuto = undo)
        assertEquals("DXVK 1.10.3", d3d11.readText())
        run(null, bridge = false, applyAuto = auto, undoAuto = undo)
        assertEquals("bundled DXVK experimental-11", d3d11.readText())
        assertNull(state().getJSONObject("active").optJSONObject("proton-exp")?.optJSONObject("dxvk"))
    }

    @Test fun turningTheBridgeOffKeepsThePlayersOwnChoice() {
        proton("proton-exp")
        writeState("proton-exp", JSONObject().put("file", "dxvk-3.1-linux.wcp").put("protonVersion", "experimental-11"))
        assertNull(run(g710, bridge = false))
        assertNull(run(g710, keep = true))
    }

    @Test fun aQueuedAutomaticSwapIsDroppedWhenTheBridgeIsOff() {
        proton("proton-exp")
        val f = File(context.filesDir, "components/state.json")
        f.parentFile!!.mkdirs()
        f.writeText(JSONObject().put("active", JSONObject())
            .put("queued", JSONObject().put("proton-exp", JSONObject()
                .put("dxvk", JSONObject().put("kind", "package").put("file", old).put("auto", true))
                .put("vkd3d", JSONObject().put("kind", "package").put("file", "vkd3d-x.wcp")))).toString())
        assertNull(run(null, bridge = false))
        val q = state().getJSONObject("queued").getJSONObject("proton-exp")
        assertFalse(q.has("dxvk"))
        assertTrue("the player's own queued swap stays", q.has("vkd3d"))
    }

    @Test fun whenAutomaticChoicesAreUndone() {
        val rule = ComponentsManager.bridgeDxvk(g710)
        assertTrue(ComponentsManager.autoDxvkUndoWanted(false, false, null, null))
        assertTrue(ComponentsManager.autoDxvkUndoWanted(true, true, g710, null))
        assertTrue(ComponentsManager.autoDxvkUndoWanted(true, false, full, null))
        assertFalse("failed detection keeps it", ComponentsManager.autoDxvkUndoWanted(true, false, null, null))
        assertFalse(ComponentsManager.autoDxvkUndoWanted(true, false, g710, rule))
    }

    private fun run(
        vk: AppVulkan?,
        bridge: Boolean = true,
        keep: Boolean = false,
        applyAuto: (String, String) -> String = { id, file -> error("apply $id $file") },
        undoAuto: (String, String) -> String = { id, comp -> error("undo $id $comp") },
    ) = ComponentsManager.ensureForcedPackages(
        context, mali,
        lookup = { file, release -> ComponentsManager.CatalogItem(file, "dxvk", release, "https://example.invalid/$file", 4, "sha256:" + "ab".repeat(32)) },
        fetch = {},
        apply = { id, file -> error("non-automatic apply $id $file") },
        vulkan = vk, bridgeSession = bridge, applyAuto = applyAuto, undoAuto = undoAuto, keepProtonDxvk = keep,
    )

    private fun protonDir(name: String) = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/steamapps/common/$name")

    private fun proton(name: String, version: String = "experimental-11") {
        val dir = protonDir(name)
        File(dir, "files/lib/wine").mkdirs()
        File(dir, "proton").writeText("#!/bin/sh\n")
        File(dir, "version").writeText("1 $version\n")
        File(dir, "files/lib/wine/dxvk/aarch64-windows/d3d11.dll").apply {
            parentFile!!.mkdirs()
            writeText("bundled DXVK $version")
        }
    }

    private fun state() = JSONObject(File(context.filesDir, "components/state.json").readText())

    private fun writeState(protonId: String, dxvk: JSONObject) {
        val f = File(context.filesDir, "components/state.json")
        f.parentFile!!.mkdirs()
        f.writeText(JSONObject().put("active", JSONObject().put(protonId, JSONObject().put("dxvk", dxvk)))
            .put("queued", JSONObject()).toString())
    }

    private fun writePackage(file: String, d3d11: String) {
        val dest = File(context.filesDir, "components/packages/$file")
        dest.parentFile!!.mkdirs()
        TarArchiveOutputStream(ZstdOutputStream(dest.outputStream())).use { tar ->
            fun put(name: String, bytes: ByteArray) {
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
            put("./profile.json", JSONObject().put("type", "DXVK-Linux").put("versionName", file.removeSuffix(".wcp")).toString().toByteArray())
            put("./files/lib/wine/dxvk/aarch64-windows/d3d11.dll", d3d11.toByteArray())
        }
    }
}
