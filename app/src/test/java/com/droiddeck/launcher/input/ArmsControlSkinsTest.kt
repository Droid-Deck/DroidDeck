package com.droiddeck.launcher.input

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArmsControlSkinsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() { context.getSharedPreferences("input-profiles", Context.MODE_PRIVATE).edit().clear().commit() }
    private fun base() = CommunityControls.parse(context, context.assets.open("community-controls/packs/compact-gamepad.ddcontrols.zip").use { it.readBytes() })
    private fun png(color: Int, size: Int = 16): ByteArray {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle() }.toByteArray()
    }
    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z -> files.forEach { (name, data) -> z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() } }
        return bytes.toByteArray()
    }
    @Test fun skinAppliesToNativeTargetsAndPreservesBindingsAndGeometry() {
        val old = base()
        val skin = ArmsControlSkins.parse(context, zip("folder/ic_controller_cross_button.png" to png(Color.RED),
            "square.png" to png(Color.BLUE)), old.layout, old.bindings, "Community skin")
        assertEquals(old.bindings.elements, skin.bindings.elements)
        assertEquals(old.layout.elements.map { it.x to it.y }, skin.layout.elements.map { it.x to it.y })
        assertEquals("skin_cross.png", skin.layout.elements.first { it.id == "a" }.image)
        assertEquals("skin_square.png", skin.layout.elements.first { it.id == "x" }.image)
        assertEquals("", skin.layout.elements.first { it.id == "b" }.image)
        assertNotEquals(old.layout.id, skin.layout.id)
    }
    @Test fun separateStickArtworkSurvivesInstallExportAndReimport() {
        val old = base()
        val skin = ArmsControlSkins.parse(context, zip("analog_base.png" to png(Color.RED),
            "analog_stick_left.png" to png(Color.GREEN), "analog_stick_right.png" to png(Color.BLUE)), old.layout, old.bindings, "Stick skin")
        val left = skin.layout.elements.first { it.id == "left-stick" }
        val right = skin.layout.elements.first { it.id == "right-stick" }
        assertEquals(left.image, right.image)
        assertNotEquals(left.stickImage, right.stickImage)
        val installed = CommunityControls.install(context, skin, InputProfile.GAME, InputRole.PRIMARY)
        val layout = installed.layout(InputProfile.GAME, InputRole.PRIMARY)!!
        val bindings = installed.preset(installed.profile(InputProfile.GAME).binding(InputRole.PRIMARY))
        val pack = CommunityControls.parse(context, CommunityControls.export(context, layout, bindings))
        assertEquals(3, pack.images.size)
        assertNotEquals("", pack.layout.elements.first { it.id == "left-stick" }.stickImage)
        val artwork = ArmsControlSkins.export(context, layout, bindings)
        val imported = ArmsControlSkins.parse(context, artwork, old.layout, old.bindings, "Shared skin")
        assertEquals(4, imported.images.size)
        assertEquals(old.bindings.elements, imported.bindings.elements)
    }
    @Test fun downsamplesLargeImagesBeforeStoringThem() {
        val old = base()
        val skin = ArmsControlSkins.parse(context, zip("cross.png" to png(Color.RED, 2048)), old.layout, old.bindings, "Large skin")
        val bytes = skin.images.values.single()
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertTrue(bitmap.width <= 512 && bitmap.height <= 512)
        bitmap.recycle()
    }
    @Test fun rejectsTraversalDuplicateButtonsAndArchivesWithoutMatchingImages() {
        val old = base()
        for (files in listOf(arrayOf("../cross.png" to png(Color.RED)),
            arrayOf("cross.png" to png(Color.RED), "ic_controller_cross_button.png" to png(Color.RED)),
            arrayOf("readme.txt" to byteArrayOf()), arrayOf("cross.png" to byteArrayOf(1, 2))))
            assertThrows(IllegalArgumentException::class.java) { ArmsControlSkins.parse(context, zip(*files), old.layout, old.bindings, "Invalid") }
    }
}
