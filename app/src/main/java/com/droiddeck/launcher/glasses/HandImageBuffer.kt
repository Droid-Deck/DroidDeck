package com.droiddeck.launcher.glasses

import android.graphics.Bitmap
import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.MPImage
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class HandImageBuffer {
    private var pixels = ByteBuffer.allocateDirect(0)
    fun image(bitmap: Bitmap): MPImage {
        val size = bitmap.width * bitmap.height * 4
        if (pixels.capacity() != size) pixels = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        pixels.clear()
        bitmap.copyPixelsToBuffer(pixels)
        pixels.rewind()
        // Closing a BitmapImageBuilder result recycles its input bitmap.
        return ByteBufferImageBuilder(pixels, bitmap.width, bitmap.height, MPImage.IMAGE_FORMAT_RGBA).build()
    }
}
