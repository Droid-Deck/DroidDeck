package com.droiddeck.launcher.session

import android.hardware.display.DisplayManager
import android.view.Display
import android.view.SurfaceHolder
import com.droiddeck.launcher.ui.ExternalOutputScreen
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
@Config(sdk = [31, 34], manifest = Config.NONE, shadows = [ExternalOutputModeTest.ModeDisplay::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ExternalOutputModeTest {
    @Test fun prefersTheLargestModeAtSixtyHertzOverFourKAtThirty() {
        val uhd30 = mode(28, 3840, 2160, 30.000002f)
        val uhd24 = mode(30, 3840, 2160, 24.000002f)
        val qhd60 = mode(31, 2560, 1440, 60.000004f)
        val hd60 = mode(32, 1920, 1080, 60.000004f)
        val hd50 = mode(33, 1920, 1080, 50f)
        val display = display(uhd30, arrayOf(uhd30, uhd24, qhd60, hd60, hd50))

        assertEquals(qhd60.modeId, ExternalOutput.preferredMode(display).modeId)
    }

    @Test fun aTvDoesNotDropToALowResolutionForAFasterRate() {
        val uhd30 = mode(1, 3840, 2160, 30f)
        val qhd60 = mode(2, 2560, 1440, 60f)
        val hd60 = mode(3, 1920, 1080, 60f)
        val sxga75 = mode(4, 1280, 1024, 75f)
        val display = display(uhd30, arrayOf(uhd30, qhd60, hd60, sxga75))

        assertEquals(qhd60.modeId, ExternalOutput.preferredMode(display).modeId)
    }

    @Test fun aHighRefreshMonitorKeepsItsNativeResolutionAboveSixtyHertz() {
        val qhd72 = mode(1, 2560, 1440, 72f)
        val qhd60 = mode(2, 2560, 1440, 60f)
        val hd144 = mode(3, 1920, 1080, 144f)
        val display = display(qhd60, arrayOf(qhd60, qhd72, hd144))

        assertEquals(qhd72.modeId, ExternalOutput.preferredMode(display).modeId)
    }

    @Test fun aDisplayWithoutSixtyHertzStillGetsItsFastestLargestMode() {
        val uhd30 = mode(1, 3840, 2160, 30f)
        val hd30 = mode(2, 1920, 1080, 30f)
        val display = display(hd30, arrayOf(hd30, uhd30))

        assertEquals(uhd30.modeId, ExternalOutput.preferredMode(display).modeId)
    }

    @Test fun fourKIsDrawnAt1440AndScaledByTheSystem() {
        assertEquals(2560 to 1440, ExternalOutput.swapchainSize(mode(1, 3840, 2160, 30f), 720))
        assertEquals(2730 to 1440, ExternalOutput.swapchainSize(mode(2, 4096, 2160, 30f), 720))
        assertEquals(3840 to 2160, ExternalOutput.swapchainSize(mode(3, 3840, 2160, 60f), 2160))
    }

    @Test fun displaysUpTo1440AreDrawnAtTheirOwnSize() {
        assertEquals(null, ExternalOutput.swapchainSize(mode(1, 2560, 1440, 60f), 720))
        assertEquals(null, ExternalOutput.swapchainSize(mode(2, 1920, 1080, 60f), 1080))
    }

    @Test fun keepsTheLargestResolutionWhenEveryModeHasTheSameRate() {
        val hd60 = mode(1, 1920, 1080, 60f)
        val uhd60 = mode(2, 3840, 2160, 60f)
        val display = display(hd60, arrayOf(hd60, uhd60))

        assertEquals(uhd60.modeId, ExternalOutput.preferredMode(display).modeId)
    }

    @Test fun screenRequestsThePreferredModeOnItsWindow() {
        val hd30 = mode(1, 1920, 1080, 30f)
        val hd60 = mode(2, 1920, 1080, 60f)
        val screen = ExternalOutputScreen(RuntimeEnvironment.getApplication(), display(hd30, arrayOf(hd30, hd60)), callback)
            .apply { create() }

        assertEquals(hd60.modeId, screen.window!!.attributes.preferredDisplayModeId)
    }

    @Test fun screenReportsTheActiveRateApartFromTheRequestedOne() {
        val hd30 = mode(1, 1920, 1080, 30f)
        val hd60 = mode(2, 1920, 1080, 60f)
        val screen = ExternalOutputScreen(RuntimeEnvironment.getApplication(), display(hd30, arrayOf(hd30, hd60)), callback)

        assertEquals(30f, screen.hz)
        assertEquals(60f, screen.requestedHz)
    }

    private val callback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) = Unit
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
        override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
    }

    private fun mode(id: Int, width: Int, height: Int, hz: Float): Display.Mode =
        ShadowDisplayManager.ModeBuilder.modeBuilder(id)
            .setWidth(width).setHeight(height).setRefreshRate(hz).build()

    private fun display(current: Display.Mode, modes: Array<Display.Mode>): Display {
        val id = ShadowDisplayManager.addDisplay("w1920dp-h1080dp")
        val display = RuntimeEnvironment.getApplication().getSystemService(DisplayManager::class.java).getDisplay(id)
        shadowOf(display).setFlags(Display.FLAG_PRESENTATION)
        Shadow.extract<ModeDisplay>(display).apply {
            this.current = current
            supported = modes
        }
        return display
    }

    @Implements(Display::class)
    class ModeDisplay : ShadowDisplay() {
        lateinit var current: Display.Mode
        var supported = emptyArray<Display.Mode>()

        @Implementation protected fun getMode(): Display.Mode = current
        @Implementation protected fun getSupportedModes(): Array<Display.Mode> = supported
    }
}
