package com.droiddeck.launcher.session

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class XrModelResourcesTest {
    @get:Rule val temp = TemporaryFolder()
    private fun model(): File = temp.newFolder().apply {
        File(this, "controller.json").writeText("""{"components":{"body":{"filename":"part.obj"}}}""")
        File(this, "part.obj").writeText("mtllib material.mtl\nv 0 0 0\n")
        File(this, "material.mtl").writeText("map_Kd texture.png\n")
        File(this, "texture.png").writeBytes(byteArrayOf(1))
    }

    @Test fun missingGeometryOrTextureCannotBeSelectedAsACompleteModel() {
        val directory = model()
        assertTrue(XrModelResources.model(directory, "controller"))
        File(directory, "texture.png").delete()
        assertFalse(XrModelResources.model(directory, "controller"))
        File(directory, "texture.png").writeBytes(byteArrayOf(1))
        File(directory, "part.obj").delete()
        assertFalse(XrModelResources.model(directory, "controller"))
    }

    @Test fun anEmptyDescriptorCannotMakeTheControllerVisible() {
        val directory = model()
        File(directory, "controller.json").writeText("{}")
        assertFalse(XrModelResources.model(directory, "controller"))
    }

    @Test fun descriptorPathsCannotEscapeTheModelDirectory() {
        val directory = model()
        File(directory, "controller.json").writeText("""{"components":{"body":{"filename":"../outside.obj"}}}""")
        assertFalse(XrModelResources.model(directory, "controller"))
    }
}
