package com.droiddeck.launcher.glasses

import org.junit.Assert.*
import org.junit.Test

class SteamVrSpatialGateTest {
    @Test fun launchWaitsForRunningThenTheEntireSettleInterval() {
        val gate = SteamVrSpatialGate()
        assertTrue(gate.spatialEnabled(true, 0))
        gate.prepare()
        assertFalse(gate.spatialEnabled(true, 60_000))
        gate.setRunning(false, 60_000)
        assertFalse(gate.spatialEnabled(true, 60_000))
        gate.setRunning(true, 60_000)
        assertFalse(gate.spatialEnabled(true, 61_999))
        assertTrue(gate.spatialEnabled(true, 62_000))
        assertFalse(gate.spatialEnabled(false, 62_000))
    }

    @Test fun repeatedRunningAndActivityPreparationDoNotMoveTheDeadline() {
        val gate = SteamVrSpatialGate()
        gate.prepare()
        gate.setRunning(true, 100)
        gate.prepare()
        gate.setRunning(true, 1_900)
        assertEquals(2_100L, gate.deadline)
        assertTrue(gate.spatialEnabled(true, 2_100))
        gate.prepare()
        gate.setRunning(true, 10_000)
        assertTrue(gate.spatialEnabled(true, 10_000))
    }

    @Test fun explicitCancelRestoresSavedTrackingBeforeRunningAndDuringSettling() {
        val gate = SteamVrSpatialGate()
        gate.prepare()
        gate.cancel()
        assertTrue(gate.spatialEnabled(true, 100))
        assertFalse(gate.spatialEnabled(false, 100))
        gate.prepare()
        gate.setRunning(true, 200)
        gate.cancel()
        assertNull(gate.deadline)
        assertTrue(gate.spatialEnabled(true, 201))
        assertFalse(gate.spatialEnabled(false, 10_000))
    }

    @Test fun stopAndNewLaunchCannotReuseAnEarlierDeadline() {
        val gate = SteamVrSpatialGate()
        gate.prepare()
        gate.setRunning(true, 100)
        gate.setRunning(false, 200)
        assertTrue(gate.spatialEnabled(true, 200))
        gate.prepare()
        assertFalse(gate.spatialEnabled(true, 2_100))
        gate.setRunning(false, 2_100)
        assertFalse(gate.spatialEnabled(true, 2_100))
        gate.setRunning(true, 3_000)
        assertFalse(gate.spatialEnabled(true, 4_999))
        assertTrue(gate.spatialEnabled(true, 5_000))
    }

    @Test fun discoveringAnAlreadyRunningSessionStillAllowsSettling() {
        val gate = SteamVrSpatialGate()
        gate.setRunning(true, 100)
        assertFalse(gate.spatialEnabled(true, 2_099))
        assertTrue(gate.spatialEnabled(true, 2_100))
    }
}
