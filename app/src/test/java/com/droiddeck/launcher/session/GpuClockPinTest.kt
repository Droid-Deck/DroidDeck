package com.droiddeck.launcher.session

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class GpuClockPinTest {
    private val context = RuntimeEnvironment.getApplication()

    @After fun stop() { GpuClockPin.stop(context) }

    @Test fun aToggleOutsideASessionSaysNothing() {
        SessionPrefs.setGpuClockPin(context, true)
        assertNull(GpuClockPin.sync(context))
    }

    @Test fun aToggleMidSessionReportsTheHoldWithTheStartWording() {
        SessionPrefs.setGpuClockPin(context, false)
        val started = GpuClockPin.start(context)
        assertEquals("gpu clock: governed", started)
        SessionPrefs.setGpuClockPin(context, true)
        val held = GpuClockPin.sync(context)
        assertEquals("gpu clock: no /dev/kgsl-3d0, left governed", held)
        SessionPrefs.setGpuClockPin(context, false)
        assertEquals(started, GpuClockPin.sync(context))
    }
}
