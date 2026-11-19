package com.droiddeck.launcher.input

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CommunityControlsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun starter() = context.assets.open("community-controls/packs/compact-gamepad.ddcontrols.zip").use { it.readBytes() }
    @Before fun reset() {
        context.getSharedPreferences("input-profiles", Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun archive(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip -> entries.forEach { (name, data) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
        } }
        return out.toByteArray()
    }
    private fun manifest(): JSONObject = ZipInputStream(ByteArrayInputStream(starter())).use {
        it.nextEntry; JSONObject(it.readBytes().toString(Charsets.UTF_8))
    }
    private fun rejects(action: () -> Unit) {
        try { action(); fail("Expected rejected controls") } catch (_: IllegalArgumentException) { }
    }

    @Test fun starterInstallsIndependentNativeGamepadWithoutChangingOtherParts() {
        val original = InputLibraries.read(context)
        val pack = CommunityControls.parse(context, starter())
        val second = CommunityControls.parse(context, starter())
        assertNotEquals(pack.layout.id, second.layout.id)
        assertNotEquals(pack.bindings.id, second.bindings.id)
        val changed = CommunityControls.install(context, pack, InputProfile.GAME, InputRole.PRIMARY)
        assertEquals(pack.layout, changed.layout(InputProfile.GAME, InputRole.PRIMARY))
        assertEquals(InputAction.Native(InputControl.A), changed.preset(changed.profile(InputProfile.GAME).binding(InputRole.PRIMARY)).elements["a"])
        assertEquals(original.profile(InputProfile.DESKTOP), changed.profile(InputProfile.DESKTOP))
        assertEquals(original.profile(InputProfile.GAME).parts[InputRole.PHYSICAL], changed.profile(InputProfile.GAME).parts[InputRole.PHYSICAL])
        assertEquals(original.profile(InputProfile.GAME).parts[InputRole.SECONDARY], changed.profile(InputProfile.GAME).parts[InputRole.SECONDARY])
        assertEquals(changed, InputLibraries.read(context))
    }

    @Test fun packRoundTripPreservesImagesStyleActionsAndClicks() {
        val base = CommunityControls.parse(context, starter())
        val imageName = "roundtrip.png"
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val layout = base.layout.copy(elements = base.layout.elements.map { if (it.id == "a") it.copy(image = imageName, showLabel = false, toggle = true) else it })
        val bindings = base.bindings.copy(elements = base.bindings.elements + ("a" to InputAction.Keys(listOf(29, 30))),
            clicks = mapOf("left-stick" to InputAction.MouseButton(272)))
        val installed = CommunityControls.install(context, CommunityControls.Pack(layout, bindings, mapOf(imageName to png)), InputProfile.GAME, InputRole.PRIMARY)
        val saved = installed.layout(InputProfile.GAME, InputRole.PRIMARY)!!
        val exported = CommunityControls.export(context, saved, installed.preset(installed.profile(InputProfile.GAME).binding(InputRole.PRIMARY)))
        val imported = CommunityControls.parse(context, exported)
        assertEquals(saved.copy(id = imported.layout.id), imported.layout)
        assertEquals(bindings.elements, imported.bindings.elements)
        assertEquals(bindings.clicks, imported.bindings.clicks)
        assertArrayEquals(png, imported.images.values.single())
        assertNotEquals(imageName, saved.elements.first { it.id == "a" }.image)
    }

    @Test fun parserDoesNotChangePreferences() {
        val before = InputLibraries.read(context)
        CommunityControls.parse(context, starter())
        assertEquals(before, InputLibraries.read(context))
    }

    @Test fun rejectsTraversalAndUnexpectedEntries() {
        rejects { CommunityControls.parse(context, archive("../controls.json" to byteArrayOf())) }
        rejects { CommunityControls.parse(context, archive("images/../x.png" to byteArrayOf())) }
        rejects { CommunityControls.parse(context, archive("unrelated" to byteArrayOf())) }
    }

    @Test fun rejectsMissingAndUnreferencedImages() {
        val doc = manifest()
        doc.getJSONObject("layout").getJSONArray("elements").getJSONObject(0).put("image", "missing.png")
        rejects { CommunityControls.parse(context, archive("controls.json" to doc.toString().toByteArray())) }
        rejects { CommunityControls.parse(context, archive("controls.json" to manifest().toString().toByteArray(), "images/unused.png" to byteArrayOf())) }
    }

    @Test fun rejectsFutureFormatsAndInvalidActions() {
        val doc = manifest().put("version", 99)
        rejects { CommunityControls.parse(context, archive("controls.json" to doc.toString().toByteArray())) }
        doc.put("version", 1).getJSONObject("bindings").getJSONObject("elements").put("a", JSONObject().put("type", "shell").put("command", "bad"))
        rejects { CommunityControls.parse(context, archive("controls.json" to doc.toString().toByteArray())) }
    }

    @Test fun importingInvalidNativePackPreservesItsOriginalError() {
        val doc = manifest()
        doc.getJSONObject("layout").getJSONArray("elements").getJSONObject(0).put("image", "missing.png")
        val old = CommunityControls.parse(context, starter())
        val error = assertThrows(IllegalArgumentException::class.java) {
            CommunityControls.importFile(context, archive("controls.json" to doc.toString().toByteArray()), old.layout, old.bindings)
        }
        assertEquals("Missing or unexpected button images.", error.message)
    }

    @Test fun rejectsCorruptImages() {
        val doc = manifest()
        doc.getJSONObject("layout").getJSONArray("elements").getJSONObject(0).put("image", "invalid.png")
        rejects { CommunityControls.parse(context, archive("controls.json" to doc.toString().toByteArray(), "images/invalid.png" to byteArrayOf(0, 1, 2))) }
    }

    @Test fun boundedReaderRejectsOversizeBeforeBufferingAllInput() {
        rejects { CommunityControls.readLimited(ByteArrayInputStream(ByteArray(8192)), 16) }
        assertEquals(16, CommunityControls.readLimited(ByteArrayInputStream(ByteArray(16)), 16).size)
        rejects { CommunityControls.parse(context, archive("controls.json" to ByteArray(2_000_001))) }
    }

    @Test fun bundledCatalogMatchesPackAndRejectsExternalPaths() {
        val entries = CommunityControls.bundled(context)
        assertTrue(entries.isNotEmpty())
        entries.forEach {
            assertEquals(it.name, CommunityControls.download(context, it).layout.name)
            val preview = requireNotNull(CommunityControls.preview(context, it))
            assertTrue(maxOf(preview.width, preview.height) <= 256)
            preview.recycle()
        }
        rejects { CommunityControls.download(context, entries.first().copy(sha256 = "0".repeat(64))) }
        val doc = JSONObject(context.assets.open("community-controls/catalog.json").bufferedReader().use { it.readText() })
        doc.getJSONArray("controls").getJSONObject(0).put("file", "https://example.com/pack.zip")
        rejects { CommunityControls.catalog(doc.toString()) }
        doc.getJSONArray("controls").getJSONObject(0).put("file", "packs/../../other.ddcontrols.zip")
        rejects { CommunityControls.catalog(doc.toString()) }
    }
}
