package com.droiddeck.launcher.glasses

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class SpatialPoseTest {
    private val t=1_000_000_000L
    private fun sample(x: Float=0f,y: Float=0f,z: Float=0f,time: Long=t,generation: Long=7,valid: Boolean=true,
                       rotation: SpatialRotation=SpatialRotation())=SpatialSample(SpatialPose(SpatialVector(x,y,z),rotation),time,generation,valid)
    private fun session()=SpatialSession().apply { reset(7,1.6f);accept(sample()) }
    private fun near(expected: Float,actual: Float)=assertEquals(expected,actual,0.0001f)

    @Test fun sdkPoseKeepsMetersAndCanonicalAxes() {
        val p=SpatialPose.fromSdk(floatArrayOf(1f,2f,-3f,1.1f,0f,0f,0f))!!
        assertEquals(SpatialVector(1f,2f,-3f),p.position);near(1f,p.rotation.w)
        assertNull(SpatialPose.fromSdk(floatArrayOf(0f,0f,0f,0f,0f,0f,0f)))
        assertNull(SpatialPose.fromSdk(floatArrayOf(Float.NaN,0f,0f,1f,0f,0f,0f)))
    }
    @Test fun quaternionRotationAndInverseAgree() {
        val q=SpatialRotation(y=sqrt(0.5f),w=sqrt(0.5f))
        val p=SpatialPose(SpatialVector(1f,2f,3f),q)
        val front=q.apply(SpatialVector(z=-1f));near(-1f,front.x);near(0f,front.z)
        val inverse=p.inverse()*p;near(0f,inverse.position.length());near(1f,inverse.rotation.w)
    }
    @Test fun floorSurvivesCrouchedRecenter() {
        val s=session();s.accept(sample(x=.2f,y=-.3f,time=t+10));s.recenter(false,t+10)
        near(1.3f,s.view(false)!!.position.y);near(0f,s.view(false)!!.position.x)
        s.accept(sample(x=.2f,time=t+20));near(1.6f,s.view(false)!!.position.y)
    }
    @Test fun screenAndVrOriginsStayIndependent() {
        val s=session();s.accept(sample(x=.2f,time=t+10));s.recenter(true,t+10)
        near(0f,s.view(true)!!.position.x);near(.2f,s.view(false)!!.position.x)
        s.accept(sample(x=.3f,time=t+20));s.recenter(false,t+20)
        near(.1f,s.view(true)!!.position.x);near(0f,s.view(false)!!.position.x)
    }
    @Test fun screenRecenterPreservesRecliningRotation() {
        val s=SpatialSession();s.reset(7,1.6f)
        val tilt=SpatialRotation(x=sqrt(.5f),w=sqrt(.5f))
        s.accept(sample(rotation=tilt));near(1f,s.view(true)!!.rotation.w)
        near(tilt.x,s.view(false)!!.rotation.x)
    }
    @Test fun staleHeadFreezesViewAndRejectsOldConnection() {
        val s=session();s.accept(sample(x=.1f,time=t+10));val last=s.view(false)
        assertFalse(s.accept(sample(x=99f,time=t+20,generation=6)))
        s.accept(sample(time=t+20,valid=false));assertEquals(last,s.view(false));assertFalse(s.valid(t+21))
        s.accept(sample(x=.1f,time=t+30));assertFalse(s.valid(t+100_000_031))
        assertFalse(s.accept(sample(x=Float.NaN,time=t+40)));assertEquals(last,s.view(false))
    }
    @Test fun discontinuityRequiresExplicitRecovery() {
        val s=session();assertFalse(s.accept(sample(x=5f,time=t+10)))
        assertTrue(s.needsRecenter);assertFalse(s.valid(t+10));near(0f,s.view(false)!!.position.x)
        s.recenter(false,t+10);assertTrue(s.recenterPending);assertFalse(s.valid(t+10))
        for (i in 1..6) s.accept(sample(x=5f,time=t+i*50_000_000L))
        assertTrue(s.valid(t+300_000_000));near(0f,s.view(false)!!.position.x)
        s.reset(8,1.6f,true);s.accept(sample(generation=8,time=t+400_000_000));assertFalse(s.valid(t+400_000_000))
        s.recenter(false,t+400_000_000)
        for (i in 1..6) s.accept(sample(generation=8,time=t+400_000_000+i*50_000_000L))
        assertTrue(s.valid(t+700_000_000))
    }
    @Test fun recenterDuringLostTrackingWaitsForSteadySamplesInsteadOfDroppingTheRequest() {
        val s=session();s.accept(sample(time=t+10,valid=false));val frozen=s.view(false)
        s.recenter(false,t+20);assertTrue(s.recenterPending)
        s.accept(sample(x=.2f,time=t+50_000_000))
        assertFalse(s.valid(t+50_000_000));assertEquals(frozen,s.view(false))
        for(i in 2..6) s.accept(sample(x=.2f,time=t+i*50_000_000L))
        assertFalse(s.recenterPending);assertTrue(s.valid(t+300_000_000));near(0f,s.view(false)!!.position.x)
        near(1.6f,s.view(false)!!.position.y)
    }
    @Test fun staleRecenterWaitsAndRepeatedTapsDoNotRestartTheWait() {
        val s=session();s.recenter(true,t+200_000_000);assertTrue(s.recenterPending)
        s.accept(sample(x=.2f,time=t+250_000_000))
        s.recenter(true,t+250_000_000)
        for(i in 1..5) s.accept(sample(x=.2f,time=t+250_000_000+i*50_000_000L))
        assertFalse(s.recenterPending);near(0f,s.view(true)!!.position.x)
        near(.2f,s.view(false)!!.position.x)
    }
    @Test fun unstableOrInterruptedRecoveryCannotAcceptANewOrigin() {
        val s=session();s.accept(sample(x=5f,time=t+10));s.recenter(false,t+10)
        for(i in 1..8) s.accept(sample(x=5f+(i%2)*.1f,time=t+i*50_000_000L))
        assertTrue(s.recenterPending);assertTrue(s.needsRecenter)
        s.accept(sample(x=5f,time=t+450_000_000))
        s.accept(sample(x=5f,time=t+500_000_000,valid=false))
        s.accept(sample(x=5f,time=t+550_000_000))
        assertTrue(s.recenterPending)
        for(i in 1..5) s.accept(sample(x=5f,time=t+550_000_000+i*50_000_000L))
        assertFalse(s.recenterPending);assertTrue(s.valid(t+800_000_000))
    }
    @Test fun disconnectCancelsQueuedRecenterAndOldSamplesCannotCompleteIt() {
        val s=session();s.recenter(false,t+200_000_000);assertTrue(s.recenterPending)
        s.reset(8,1.6f,true);assertFalse(s.recenterPending)
        for(i in 1..10) s.accept(sample(time=t+i*50_000_000L))
        assertTrue(s.needsRecenter);assertNull(s.latest())
        s.accept(sample(generation=8,time=t+600_000_000));assertFalse(s.valid(t+600_000_000))
    }
    @Test fun recoverableQuaternionScaleDoesNotCreateFalseDiscontinuities() {
        val s=session()
        for(i in 1..4) assertTrue(s.accept(sample(time=t+i,rotation=SpatialRotation(w=.8f))))
        assertFalse(s.needsRecenter);near(1f,s.latest()!!.pose.rotation.w)
    }
    @Test fun calibrationFrameChangesOnlyWhenTheTrackingFrameChanges() {
        val s=session();val frame=s.trackingFrameGeneration
        s.recenter(false,t);assertEquals(frame,s.trackingFrameGeneration)
        s.accept(sample(x=5f,time=t+10));assertEquals(frame+1,s.trackingFrameGeneration)
        s.accept(sample(x=5f,time=t+20));assertEquals(frame+1,s.trackingFrameGeneration)
        s.reset(8,1.6f,true);assertEquals(frame+2,s.trackingFrameGeneration)
    }
    @Test fun captureRemainsValidWhenHeadPollingAdvancesDuringBoardDetection() {
        val s=session();val frame=s.trackingFrameGeneration
        val detectionStarted=t+1_000_000L
        s.accept(sample(x=.01f,time=t+20_000_000L))
        assertFalse(s.valid(detectionStarted))
        assertTrue(s.isCurrentTrackingFrame(7,frame) { t+25_000_000L })
    }
    @Test fun captureCompletionStillRejectsStaleChangedAndDisconnectedTracking() {
        val s=session();val frame=s.trackingFrameGeneration
        assertFalse(s.isCurrentTrackingFrame(7,frame) { t+100_000_001L })
        s.accept(sample(x=5f,time=t+10));assertFalse(s.isCurrentTrackingFrame(7,frame) { t+20 })
        s.recenter(false,t+20)
        for(i in 1..6) s.accept(sample(x=5f,time=t+i*50_000_000L))
        assertTrue(s.valid(t+300_000_000L))
        assertFalse(s.isCurrentTrackingFrame(7,frame) { t+300_000_000L })
        s.reset(8,1.6f);s.accept(sample(generation=8,time=t+400_000_000L))
        assertFalse(s.isCurrentTrackingFrame(7,frame) { t+400_000_000L })
    }
    @Test fun newerHeadDoesNotRefreshOlderHand() {
        val s=session();s.setHands(7,SpatialHand(SpatialPose(),t,true,1f,true),SpatialHand())
        s.accept(sample(time=t+80_000_000))
        s.accept(sample(time=t+150_000_000));assertTrue(s.hand(0,t+200_000_000).valid)
        assertEquals(t,s.hand(0,t+200_000_000).timeNs)
        assertFalse(s.hand(0,t+200_000_001).valid)
        s.recenter(false,t+200_000_001);assertFalse(s.hand(0,t+200_000_001).valid)
        s.reset(8,1.6f);s.setHands(7,SpatialHand(timeNs=t,valid=true),SpatialHand());assertFalse(s.hand(0,t).valid)
    }
    private fun heldHand(time: Long = t) = SpatialHand(timeNs=time,valid=true,trigger=1f,grip=true)
    private fun epochs(session: SpatialSession) = List(4) { (session.inputEpochs() ushr (it*8)) and 0xff }
    @Test fun overwrittenHandLossRemainsVisibleAfterHeldGestureReturns() {
        val s=session();s.setHands(7,heldHand(),heldHand())
        s.setHands(7,SpatialHand(),heldHand(t+10))
        s.setHands(7,heldHand(t+20),heldHand(t+20))
        assertEquals(listOf(1,1,0,0),epochs(s))
        assertTrue(s.hand(0,t+20).valid);assertTrue(s.hand(0,t+20).grip)
        assertEquals(listOf(1,1,0,0),epochs(s))
    }
    @Test fun triggerAndGripLossEpochsAreIndependentForBothHands() {
        val s=session();s.setHands(7,heldHand(),heldHand())
        s.setHands(7,heldHand(t+10).copy(triggerValid=false),heldHand(t+10))
        assertEquals(listOf(1,0,0,0),epochs(s))
        assertTrue(s.hand(0,t+10).gripValid)
        s.setHands(7,heldHand(t+20).copy(gripValid=false),heldHand(t+20).copy(triggerValid=false))
        assertEquals(listOf(1,1,1,0),epochs(s))
        s.setHands(7,heldHand(t+30).copy(gripValid=false),heldHand(t+30).copy(triggerValid=false))
        assertEquals(listOf(1,1,1,0),epochs(s))
        assertFalse(s.hand(0,t+30).gripValid);assertTrue(s.hand(0,t+30).triggerValid)
    }
    @Test fun overwrittenHeadLossDoesNotLoseTheInputReset() {
        val s=session();s.setHands(7,heldHand(),heldHand())
        s.accept(sample(time=t+10,valid=false))
        s.accept(sample(time=t+20))
        s.setHands(7,heldHand(t+20),heldHand(t+20))
        assertEquals(listOf(1,1,1,1),epochs(s))
        assertTrue(s.hand(0,t+20).triggerValid)
    }
    @Test fun headGapClearsCachedHandsEvenWithoutAnInvalidSnapshot() {
        val s=session();s.setHands(7,heldHand(),heldHand())
        s.accept(sample(time=t+100_000_001))
        assertFalse(s.hand(0,t+100_000_001).valid)
        assertEquals(listOf(1,1,1,1),epochs(s))
        s.setHands(7,heldHand(t+110_000_000),heldHand(t+110_000_000))
        assertTrue(s.hand(0,t+110_000_000).valid)
        assertEquals(listOf(1,1,1,1),epochs(s))
    }
    @Test fun latePreLossCaptureCannotRearmAnInputAfterHeadRecovery() {
        val s=session();s.setHands(7,heldHand(),heldHand())
        s.accept(sample(time=t+20,valid=false));s.accept(sample(time=t+30))
        val released=heldHand(t+10).copy(trigger=0f,grip=false)
        s.setHands(7,released,released)
        val late=s.hand(0,t+30)
        assertTrue(late.valid);assertFalse(late.triggerValid);assertFalse(late.gripValid)
        assertEquals(listOf(1,1,1,1),epochs(s))
        s.setHands(7,released.copy(timeNs=t+40),released.copy(timeNs=t+40))
        assertTrue(s.hand(0,t+40).triggerValid);assertTrue(s.hand(0,t+40).gripValid)
        assertEquals(listOf(1,1,1,1),epochs(s))
    }
    @Test fun freshReplacementAfterLongHandGapStillAdvancesResetEpoch() {
        val s=session();s.setHands(7,heldHand(),heldHand())
        for(i in 1..5) s.accept(sample(time=t+i*50_000_000))
        s.setHands(7,heldHand(t+250_000_000),heldHand(t+250_000_000))
        assertTrue(s.hand(0,t+250_000_000).valid)
        assertEquals(listOf(1,1,1,1),epochs(s))
    }
    @Test fun handExpiryAndRepeatedInvalidPollsOnlyIncrementOnce() {
        val s=session();s.setHands(7,heldHand(),SpatialHand())
        for(i in 1..4) s.accept(sample(time=t+i*50_000_000))
        assertTrue(s.hand(0,t+200_000_000).valid)
        repeat(4) { assertFalse(s.hand(0,t+200_000_001+it).valid) }
        assertEquals(listOf(1,1,0,0),epochs(s))
        s.setHands(7,heldHand(t+210_000_000),SpatialHand())
        assertTrue(s.hand(0,t+210_000_000).valid)
        assertEquals(listOf(1,1,0,0),epochs(s))
    }
    @Test fun headExpiryThenDelayedHeadSampleDoesNotDoubleCountLoss() {
        val s=session();s.setHands(7,heldHand(),heldHand())
        for(i in 0..1) assertFalse(s.hand(i,t+100_000_001).valid)
        assertEquals(listOf(1,1,1,1),epochs(s))
        s.accept(sample(time=t+150_000_000))
        s.setHands(7,heldHand(t+160_000_000),heldHand(t+160_000_000))
        assertEquals(listOf(1,1,1,1),epochs(s))
    }
    @Test fun disabledHandsReleaseOnceAndWrongGenerationDoesNotAffectCurrentHands() {
        val s=session();s.setHands(7,heldHand(),heldHand())
        s.setHands(6,SpatialHand(),SpatialHand())
        assertTrue(s.hand(0,t).valid);assertEquals(listOf(0,0,0,0),epochs(s))
        repeat(3) { s.setHands(7,SpatialHand(),SpatialHand()) }
        assertEquals(listOf(1,1,1,1),epochs(s))
        s.reset(8,1.6f)
        assertEquals(0,s.inputEpochs())
    }
    @Test fun invalidTriggerValueOnlyInvalidatesItsOwnGestureEpoch() {
        val s=session();s.setHands(7,heldHand(),heldHand())
        s.setHands(7,heldHand(t+10).copy(trigger=Float.NaN),heldHand(t+10))
        assertEquals(listOf(1,0,0,0),epochs(s))
        assertFalse(s.hand(0,t+10).triggerValid);assertTrue(s.hand(0,t+10).gripValid)
    }
    @Test fun eachPackedEpochWrapsWithinItsOwnByte() {
        val s=session()
        repeat(255) {
            s.setHands(7,heldHand(t+it*2),heldHand(t+it*2))
            s.setHands(7,SpatialHand(),SpatialHand())
        }
        assertEquals(listOf(255,255,255,255),epochs(s));assertEquals(-1,s.inputEpochs())
        s.setHands(7,heldHand(t+512),heldHand(t+512));s.setHands(7,SpatialHand(),SpatialHand())
        assertEquals(0,s.inputEpochs())
    }
    @Test fun historyNeverCrossesReconnectOrLargeTimeGap() {
        val s=session();assertNotNull(s.poseAt(t+4_000_000,7));assertNull(s.poseAt(t+20_000_000,7))
        s.reset(8,1.6f);assertNull(s.poseAt(t,7))
    }
    @Test fun cameraClockWarmsUpAndRejectsResetAndJitter() {
        val clock=CameraClock();val offset=10_000_000_000L
        for(i in 1..15) assertNull(clock.map(i*40_000_000L,offset+i*40_000_000L))
        assertEquals(offset+640_000_000L,clock.map(640_000_000L,offset+640_000_000L))
        assertNull(clock.map(1,offset+1))
        val bad=CameraClock()
        for(i in 1..30) assertNull(bad.map(i*40_000_000L,offset+i*40_000_000L+(i%2)*30_000_000L))
    }
    @Test fun halfRateCameraClockRetainsSourceTimeThroughSkippedPairs() {
        val clock=CameraClock();val offset=10_000_000_000L
        for(i in 1..15) assertNull(clock.map(i*80_000_000L,offset+i*80_000_000L))
        assertEquals(offset+1_280_000_000L,clock.map(1_280_000_000L,offset+1_280_000_000L))
        assertEquals(offset+1_440_000_000L,clock.map(1_440_000_000L,offset+1_440_000_000L))
        assertNull(clock.map(1,offset+1))
    }
    @Test fun cameraClockKeepsAlignedMappingAfterLongSkippedGaps() {
        val offset=10_000_000_000L
        for(gap in listOf(240_000_000L,1_000_000_000L)) {
            val clock=CameraClock()
            for(i in 1..16) clock.map(i*80_000_000L,offset+i*80_000_000L)
            val source=1_280_000_000L+gap
            assertEquals(offset+source,clock.map(source,offset+source))
            assertEquals(offset+source+80_000_000L,clock.map(source+80_000_000L,offset+source+80_000_000L))
        }
    }
    @Test fun cameraClockRewarmsAfterSourceOrReceiptStopsOrReverses() {
        val offset=10_000_000_000L;val end=1_280_000_000L
        val resets=listOf(1L to offset+end+80_000_000L,
            end to offset+end+80_000_000L,
            end+80_000_000L to 1L,
            end+80_000_000L to offset+end)
        for((source,receipt) in resets) {
            val clock=CameraClock()
            for(i in 1..16) clock.map(i*80_000_000L,offset+i*80_000_000L)
            for(i in 0..14) assertNull(clock.map(source+i*80_000_000L,receipt+i*80_000_000L))
            assertEquals(receipt+1_200_000_000L,clock.map(source+1_200_000_000L,receipt+1_200_000_000L))
        }
    }
    @Test fun cameraClockRewarmsAfterOffsetJumpAcrossSkippedFrames() {
        val offset=10_000_000_000L
        for(gap in listOf(240_000_000L,1_000_000_000L)) for(jump in listOf(-20_000_001L,20_000_001L)) {
            val clock=CameraClock()
            for(i in 1..16) clock.map(i*80_000_000L,offset+i*80_000_000L)
            val source=1_280_000_000L+gap;val receipt=source+offset+jump
            for(i in 0..14) assertNull(clock.map(source+i*80_000_000L,receipt+i*80_000_000L))
            assertEquals(receipt+1_200_000_000L,clock.map(source+1_200_000_000L,receipt+1_200_000_000L))
        }
    }
    @Test fun cameraClockRetainsMappingWithBoundedJitterAcrossSkippedFrames() {
        val clock=CameraClock();val offset=10_000_000_000L
        for(i in 1..16) clock.map(i*80_000_000L,offset+i*80_000_000L+(i%2)*20_000_000L)
        assertEquals(offset+1_520_000_000L,clock.map(1_520_000_000L,offset+1_540_000_000L))
        assertEquals(offset+2_520_000_000L,clock.map(2_520_000_000L,offset+2_520_000_000L))
        assertNull(clock.map(2_760_000_000L,offset+2_780_000_001L))
    }
    @Test fun cameraClockRetainsMappingThroughIsolatedShortGapReceiptJitter() {
        val clock=CameraClock();val offset=10_000_000_000L
        for(i in 1..16) clock.map(i*80_000_000L,offset+i*80_000_000L)
        assertEquals(offset+1_360_000_000L,clock.map(1_360_000_000L,offset+1_390_000_000L))
        assertEquals(offset+1_440_000_000L,clock.map(1_440_000_000L,offset+1_440_000_000L))
    }
    @Test fun cameraClockInvalidTimestampRequiresFreshWarmup() {
        val offset=10_000_000_000L
        for((source,receipt) in listOf(0L to offset,-1L to offset,1L to 0L,1L to -1L)) {
            val clock=CameraClock()
            for(i in 1..16) clock.map(i*80_000_000L,offset+i*80_000_000L)
            assertNull(clock.map(source,receipt))
            for(i in 17..31) assertNull(clock.map(i*80_000_000L,offset+i*80_000_000L))
            assertEquals(offset+2_560_000_000L,clock.map(2_560_000_000L,offset+2_560_000_000L))
        }
    }
}
