package com.droiddeck.launcher.session

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class GpuClockPinTest {
    private val context = RuntimeEnvironment.getApplication()

    private val calls = mutableListOf<Boolean>()
    private val node = GpuClockPin.supported
    private val turbo = GpuClockPin.turbo

    @After fun stop() {
        GpuClockPin.stop(context)
        GpuClockPin.supported = node
        GpuClockPin.turbo = turbo
        SessionPrefs.setGpuClockPin(context, false)
    }

    private fun fakeGpu() {
        GpuClockPin.supported = true
        GpuClockPin.turbo = { calls += it }
    }

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

    @Test fun aBoostHoldsTheClockOnlyWhileItLasts() {
        fakeGpu()
        assertEquals("gpu clock: governed", GpuClockPin.start(context))
        assertTrue(GpuClockPin.boost(true))
        assertEquals("gpu clock: held at its top", GpuClockPin.sync(context))
        assertFalse(GpuClockPin.boost(true))
        assertTrue(GpuClockPin.boost(false))
        assertEquals("gpu clock: governed", GpuClockPin.sync(context))
        assertEquals(listOf(true, false), calls)
    }

    @Test fun endingABoostKeepsTheHoldTheUserTurnedOn() {
        fakeGpu()
        SessionPrefs.setGpuClockPin(context, true)
        GpuClockPin.start(context)
        GpuClockPin.boost(true)
        GpuClockPin.sync(context)
        GpuClockPin.boost(false)
        assertEquals("gpu clock: held at its top", GpuClockPin.sync(context))
        assertFalse(false in calls)
    }

    @Test fun aBoostOutsideASessionDoesNothing() {
        fakeGpu()
        GpuClockPin.boost(true)
        assertNull(GpuClockPin.sync(context))
        assertTrue(calls.isEmpty())
    }

    @Test fun aBoostDoesNotOutliveItsSession() {
        fakeGpu()
        GpuClockPin.start(context)
        GpuClockPin.boost(true)
        GpuClockPin.sync(context)
        GpuClockPin.stop(context)
        assertEquals("gpu clock: governed", GpuClockPin.start(context))
        assertEquals(listOf(true, false), calls)
    }

    @Test fun aBoostWithoutTheGpuNodeLeavesTheClockGoverned() {
        GpuClockPin.supported = false
        GpuClockPin.turbo = { calls += it }
        GpuClockPin.start(context)
        GpuClockPin.boost(true)
        assertEquals("gpu clock: no /dev/kgsl-3d0, left governed", GpuClockPin.sync(context))
        assertTrue(calls.isEmpty())
    }
}
