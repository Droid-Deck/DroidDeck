package com.droiddeck.launcher.glasses

import org.junit.Assert.*
import org.junit.Test

class HandGesturesTest {
    private fun openHand(): MutableList<SpatialVector> {
        val p=MutableList(21) { SpatialVector(z=.5f) }
        p[0]=SpatialVector(0f,0f,.5f)
        p[4]=SpatialVector(.07f,-.05f,.5f)
        for((base,x) in listOf(5 to .035f,9 to .01f,13 to -.015f,17 to -.04f)) {
            for(j in 0..3)p[base+j]=SpatialVector(x,-.06f-.025f*j,.5f)
        }
        return p
    }
    @Test fun triangulatedMetersAreTransformedIntoTheHeadWorldFrame() {
        val h=HandGestures(rightHand=false).estimate(openHand(),SpatialPose(SpatialVector(1f,2f,3f)),1_000_000_000)!!
        assertEquals(3.5f,h.pose.position.z,.0001f)
        assertTrue(h.pose.rotation.normalized()!=null);assertFalse(h.grip);assertEquals(0f,h.trigger,.0001f)
    }
    @Test fun mirroredHandsUseTheSamePalmNormalAndFingerDirection() {
        val left=openHand().apply { this[9]=SpatialVector(0f,-.06f,.5f) }
        val right=left.map { SpatialVector(-it.x,it.y,it.z) }
        val world=SpatialPose(SpatialVector(1f,2f,3f),SpatialRotation(.2f,-.3f,.1f,.9f).normalized()!!)
        for(turn in listOf(SpatialRotation(),SpatialRotation(y=1f,w=0f),
            SpatialRotation(.3f,.1f,-.2f,.9f).normalized()!!)) {
            val hands=listOf(left,right).mapIndexed { side,points ->
                HandGestures(rightHand=side==1).estimate(points.map(turn::apply),world,1_000_000_000)!!
            }
            for(hand in hands) {
                val rotation=hand.pose.rotation
                assertVector(world.rotation.apply(turn.apply(SpatialVector(z=-1f))),rotation.apply(SpatialVector(y=1f)))
                assertVector(world.rotation.apply(turn.apply(SpatialVector(y=-1f))),rotation.apply(SpatialVector(z=-1f)))
                val x=rotation.apply(SpatialVector(x=1f));val y=rotation.apply(SpatialVector(y=1f))
                assertVector(rotation.apply(SpatialVector(z=1f)),x.cross(y))
            }
        }
    }
    @Test fun rightHandOrientationDoesNotChangePinchOrGrip() {
        val points=openHand()
        points[4]=points[8]
        for((tip,base) in listOf(12 to 9,16 to 13,20 to 17)) points[tip]=points[base]
        val left=HandGestures(rightHand=false).estimate(points,SpatialPose(),1_000_000_000)!!
        val right=HandGestures(rightHand=true).estimate(points.map { SpatialVector(-it.x,it.y,it.z) },SpatialPose(),1_000_000_000)!!
        assertEquals(left.flags(),right.flags());assertEquals(1f,right.trigger,0f);assertTrue(right.grip)
        assertEquals(left.timeNs,right.timeNs)
        assertVector(SpatialVector(-left.pose.position.x,left.pose.position.y,left.pose.position.z),right.pose.position)
    }
    private fun assertVector(expected: SpatialVector,actual: SpatialVector) {
        assertEquals(expected.x,actual.x,.0001f);assertEquals(expected.y,actual.y,.0001f);assertEquals(expected.z,actual.z,.0001f)
    }
    @Test fun pinchAndFingerClosureProduceTriggerAndGrip() {
        val detector=HandGestures(rightHand=false);val p=openHand()
        detector.estimate(p,SpatialPose(),1_000_000_000)
        p[4]=p[8]
        for((tip,base) in listOf(12 to 9,16 to 13,20 to 17)) p[tip]=p[base]
        val pressed=detector.estimate(p,SpatialPose(),1_040_000_000)!!
        assertEquals(1f,pressed.trigger,.0001f);assertTrue(pressed.grip)
        val released=detector.estimate(openHand(),SpatialPose(),1_080_000_000)!!
        assertEquals(0f,released.trigger,.0001f);assertFalse(released.grip)
    }
    @Test fun invalidGeometryAndImplausibleTemporalJumpAreRejected() {
        val detector=HandGestures(rightHand=false)
        assertNull(detector.estimate(emptyList(),SpatialPose(),1))
        assertEquals(HandObservationOutcome.LANDMARK_COUNT_INVALID,detector.lastOutcome)
        assertNull(detector.estimate(List(21) { SpatialVector() },SpatialPose(),1))
        assertEquals(HandObservationOutcome.PALM_WIDTH_INVALID,detector.lastOutcome)
        detector.estimate(openHand(),SpatialPose(),1_000_000_000)
        assertNull(detector.estimate(openHand(),SpatialPose(SpatialVector(2f,0f,0f)),1_040_000_000))
        assertEquals(HandObservationOutcome.PALM_MOTION_REJECTED,detector.lastOutcome)
        detector.reset()
        assertNotNull(detector.estimate(openHand(),SpatialPose(SpatialVector(2f,0f,0f)),1_080_000_000))
        assertEquals(HandObservationOutcome.ACCEPTED,detector.lastOutcome)
    }
    @Test fun anUnreliableUnusedJointDoesNotHideThePalmOrDisableGestures() {
        val points=openHand().map { it as SpatialVector? }.toMutableList()
        points[6]=null
        val hand=HandGestures(rightHand=false).estimate(points,SpatialPose(),1_000_000_000)!!
        assertTrue(hand.valid);assertTrue(hand.triggerValid);assertTrue(hand.gripValid)
        assertEquals(7,hand.flags())
    }
    @Test fun repeatedMotionRejectionsPreserveTheLastPoseAndGripHysteresis() {
        val detector=HandGestures(false)
        val closed=openHand().apply {
            for((tip,base) in listOf(12 to 9,16 to 13,20 to 17)) this[tip]=this[base]
        }
        assertTrue(detector.estimate(closed,SpatialPose(),1_000_000_000)!!.grip)
        for(time in listOf(1_040_000_000L,1_080_000_000L,1_120_000_000L)) {
            assertNull(detector.estimate(openHand(),SpatialPose(SpatialVector(2f,0f,0f)),time))
            assertEquals(HandObservationOutcome.PALM_MOTION_REJECTED,detector.lastOutcome)
            detector.resetAfterRejection(detector.lastOutcome)
        }
        val partlyClosed=openHand().apply { this[12]=this[9];this[16]=this[13] }
        val recovered=detector.estimate(partlyClosed,SpatialPose(),1_160_000_000)!!
        assertTrue(recovered.grip)
        assertEquals(1_160_000_000L,recovered.timeNs)
    }
    @Test fun motionRejectionDoesNotPreventReacquisitionAfterTheMotionWindow() {
        val detector=HandGestures(false)
        detector.estimate(openHand(),SpatialPose(),1_000_000_000)
        val moved=SpatialPose(SpatialVector(2f,0f,0f))
        for(time in listOf(1_040_000_000L,1_160_000_000L)) {
            assertNull(detector.estimate(openHand(),moved,time))
            detector.resetAfterRejection(detector.lastOutcome)
        }
        val reacquired=detector.estimate(openHand(),moved,1_200_000_000)!!
        assertEquals(HandObservationOutcome.ACCEPTED,detector.lastOutcome)
        assertEquals(1_200_000_000L,reacquired.timeNs)
    }
    @Test fun actualDetectionLossClearsMotionHistoryAndGripEvenAfterAMotionRejection() {
        val detector=HandGestures(false)
        val closed=openHand().apply {
            for((tip,base) in listOf(12 to 9,16 to 13,20 to 17)) this[tip]=this[base]
        }
        assertTrue(detector.estimate(closed,SpatialPose(),1_000_000_000)!!.grip)
        val moved=SpatialPose(SpatialVector(2f,0f,0f))
        assertNull(detector.estimate(closed,moved,1_040_000_000))
        detector.resetAfterRejection(detector.lastOutcome)
        detector.resetAfterRejection(HandObservationOutcome.NO_MATCHING_DETECTION)
        val partlyClosed=openHand().apply { this[12]=this[9];this[16]=this[13] }
        val reacquired=detector.estimate(partlyClosed,moved,1_080_000_000)!!
        assertFalse(reacquired.grip)
    }
    @Test fun occludedPinchKeepsThePalmAndIndependentGrip() {
        val detector=HandGestures(rightHand=false)
        val points=openHand().map { it as SpatialVector? }.toMutableList()
        points[4]=points[8]
        for((tip,base) in listOf(12 to 9,16 to 13,20 to 17)) points[tip]=points[base]
        assertEquals(1f,detector.estimate(points,SpatialPose(),1_000_000_000)!!.trigger,0f)
        points[4]=null
        val hand=detector.estimate(points,SpatialPose(),1_080_000_000)!!
        assertTrue(hand.valid);assertFalse(hand.triggerValid);assertEquals(0f,hand.trigger,0f)
        assertTrue(hand.gripValid);assertTrue(hand.grip);assertEquals(5,hand.flags())
    }
    @Test fun occludedGripKeepsThePalmAndIndependentPinch() {
        val points=openHand().map { it as SpatialVector? }.toMutableList()
        points[4]=points[8];points[16]=SpatialVector(Float.NaN,0f,.5f)
        val hand=HandGestures(rightHand=false).estimate(points,SpatialPose(),1_000_000_000)!!
        assertTrue(hand.valid);assertTrue(hand.triggerValid);assertEquals(1f,hand.trigger,0f)
        assertFalse(hand.gripValid);assertFalse(hand.grip);assertEquals(3,hand.flags())
    }
    @Test fun missingPalmStillRejectsTheController() {
        for(index in listOf(0,5,9,13,17)) {
            val points=openHand().map { it as SpatialVector? }.toMutableList();points[index]=null
            val detector=HandGestures(rightHand=false)
            assertNull(detector.estimate(points,SpatialPose(),1_000_000_000))
            assertEquals(HandObservationOutcome.PALM_LANDMARK_MISSING,detector.lastOutcome)
            assertNotNull(detector.estimate(openHand(),SpatialPose(),1_040_000_000))
            assertEquals(HandObservationOutcome.ACCEPTED,detector.lastOutcome)
        }
        assertEquals(0,SpatialHand(triggerValid=true,gripValid=true).flags())
    }
    @Test fun degeneratePalmAxesAreDistinguishedFromMissingStereoLandmarks() {
        val detector=HandGestures(rightHand=false)
        for(middle in listOf(SpatialVector(0f,0f,.5f),SpatialVector(.04f,0f,.5f))) {
            val points=openHand().apply { this[9]=middle }
            assertNull(detector.estimate(points,SpatialPose(),1_000_000_000))
            assertEquals(HandObservationOutcome.PALM_BASIS_INVALID,detector.lastOutcome)
        }
        assertNotNull(detector.estimate(openHand(),SpatialPose(),1_040_000_000))
        assertEquals(HandObservationOutcome.ACCEPTED,detector.lastOutcome)
    }
    @Test fun agreeingImageCurlsAndPinchSurviveMissingTriangulatedFingertips() {
        val image=openHand().apply {
            this[4]=this[8]
            for((tip,base) in listOf(12 to 9,16 to 13,20 to 17)) this[tip]=this[base]
        }.map { SpatialVector(320f+it.x*1000f,240f+it.y*1000f) }
        val points=openHand().map { it as SpatialVector? }.toMutableList()
        for(index in listOf(4,8,12,16,20)) points[index]=null
        val hand=HandGestures(false).estimate(points,SpatialPose(),1_000_000_000,image,image)!!
        assertTrue(hand.valid);assertTrue(hand.gripValid);assertTrue(hand.grip)
        assertTrue(hand.triggerValid);assertEquals(1f,hand.trigger,0f)
        assertVector(HandGestures(false).estimate(openHand(),SpatialPose(),1_000_000_000)!!.pose.position,hand.pose.position)
    }
    @Test fun imageDisagreementInvalidatesGestureInsteadOfFallingBackToPressedDepth() {
        val closed=openHand().apply {
            this[4]=this[8]
            for((tip,base) in listOf(12 to 9,16 to 13,20 to 17)) this[tip]=this[base]
        }
        fun image(points: List<SpatialVector>)=points.map { SpatialVector(it.x*1000f,it.y*1000f) }
        val detector=HandGestures(false)
        assertTrue(detector.estimate(closed,SpatialPose(),1_000_000_000,image(closed),image(closed))!!.grip)
        val conflict=detector.estimate(closed,SpatialPose(),1_080_000_000,image(closed),image(openHand()))!!
        assertTrue(conflict.valid);assertFalse(conflict.triggerValid);assertFalse(conflict.gripValid)
        assertFalse(conflict.grip);assertEquals(0f,conflict.trigger,0f)
        val missingView=detector.estimate(closed,SpatialPose(),1_120_000_000,image(closed),null)!!
        assertTrue(missingView.valid);assertFalse(missingView.triggerValid);assertFalse(missingView.gripValid)
        val released=detector.estimate(closed,SpatialPose(),1_160_000_000,image(openHand()),image(openHand()))!!
        assertTrue(released.triggerValid);assertTrue(released.gripValid)
        assertFalse(released.grip);assertEquals(0f,released.trigger,0f)
    }
}
