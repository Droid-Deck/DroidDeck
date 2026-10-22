package com.droiddeck.launcher.glasses

import org.junit.Assert.*
import org.junit.Test

class HandObservationTest {
    private val time=1_000_000_000L
    private fun session()=SpatialSession().apply {
        reset(7,1.6f)
        accept(SpatialSample(SpatialPose(),time,7,true))
    }
    private fun advance(session: SpatialSession, ms: Long, valid: Boolean=true, x: Float=.1f) {
        session.accept(SpatialSample(SpatialPose(SpatialVector(x=x)),time+ms*1_000_000,7,valid))
    }

    @Test fun lateCameraConfirmationUsesTheCapturePoseInsteadOfTheLatestPose() {
        val session=session()
        val observation=HandObservation(time,7,session.trackingFrameGeneration)
        assertNull(observation.headPose(session,false,time))
        advance(session,150)
        assertEquals(SpatialPose(),observation.headPose(session,true,time+150_000_000))
        assertEquals(time,observation.timeNs)
    }

    @Test fun missingConfirmationOrExpiredObservationCannotPublish() {
        val session=session()
        val observation=HandObservation(time,7,session.trackingFrameGeneration)
        advance(session,180)
        assertNull(observation.headPose(session,false,time+180_000_000))
        assertNotNull(observation.headPose(session,true,time+200_000_000))
        assertNull(observation.headPose(session,true,time+200_000_001))
        assertNull(observation.headPose(session,true,time-1))
    }

    @Test fun longerHandWindowDoesNotExtendHeadFreshness() {
        val session=session()
        val observation=HandObservation(time,7,session.trackingFrameGeneration)
        assertNotNull(observation.headPose(session,true,time+100_000_000))
        assertNull(observation.headPose(session,true,time+100_000_001))
        advance(session,150)
        assertNotNull(observation.headPose(session,true,time+150_000_000))
    }

    @Test fun trackingLossAndRecoveredDiscontinuityInvalidateInFlightInference() {
        val session=session()
        val observation=HandObservation(time,7,session.trackingFrameGeneration)
        advance(session,20,valid=false)
        assertNull(observation.headPose(session,true,time+20_000_000))
        advance(session,30,x=5f)
        assertNull(observation.headPose(session,true,time+30_000_000))
        session.setFloor(1.6f,time+30_000_000)
        assertTrue(session.valid(time+30_000_000))
        assertNull(observation.headPose(session,true,time+30_000_000))
    }

    @Test fun reconnectAndMissingHistoryCannotBorrowAnotherPose() {
        val session=session()
        val observation=HandObservation(time,7,session.trackingFrameGeneration)
        advance(session,40)
        assertNull(observation.copy(timeNs=time+20_000_000).headPose(session,true,time+40_000_000))
        session.reset(8,1.6f)
        session.accept(SpatialSample(SpatialPose(),time+50_000_000,8,true))
        assertNull(observation.headPose(session,true,time+50_000_000))
    }
}
