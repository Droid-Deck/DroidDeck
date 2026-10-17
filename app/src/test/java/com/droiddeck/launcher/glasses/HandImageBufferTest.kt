package com.droiddeck.launcher.glasses

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.MPImage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class HandImageBufferTest {
    private fun bitmap(vararg values: Int) = Bitmap.createBitmap(values.size, 1, Bitmap.Config.ARGB_8888).apply {
        setPixels(values.map { Color.rgb(it, it, it) }.toIntArray(), 0, values.size, 0, 0, values.size, 1)
    }

    private fun values(buffer: HandImageBuffer, bitmap: Bitmap): List<Int> = values(buffer.image(bitmap))

    private fun values(image: MPImage): List<Int> = image.use {
        assertEquals(MPImage.IMAGE_FORMAT_RGB, image.containedImageProperties.single().imageFormat)
        val pixels = ByteBufferExtractor.extract(image)
        assertTrue(pixels.isDirect)
        assertEquals(image.width * image.height * 3, pixels.remaining())
        List(image.width * image.height) {
            val red = pixels.get().toInt() and 255
            assertEquals(red, pixels.get().toInt() and 255)
            assertEquals(red, pixels.get().toInt() and 255)
            red
        }
    }

    @Test fun liveDefaultUsesFullModelAndPreservesCameraPixelCoordinatesAndExposure() {
        assertEquals(HandModel.FULL, HandModel.DEFAULT)
        val input = byteArrayOf(32, 64, 96, 128.toByte(), 160.toByte(), 192.toByte())
        val original = input.copyOf()
        val image = HandImageBuffer().image(input, 3, 2)
        assertEquals(3, image.width); assertEquals(2, image.height)
        assertEquals(listOf(32, 64, 96, 128, 160, 192), values(image))
        assertArrayEquals(original, input)
    }

    @Test fun directCameraAndRawBitmapInputsMatchAcrossEveryGrayValueAndFrameSize() {
        val buffer = HandImageBuffer()
        for ((width, height) in listOf(16 to 16, 3 to 2, 2 to 3, 1 to 1)) {
            val input = ByteArray(width * height) { ((it * 73 + 32) and 255).toByte() }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(input.map { (it.toInt() and 255).let { value -> Color.rgb(value, value, value) } }.toIntArray(),
                    0, width, 0, 0, width, height)
            }
            try {
                val expected = input.map { it.toInt() and 255 }
                assertEquals(expected, values(buffer.image(input, width, height)))
                assertEquals(expected, values(buffer.image(bitmap)))
            } finally { bitmap.recycle() }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun cameraDimensionsMustMatchTheGrayscaleFrame() {
        HandImageBuffer().image(ByteArray(5), 3, 2)
    }

    @Test fun resizedAndSuccessiveFramesDoNotRetainOldPixelsOrExposure() {
        val buffer = HandImageBuffer()
        for (input in listOf(intArrayOf(32, 96, 160), intArrayOf(64, 128, 192), intArrayOf(0, 255))) {
            val bitmap = bitmap(*input)
            try {
                assertEquals(input.toList(), values(buffer, bitmap))
            } finally { bitmap.recycle() }
        }
    }

    @Test fun closingInferenceImagesDoesNotRecycleReusableCameraBitmap() {
        val bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888)
        val buffer=HandImageBuffer()
        repeat(3) { frame ->
            bitmap.eraseColor(if(frame%2==0) Color.WHITE else Color.BLACK)
            buffer.image(bitmap).use { image ->
                assertEquals(2,image.width);assertEquals(2,image.height)
                val pixels=ByteBufferExtractor.extract(image)
                assertEquals(if(frame%2==0) 255 else 0,pixels.get(0).toInt() and 255)
            }
            assertFalse(bitmap.isRecycled)
        }
        bitmap.recycle()
    }
}
