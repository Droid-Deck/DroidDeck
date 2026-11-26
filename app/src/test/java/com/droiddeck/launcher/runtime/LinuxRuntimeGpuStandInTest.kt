package com.droiddeck.launcher.runtime

import android.app.Application
import com.droiddeck.launcher.session.SessionService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class LinuxRuntimeGpuStandInTest {
    private val kgsl = "/dev/kgsl-3d0"

    private fun standIn(nodes: Set<String>, maliGuestDriver: Boolean, bridge: Boolean): String? =
        LinuxRuntime.gpuStandIn({ it in nodes }, { maliGuestDriver }, { bridge })

    @Test fun maliGuestDriverGetsTheMaliNode() {
        assertEquals(LinuxRuntime.MALI_DEVICE, standIn(setOf(LinuxRuntime.MALI_DEVICE), maliGuestDriver = true, bridge = false))
    }

    @Test fun maliWithoutAGuestDriverOrBridgeGetsNoNode() {
        assertNull(standIn(setOf(LinuxRuntime.MALI_DEVICE, "/dev/dri/renderD128"), maliGuestDriver = false, bridge = false))
    }

    @Test fun maliGuestDriverWithoutTheMaliNodeGetsNoNode() {
        assertNull(standIn(setOf("/dev/dri/renderD128"), maliGuestDriver = true, bridge = false))
    }

    @Test fun kgslStaysTheStandInWheneverItExists() {
        assertEquals(kgsl, standIn(setOf(kgsl, LinuxRuntime.MALI_DEVICE), maliGuestDriver = true, bridge = true))
        assertEquals(kgsl, standIn(setOf(kgsl), maliGuestDriver = false, bridge = false))
    }

    @Test fun bridgeKeepsItsOwnNodeChoice() {
        assertEquals("/dev/dri/renderD129", standIn(setOf("/dev/dri/renderD129"), maliGuestDriver = false, bridge = true))
        assertEquals(LinuxRuntime.MALI_DEVICE, standIn(setOf(LinuxRuntime.MALI_DEVICE), maliGuestDriver = false, bridge = true))
    }

    @Test fun guestDriverIsNotAskedWhenKgslExists() {
        var asked = false
        LinuxRuntime.gpuStandIn({ it == kgsl }, { asked = true; true }, { asked = true; true })
        assertEquals(false, asked)
    }

    @Test fun venusGetsTheMaliNodeOnlyOnDesktop() {
        assertTrue(LinuxRuntime.maliGuestDriver(false, { true }, SessionService.MODE_DESKTOP))
        assertFalse(LinuxRuntime.maliGuestDriver(false, { true }, SessionService.MODE_STEAM))
        assertFalse(LinuxRuntime.maliGuestDriver(false, { true }, SessionService.MODE_RUN))
        assertFalse(LinuxRuntime.maliGuestDriver(false, { false }, SessionService.MODE_DESKTOP))
    }

    @Test fun openDriverPairGetsTheMaliNodeInEveryMode() {
        for (mode in listOf(SessionService.MODE_STEAM, SessionService.MODE_DESKTOP, SessionService.MODE_RUN)) {
            assertTrue(LinuxRuntime.maliGuestDriver(true, { false }, mode))
        }
    }

    @Test fun venusIsNotAskedOutsideDesktop() {
        var asked = false
        LinuxRuntime.maliGuestDriver(false, { asked = true; true }, SessionService.MODE_STEAM)
        assertFalse(asked)
    }
}
