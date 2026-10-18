package com.droiddeck.launcher.glasses

import android.hardware.display.DisplayManager
import android.view.Display
import android.view.SurfaceHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    @Test fun recoveryCoversTheExistingRenderSurfaceWithoutReplacingIt() {
        val flat=mode(1,1920,1200,60f)
        val (screen,_)=screen(flat,arrayOf(flat),60)
        val holder=screen.holder
        val root=screen.surfaceView.parent as android.view.ViewGroup
        assertTrue(root.indexOfChild(screen.trackingRecovery)>root.indexOfChild(screen.surfaceView))
        screen.trackingRecovery.update(TrackingRecovery.LOST,false)
        assertEquals(android.view.View.VISIBLE,screen.trackingRecovery.visibility)
        screen.trackingRecovery.update(null,false)
        assertEquals(android.view.View.GONE,screen.trackingRecovery.visibility)
        assertTrue(holder===screen.holder)
    }

    @Test fun nativeThirtyHzMustMatchBothDimensionsOfTheCurrentEyeLayout() {
        val flat60 = mode(1, 1920, 1200, 60f)
        val flat30 = mode(2, 1920, 1200, 29.97f)
        val stereo60 = mode(3, 3840, 1200, 60f)
        val stereo30 = mode(4, 3840, 1200, 30f)
        val short30 = mode(5, 3840, 1080, 30f)
        val (screen, panel) = screen(flat60, arrayOf(flat60, flat30, stereo60, short30), 30)
        assertTrue(Glasses.supports30Hz(screen.display))
        assertEquals(flat30.modeId, screen.window!!.attributes.preferredDisplayModeId)

        panel.current = stereo60
        screen.prefer(30)
        assertFalse(Glasses.supports30Hz(screen.display))
        assertEquals(stereo60.modeId, screen.window!!.attributes.preferredDisplayModeId)

        panel.supported += stereo30
        screen.prefer(30)
        assertTrue(Glasses.supports30Hz(screen.display))
        assertEquals(stereo30.modeId, screen.window!!.attributes.preferredDisplayModeId)
    }

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

    @Test fun explicitResolutionChangesBothDimensionsWithoutSwitchingEyeLayout() {
        val flat1080 = mode(1, 1920, 1080, 90f)
        val flat1200 = mode(2, 1920, 1200, 90f)
        val stereo1080 = mode(3, 3840, 1080, 90f)
        val stereo1200 = mode(4, 3840, 1200, 90f)
        val (screen, panel) = screen(flat1200, arrayOf(flat1080, flat1200, stereo1080, stereo1200), 90)
        screen.prefer(90, GlassesResolution(1920, 1080))
        assertEquals(flat1080.modeId, screen.window!!.attributes.preferredDisplayModeId)
        panel.current = stereo1200
        screen.prefer(90, GlassesResolution(1920, 1080))
        assertEquals(stereo1080.modeId, screen.window!!.attributes.preferredDisplayModeId)
        screen.prefer(90, GlassesResolution(1920, 1200))
        assertEquals(stereo1200.modeId, screen.window!!.attributes.preferredDisplayModeId)
    }

    @Test fun unavailableResolutionKeepsTheCurrentModeUntilHardwareConfirmsTheChange() {
        val stereo60 = mode(1, 3840, 1200, 60f)
        val stereo90 = mode(2, 3840, 1200, 90f)
        val flat1080 = mode(3, 1920, 1080, 90f)
        val (screen, _) = screen(stereo60, arrayOf(stereo60, stereo90, flat1080), 60)
        screen.prefer(90, GlassesResolution(1920, 1080))
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
