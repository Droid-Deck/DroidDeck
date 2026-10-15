package com.droiddeck.launcher.glasses

import android.hardware.display.DisplayManager
import android.view.Display
import android.view.SurfaceHolder
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDisplay
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], manifest = Config.NONE, shadows = [GlassesScreenTest.ModeDisplay::class])
@LooperMode(LooperMode.Mode.PAUSED)
class GlassesScreenTest {
    @Test fun switchingBetween2dAndStereoRefreshesTheModePreferenceEvenAtTheSameHz() {
        val flat = mode(1, 1920, 1200, 60f)
        val stereo = mode(2, 3840, 1200, 60f)
        val (screen, panel) = screen(flat, arrayOf(flat, stereo), 60)
        assertEquals(flat.modeId, screen.window!!.attributes.preferredDisplayModeId)

        panel.current = stereo
        screen.prefer(60)
        assertEquals(stereo.modeId, screen.window!!.attributes.preferredDisplayModeId)

        panel.current = flat
        screen.prefer(60)
        assertEquals(flat.modeId, screen.window!!.attributes.preferredDisplayModeId)
    }

    @Test fun refreshPreferenceKeepsBothDimensionsOfTheActiveStereoMode() {
        val stereo1080 = mode(1, 3840, 1080, 90f)
        val stereo1200 = mode(2, 3840, 1200, 60f)
        val (screen, _) = screen(stereo1200, arrayOf(stereo1080, stereo1200), 90)

        assertEquals(stereo1200.modeId, screen.window!!.attributes.preferredDisplayModeId)
    }

    @Test fun refreshChangesStillChooseTheNearestRateAtTheCurrentResolution() {
        val stereo60 = mode(1, 3840, 1200, 60f)
        val stereo90 = mode(2, 3840, 1200, 90f)
        val flat120 = mode(3, 1920, 1200, 120f)
        val (screen, _) = screen(stereo60, arrayOf(stereo60, stereo90, flat120), 60)

        screen.prefer(120)
        assertEquals(stereo90.modeId, screen.window!!.attributes.preferredDisplayModeId)
    }

    private fun mode(id: Int, width: Int, height: Int, hz: Float): Display.Mode =
        ShadowDisplayManager.ModeBuilder.modeBuilder(id)
            .setWidth(width).setHeight(height).setRefreshRate(hz).build()

    private fun screen(current: Display.Mode, modes: Array<Display.Mode>, hz: Int): Pair<GlassesScreen, ModeDisplay> {
        val context = RuntimeEnvironment.getApplication()
        val id = ShadowDisplayManager.addDisplay("w1920dp-h1200dp")
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(id)
        shadowOf(display).setFlags(Display.FLAG_PRESENTATION)
        val panel = Shadow.extract<ModeDisplay>(display).apply {
            this.current = current
            supported = modes
        }
        val callback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        }
        return GlassesScreen(context, display, hz, callback).apply { create() } to panel
    }

    @Implements(Display::class)
    class ModeDisplay : ShadowDisplay() {
        lateinit var current: Display.Mode
        var supported = emptyArray<Display.Mode>()

        @Implementation protected fun getMode(): Display.Mode = current
        @Implementation protected fun getSupportedModes(): Array<Display.Mode> = supported
    }
}
