package com.droiddeck.launcher.glasses

import com.droiddeck.launcher.glasses.GlassesModeState.Confirmation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesModeStateTest {
    private val device = GlassesModeState.Device(0x101d, "pro")
    private val state = GlassesModeState(20_000).apply { updateDevice(device, 0) }

    @Test fun transientFallbackDoesNotRejectAWorkingStereoTransition() {
        state.sent(true, 100)
        assertEquals(Confirmation.WAITING, state.observe(false, 2_000))
        assertEquals(Confirmation.WAITING, state.observe(false, 19_000))
        assertFalse(state.restorePending)
        assertEquals(Confirmation.CONFIRMED, state.observe(true, 19_500))
        assertFalse(state.pendingStereo)
        assertEquals(Confirmation.NONE, state.observe(false, 20_100))
    }

    @Test fun missingSignalAtDeadlineRequestsOneFallback() {
        state.sent(true, 100)
        assertEquals(Confirmation.WAITING, state.observe(false, 20_099))
        assertEquals(Confirmation.FAILED, state.observe(false, 20_100))
        assertTrue(state.restorePending)
        assertEquals(Confirmation.NONE, state.observe(false, 40_000))
        state.sent(false, 40_001)
        assertFalse(state.restorePending)
        assertFalse(state.stopVr())
    }

    @Test fun stoppingVrBeforeStereoAppearsStillRestores2d() {
        state.sent(true, 100)
        state.startVr()
        assertTrue(state.stopVr())
        assertTrue(state.restorePending)
        state.sent(false, 200)
        assertFalse(state.pendingStereo)
        assertFalse(state.restorePending)
        assertFalse(state.stopVr())
        assertEquals(Confirmation.NONE, state.observe(true, 400))
    }

    @Test fun failedRevertRemainsPendingUntilAWriteSucceeds() {
        state.sent(true, 100)
        state.startVr()
        state.observe(true, 200)
        state.stopVr()
        state.cancelPending()
        assertTrue(state.restorePending)
        assertTrue(state.stopVr())
        state.sent(false, 300)
        assertFalse(state.restorePending)
    }

    @Test fun manualModeTakesOwnershipFromVr() {
        state.sent(true, 100)
        state.startVr()
        state.manual()
        state.sent(true, 200)
        assertFalse(state.stopVr())
        assertFalse(state.restorePending)
        assertEquals(Confirmation.CONFIRMED, state.observe(true, 300))
    }

    @Test fun manualRetryGetsItsOwnConfirmationWindow() {
        state.sent(true, 100)
        assertEquals(Confirmation.FAILED, state.observe(false, 20_100))
        state.sent(false, 20_101)
        state.manual()
        state.sent(true, 30_000)
        assertEquals(Confirmation.WAITING, state.observe(false, 49_999))
        assertEquals(Confirmation.CONFIRMED, state.observe(true, 50_000))
        assertFalse(state.restorePending)
    }

    @Test fun cancelledOrDisconnectedRequestCannotFailLater() {
        state.sent(true, 100)
        state.cancelPending()
        assertFalse(state.pendingStereo)
        assertEquals(Confirmation.NONE, state.observe(false, 20_100))
        assertFalse(state.restorePending)
    }

    @Test fun manual2dCancelsThePendingStereoDeadline() {
        state.sent(true, 100)
        state.manual()
        state.sent(false, 200)
        assertFalse(state.pendingStereo)
        assertEquals(Confirmation.NONE, state.observe(false, 20_100))
    }

    @Test fun modeReenumerationPreservesDeadlineAndVrOwnership() {
        state.sent(true, 100)
        state.startVr()
        assertFalse(state.updateDevice(null, 1_000))
        assertEquals(Confirmation.WAITING, state.observe(false, 1_000))
        assertFalse(state.updateDevice(device, 2_000))
        assertEquals(20_100L, state.nextCheckAt)
        assertEquals(Confirmation.CONFIRMED, state.observe(true, 3_000))
        assertTrue(state.stopVr())
    }

    @Test fun absentAtDeadlineCancelsWithoutFailureOrLateRestore() {
        state.sent(true, 100)
        state.startVr()
        state.updateDevice(null, 1_000)
        state.stopVr()
        assertEquals(Confirmation.NONE, state.observe(false, 20_100))
        assertFalse(state.restorePending)
        assertNull(state.nextCheckAt)
        assertFalse(state.updateDevice(device, 40_000))
        assertFalse(state.stopVr())
    }

    @Test fun differentModelOrKnownSerialCannotInheritARequest() {
        listOf(GlassesModeState.Device(0x1131, "pro"), device.copy(serial = "other")).forEach { other ->
            state.updateDevice(device, 0)
            state.sent(true, 100)
            state.startVr()
            state.updateDevice(null, 1_000)
            assertTrue(state.updateDevice(other, 2_000))
            assertFalse(state.pendingStereo)
            assertFalse(state.stopVr())
        }
    }

    @Test fun permissionPendingReconnectKeepsTheKnownIdentity() {
        state.sent(true, 100)
        state.startVr()
        state.updateDevice(null, 1_000)
        assertFalse(state.updateDevice(device.copy(serial = null), 2_000))
        assertTrue(state.pendingStereo)
        assertTrue(state.updateDevice(device.copy(serial = "other"), 3_000))
        assertFalse(state.pendingStereo)
        assertFalse(state.stopVr())
    }

    @Test fun sameDeviceReconnectDoesNotResetFailureStatus() {
        state.sent(true, 100)
        assertEquals(Confirmation.FAILED, state.observe(false, 20_100))
        state.sent(false, 20_101)
        assertFalse(state.updateDevice(null, 21_000))
        assertFalse(state.updateDevice(device, 22_000))
    }

    @Test fun confirmedVrCannotRestoreAfterAProlongedUnplug() {
        state.sent(true, 100)
        state.startVr()
        state.observe(true, 200)
        state.updateDevice(null, 1_000)
        assertTrue(state.stopVr())
        assertEquals(21_000L, state.nextCheckAt)
        state.updateDevice(device, 22_000)
        assertFalse(state.restorePending)
        assertFalse(state.stopVr())
        assertNull(state.nextCheckAt)
    }
}
