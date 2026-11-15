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
    @Test fun screenUsesTheConnectedModeWithoutAskingForAnother() {
        val hd30 = mode(1, 1920, 1080, 30f)
        val hd60 = mode(2, 1920, 1080, 60f)
        val screen = ExternalOutputScreen(RuntimeEnvironment.getApplication(), display(hd30, arrayOf(hd30, hd60)), callback)
            .apply { create() }

        assertEquals(0, screen.window!!.attributes.preferredDisplayModeId)
    }

    @Test fun screenReportsTheConnectedModeRate() {
        val hd30 = mode(1, 1920, 1080, 30f)
        val screen = ExternalOutputScreen(RuntimeEnvironment.getApplication(), display(hd30, arrayOf(hd30)), callback)

        assertEquals(30f, screen.hz)
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
