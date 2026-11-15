package com.droiddeck.launcher.input

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PointerSpaceTest {
    private val phonePicture = RectF(0f, 896f, 1080f, 1503f)

    @Test fun theGlassesMakeTheWholeViewThePointerArea() {
        assertEquals(RectF(0f, 0f, 1080f, 2400f),
            PointerSpace.area(1080, 2400, glassesOwnOutput = true, phonePicture))
    }

    @Test fun theViewThatShowsThePictureUsesItsPictureRect() {
        assertSame(phonePicture, PointerSpace.area(2400, 1504, glassesOwnOutput = false, phonePicture))
    }

    @Test fun aViewWithoutSizeHasNoPointerArea() {
        assertNull(PointerSpace.area(0, 2400, glassesOwnOutput = true, phonePicture))
        assertNull(PointerSpace.area(1080, 0, glassesOwnOutput = false, phonePicture))
    }
}
