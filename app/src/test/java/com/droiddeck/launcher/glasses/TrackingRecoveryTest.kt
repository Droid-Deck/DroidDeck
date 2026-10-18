package com.droiddeck.launcher.glasses

import org.junit.Assert.*
import org.junit.Test

class TrackingRecoveryTest {
    @Test fun periodicStatusRefreshDetectsExpiryWithoutAnotherPoseCallback() {
        val time=1_000_000_000L
        val session=SpatialTracking.session
        try {
            session.reset(91,1.6f)
            session.accept(SpatialSample(SpatialPose(),time,91,true))
            SpatialTracking.refreshStatus(time)
            assertTrue(SpatialTracking.status.value.headFresh)
            assertNull(TrackingRecovery.forSession(true,true,SpatialTracking.status.value))
            SpatialTracking.refreshStatus(time+100_000_001)
            assertFalse(SpatialTracking.status.value.headFresh)
            assertEquals(TrackingRecovery.LOST,TrackingRecovery.forSession(true,true,SpatialTracking.status.value))
            session.accept(SpatialSample(SpatialPose(),time+110_000_000,91,true))
            SpatialTracking.refreshStatus(time+110_000_000)
            assertNull(TrackingRecovery.forSession(true,true,SpatialTracking.status.value))
        } finally {
            session.reset(92,1.6f)
            SpatialTracking.refreshStatus(time+200_000_000)
        }
    }

    @Test fun healthyAndNonSpatialSessionsNeverShowRecovery() {
        assertNull(TrackingRecovery.forSession(true,true,SpatialTrackingStatus(trackingValid=true,headFresh=true)))
        assertNull(TrackingRecovery.forSession(false,false,SpatialTrackingStatus()))
        assertEquals(TrackingRecovery.DISCONNECTED,TrackingRecovery.forSession(true,false,SpatialTrackingStatus(trackingValid=true)))
    }

    @Test fun pendingRecenterStillRequiresLookingAroundUntilHeadTrackingReturns() {
        val pending=SpatialTrackingStatus(recenterPending=true,needsRecenter=true)
        assertEquals(TrackingRecovery.LOST,TrackingRecovery.forSession(true,true,pending))
        assertEquals(TrackingRecovery.STABILIZING,TrackingRecovery.forSession(true,true,pending.copy(headFresh=true)))
        assertNull(TrackingRecovery.forSession(true,true,pending.copy(headFresh=true,trackingValid=true,recenterPending=false,needsRecenter=false)))
    }

    @Test fun newTrackingFrameExplainsTheRequiredRecenter() {
        val changed=SpatialTrackingStatus(needsRecenter=true,headFresh=true)
        assertEquals(TrackingRecovery.RECENTER,TrackingRecovery.forSession(true,true,changed))
        assertEquals(TrackingRecovery.LOST,TrackingRecovery.forSession(true,true,changed.copy(headFresh=false)))
    }
}
