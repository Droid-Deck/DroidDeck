package com.droiddeck.launcher.glasses

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mediapipe.framework.image.ByteBufferExtractor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class HandImageBufferTest {
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
