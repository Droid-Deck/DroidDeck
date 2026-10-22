package com.droiddeck.launcher.glasses

import android.graphics.Bitmap
import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.MPImage
import java.nio.ByteBuffer

internal class HandImageBuffer {
    companion object {
        const val PREPROCESSING = "raw-grayscale-rgb"
    }
    private var pixels = ByteBuffer.allocateDirect(0)
    private var source = IntArray(0)
    private var rgb = ByteArray(0)

    private fun allocateRgb(count: Int) {
        if (rgb.size != count * 3) {
            rgb = ByteArray(count * 3)
            pixels = ByteBuffer.allocateDirect(rgb.size)
        }
    }

    fun image(grayscale: ByteArray, width: Int, height: Int): MPImage {
        require(width > 0 && height > 0 && width.toLong() * height == grayscale.size.toLong())
        allocateRgb(grayscale.size)
        var index = 0
        for (value in grayscale) {
            rgb[index++] = value
            rgb[index++] = value
            rgb[index++] = value
        }
        return image(width, height)
    }

    fun image(grayscale: Bitmap): MPImage {
        val count = grayscale.width * grayscale.height
        if (source.size != count) source = IntArray(count)
        allocateRgb(count)
        grayscale.getPixels(source, 0, grayscale.width, 0, 0, grayscale.width, grayscale.height)
        var index = 0
        for (pixel in source) {
            val value = (pixel ushr 16).toByte()
            rgb[index++] = value
            rgb[index++] = value
            rgb[index++] = value
        }
        return image(grayscale.width, grayscale.height)
    }

    private fun image(width: Int, height: Int): MPImage {
        pixels.clear()
        pixels.put(rgb)
        pixels.rewind()
        // MediaPipe applies the model metadata's float normalization to these RGB bytes.
        return ByteBufferImageBuilder(pixels, width, height, MPImage.IMAGE_FORMAT_RGB).build()
    }
}
