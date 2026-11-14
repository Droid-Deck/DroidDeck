package com.droiddeck.launcher.input

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InputButtonIconsTest {
    @Test fun commandSymbolsRenderUsingTheButtonTintAndHaveReadableNames() {
        val context = RuntimeEnvironment.getApplication()
        for (code in listOf(InputButtonIcons.MENU, InputButtonIcons.KEYBOARD, InputButtonIcons.STEAM, InputButtonIcons.QUICK_ACCESS)) {
            val drawable = InputButtonIcons.drawable(context, code)
            assertNotNull(drawable)
            assertNotEquals(code.toString(), InputButtonIcons.label(context, code))
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            try {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GREEN }
                assertTrue(InputButtonIcons.draw(Canvas(bitmap), code, 32f, 32f, 48f, paint, drawable))
                val pixels = IntArray(64 * 64)
                bitmap.getPixels(pixels, 0, 64, 0, 0, 64, 64)
                assertTrue(pixels.count { Color.alpha(it) > 0 } > 30)
                assertTrue(pixels.filter { Color.alpha(it) > 0 }.all { Color.green(it) > 0 && Color.red(it) == 0 && Color.blue(it) == 0 })
            } finally { bitmap.recycle() }
        }
    }
}
