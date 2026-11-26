package com.droiddeck.launcher.wayland

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.util.ReflectionHelpers

class CompositorHostTest {
    private fun started(on: Boolean) = ReflectionHelpers.setStaticField(CompositorHost::class.java, "started", on)

    @After fun reset() = started(false)

    @Test fun aNewSessionDoesNotAttachToACompositorWhoseDeviceIsUnusable() {
        started(true)
        assertTrue(CompositorHost.unusableForNewSession(sessionRunning = false) { true })
    }

    @Test fun aNewSessionStillAttachesToAHealthyCompositor() {
        started(true)
        assertFalse(CompositorHost.unusableForNewSession(sessionRunning = false) { false })
    }

    @Test fun aRunningSessionIsNeverRestartedWhenItsSurfaceComesBack() {
        started(true)
        assertFalse(CompositorHost.unusableForNewSession(sessionRunning = true) { true })
    }

    @Test fun aFreshProcessStartsItsCompositorWithoutAskingTheDevice() {
        var asked = 0
        assertFalse(CompositorHost.unusableForNewSession(sessionRunning = false) { asked++; true })
        assertEquals(0, asked)
    }
}
