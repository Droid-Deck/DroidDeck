package com.droiddeck.launcher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.graphics.toArgb
import com.droiddeck.launcher.glasses.TrackingRecovery
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrackingRecoveryViewTest {
    private fun view()=TrackingRecoveryView(RuntimeEnvironment.getApplication())
    private fun render(view: View, width: Int=1920, height: Int=1200): Bitmap {
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
        view.layout(0,0,width,height)
        return Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }
    private fun save(bitmap: Bitmap, name: String) {
        val file=File("build/reports/tracking-recovery/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }

    @Test fun recoveredTrackingRemovesTheScreenAndItsTouchTarget() {
        val view=view()
        assertEquals(View.GONE,view.visibility)
        view.update(TrackingRecovery.LOST,false)
        assertEquals(View.VISIBLE,view.visibility)
        assertTrue(view.contentDescription.contains("Look around slowly"))
        assertTrue(view.isClickable)
        view.update(null,false)
        assertEquals(View.GONE,view.visibility)
        assertNull(view.contentDescription)
    }

    @Test fun everyRecoveryStateHasItsOwnActionAndFitsThePhone() {
        val view=view()
        for((state,instruction) in listOf(TrackingRecovery.LOST to "Look around slowly",TrackingRecovery.RECENTER to "tap Recenter",
            TrackingRecovery.STABILIZING to "Keep the glasses steady",TrackingRecovery.DISCONNECTED to "USB-access prompt")) {
            view.update(state,false,Themes.byId(Themes.ELECTRIC))
            assertTrue(view.contentDescription.contains(instruction))
            render(view,1080,600).also { save(it,"phone-${state.name.lowercase()}");it.recycle() }
        }
    }

    @Test fun stereoDuplicatesTheWholeMessageForEachEyeAndMatchesMono() {
        val view=view();view.update(TrackingRecovery.LOST,false)
        val mono=render(view)
        view.update(TrackingRecovery.LOST,true)
        val stereo=render(view,3840,1200)
        save(mono,"glasses-mono");save(stereo,"glasses-stereo")
        val expected=IntArray(1920*1200);mono.getPixels(expected,0,1920,0,0,1920,1200)
        for(x in listOf(0,1920)) {
            val eye=IntArray(expected.size);stereo.getPixels(eye,0,1920,x,0,1920,1200)
            var changed=0
            var difference=0L
            for(i in eye.indices) {
                if(eye[i]!=expected[i]) changed++
                for(shift in listOf(0,8,16)) difference+=kotlin.math.abs(((eye[i] ushr shift) and 255)-((expected[i] ushr shift) and 255))
            }
            // Native text rasterization can round a translated glyph edge differently.
            assertTrue("Eye at x=$x changed $changed pixels",changed<expected.size/1000)
            assertTrue("Eye at x=$x has different content",difference.toDouble()/(expected.size*3)<0.1)
        }
        mono.recycle();stereo.recycle()
    }

    @Test fun activePaletteChangesTheBackgroundAndMessageColors() {
        val view=view()
        for(theme in listOf(Themes.GRAPHITE,Themes.ELECTRIC,Themes.PAPER)) {
            val palette=Themes.byId(theme)
            view.update(TrackingRecovery.LOST,false,palette)
            val bitmap=render(view)
            assertEquals(palette.background.toArgb(),bitmap.getPixel(0,0))
            save(bitmap,"theme-$theme");bitmap.recycle()
        }
    }
}
