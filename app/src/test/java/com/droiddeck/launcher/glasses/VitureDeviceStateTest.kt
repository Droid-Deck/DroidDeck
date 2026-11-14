package com.droiddeck.launcher.glasses

import org.junit.Assert.*
import org.junit.Test

class VitureDeviceStateTest {
    @Test fun ultrawideDoesNotMasqueradeAsStereo() {
        for (code in listOf(0x3d, 0x40)) {
            val device = VitureDeviceState(nativeMode = 1, displayMode = code)
            assertTrue(device.ultrawide)
            assertFalse(device.displayModes.single { it.code == code }.stereo)
        }
        assertFalse(VitureDeviceState(nativeMode = 1, displayMode = 0x3a).ultrawide)
        assertFalse(VitureDeviceState(nativeMode = 0, displayMode = 0x40).ultrawide)
    }

    @Test fun nativeAndBypassModesFollowTheFirmwareSupportTables() {
        val native = VitureDeviceState(nativeMode = 1).displayModes
        assertEquals(setOf(0x31, 0x33, 0x34, 0x36, 0x37, 0x3a, 0x3d, 0x40), native.map { it.code }.toSet())
        assertTrue(native.filter { it.stereo || it.ultrawide }.all { it.hz == 60 })
        val bypass = VitureDeviceState(nativeMode = 0).displayModes
        assertEquals(setOf(0x31, 0x41, 0x35, 0x45), bypass.map { it.code }.toSet())
        assertTrue(bypass.filter { it.stereo }.all { it.hz == 90 })
    }

    @Test fun unknownNativeModeCannotInventABypassDisplayChoice() {
        for (mode in listOf(null, -1, 2)) {
            val device = VitureDeviceState(nativeMode = mode, displayMode = 0x35)
            assertTrue(device.displayModes.isEmpty())
            assertNull(device.selectedDisplayMode)
        }
    }

    @Test fun trackingAndRangesFollowObservedDeviceValues() {
        assertFalse(VitureDeviceState(nativeMode = 0, nativeDof = 2).nativeTracking)
        assertFalse(VitureDeviceState(nativeMode = 1, nativeDof = 0).nativeTracking)
        assertTrue(VitureDeviceState(nativeMode = 1, nativeDof = 2).nativeTracking)
        assertEquals(3, VitureDeviceState(filmMax = 8, film = .375f).filmLevel)
        assertEquals(1, VitureDeviceState(filmMax = 1, film = 1f).filmLevel)
        assertNull(VitureDeviceState().filmLevel)
        assertEquals(listOf(3, 5), VitureDeviceState(imuRateMask = (1 shl 3) or (1 shl 5)).rates)
    }

    @Test fun olderSdkKeepsSupportedControlsWithoutOverridingRuntimeLimits() {
        val one = VitureDeviceState(film = 1f).withFallbackLimits(VitureProtocol.Family.ONE)
        assertEquals(6, one.brightnessMax)
        assertEquals(7, one.volumeMax)
        assertEquals(1, one.filmMax)
        val beast = VitureDeviceState(film = .5f).withFallbackLimits(VitureProtocol.Family.BEAST)
        assertEquals(15, beast.volumeMax)
        assertEquals(8, beast.filmMax)
        assertNull(VitureDeviceState().withFallbackLimits(VitureProtocol.Family.PRO2).filmMax)
        val queried = VitureDeviceState(brightnessMax = 10, volumeMax = 12, filmMax = 4, film = .5f)
            .withFallbackLimits(VitureProtocol.Family.BEAST)
        assertEquals(10, queried.brightnessMax)
        assertEquals(12, queried.volumeMax)
        assertEquals(4, queried.filmMax)
    }

    @Test fun transientNativeReadsCannotResumeHostTrackingOrInventABypassDof() {
        val previous = VitureDeviceState(capabilities = VitureSdk.CAP_NATIVE, nativeMode = 1, nativeDof = 2)
        val failed = VitureDeviceState(capabilities = VitureSdk.CAP_NATIVE).retainingNativeTracking(previous)
        assertTrue(failed.nativeTracking)
        assertTrue(failed.nativeTrackingKnown)
        val bypass = previous.copy(nativeMode = 0).retainingNativeTracking(previous)
        assertNull(bypass.nativeDof)
        assertTrue(bypass.nativeTrackingKnown)
        val unknown = VitureDeviceState(capabilities = VitureSdk.CAP_NATIVE).retainingNativeTracking(null)
        assertFalse(unknown.nativeTrackingKnown)
        val enteringNative = VitureDeviceState(capabilities = VitureSdk.CAP_NATIVE, nativeMode = 1)
            .retainingNativeTracking(bypass)
        assertFalse(enteringNative.nativeTrackingKnown)
    }

    @Test fun failedNativeModeReadPreservesUltrawideDisplayAndScreenControls() {
        for (code in listOf(0x3d, 0x40)) {
            val previous = VitureDeviceState(capabilities = VitureSdk.CAP_NATIVE, nativeMode = 1,
                nativeDof = 0, displayMode = code, distance = 6, size = 4, side = true)
            val failed = VitureDeviceState(capabilities = VitureSdk.CAP_NATIVE, displayMode = 0x35)
                .retainingNativeTracking(previous)
            assertTrue(failed.ultrawide)
            assertEquals(code, failed.displayMode)
            assertEquals(6, failed.distance)
            assertEquals(4, failed.size)
            assertEquals(true, failed.side)
        }
    }

    @Test fun successfulModeReadsKeepTheirOwnDisplayAndScreenControls() {
        val previous = VitureDeviceState(capabilities = VitureSdk.CAP_NATIVE, nativeMode = 1,
            nativeDof = 0, displayMode = 0x40, distance = 6, size = 4, side = true)
        val native = previous.copy(displayMode = 0x34, distance = 3, size = 2, side = false)
        assertEquals(native, native.retainingNativeTracking(previous))
        val bypass = VitureDeviceState(capabilities = VitureSdk.CAP_NATIVE, nativeMode = 0, displayMode = 0x45)
        assertEquals(bypass, bypass.retainingNativeTracking(previous))
        val failedBypass = bypass.copy(nativeMode = null, displayMode = 0x35)
            .retainingNativeTracking(bypass)
        assertEquals(0, failedBypass.nativeMode)
        assertEquals(0x35, failedBypass.displayMode)
        assertNull(failedBypass.size)
    }
}
