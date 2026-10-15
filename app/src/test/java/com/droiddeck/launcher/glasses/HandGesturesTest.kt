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
        val h=HandGestures().estimate(openHand(),SpatialPose(SpatialVector(1f,2f,3f)),1_000_000_000)!!
        assertEquals(3.5f,h.pose.position.z,.0001f)
        assertTrue(h.pose.rotation.normalized()!=null);assertFalse(h.grip);assertEquals(0f,h.trigger,.0001f)
    }
    @Test fun pinchAndFingerClosureProduceTriggerAndGrip() {
        val detector=HandGestures();val p=openHand()
        detector.estimate(p,SpatialPose(),1_000_000_000)
        p[4]=p[8]
        for((tip,base) in listOf(12 to 9,16 to 13,20 to 17)) p[tip]=p[base]
        val pressed=detector.estimate(p,SpatialPose(),1_040_000_000)!!
        assertEquals(1f,pressed.trigger,.0001f);assertTrue(pressed.grip)
        val released=detector.estimate(openHand(),SpatialPose(),1_080_000_000)!!
        assertEquals(0f,released.trigger,.0001f);assertFalse(released.grip)
    }
    @Test fun invalidGeometryAndImplausibleTemporalJumpAreRejected() {
        val detector=HandGestures()
        assertNull(detector.estimate(emptyList(),SpatialPose(),1))
        assertNull(detector.estimate(List(21) { SpatialVector() },SpatialPose(),1))
        detector.estimate(openHand(),SpatialPose(),1_000_000_000)
        assertNull(detector.estimate(openHand(),SpatialPose(SpatialVector(2f,0f,0f)),1_040_000_000))
        detector.reset()
        assertNotNull(detector.estimate(openHand(),SpatialPose(SpatialVector(2f,0f,0f)),1_080_000_000))
    }
}
