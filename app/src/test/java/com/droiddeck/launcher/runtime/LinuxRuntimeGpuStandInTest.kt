package com.droiddeck.launcher.runtime

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
