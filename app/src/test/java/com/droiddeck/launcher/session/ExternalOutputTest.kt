package com.droiddeck.launcher.session

import android.hardware.display.DisplayManager
import android.view.Display
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ExternalOutputTest {
    @Test fun selectsAPresentationDisplayOtherThanThePhone() {
        val monitor = display(Display.FLAG_PRESENTATION)
        val all = displays()

        assertEquals(monitor.displayId, ExternalOutput.select(all, emptySet())?.displayId)
    }

    @Test fun ignoresDisplaysWithoutThePresentationFlag() {
        display(0)

        assertNull(ExternalOutput.select(displays(), emptySet()))
    }

    @Test fun neverSelectsTheDefaultDisplay() {
        val phone = manager().getDisplay(Display.DEFAULT_DISPLAY)
        shadowOf(phone).setFlags(Display.FLAG_PRESENTATION)

        assertNull(ExternalOutput.select(listOf(phone), emptySet()))
    }

    @Test fun skipsExcludedDisplaysSuchAsTheGlassesOrTheActivityDisplay() {
        val glasses = display(Display.FLAG_PRESENTATION)
        val monitor = display(Display.FLAG_PRESENTATION)

        assertEquals(monitor.displayId, ExternalOutput.select(displays(), setOf(glasses.displayId))?.displayId)
        assertNull(ExternalOutput.select(displays(), setOf(glasses.displayId, monitor.displayId)))
    }

    private fun display(flags: Int): Display {
        val id = ShadowDisplayManager.addDisplay("w1920dp-h1080dp")
        return manager().getDisplay(id).also { shadowOf(it).setFlags(flags) }
    }

    private fun displays(): List<Display> = manager().displays.toList()

    private fun manager(): DisplayManager =
        RuntimeEnvironment.getApplication().getSystemService(DisplayManager::class.java)
}
