package com.droiddeck.launcher.glasses

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class StereoFingerGesturesTest {
    private fun open(): MutableList<SpatialVector> {
        val p=MutableList(21) { SpatialVector(320f,240f) }
        p[4]=SpatialVector(390f,190f)
        for((base,x) in listOf(5 to 355f,9 to 330f,13 to 305f,17 to 280f)) {
            for(j in 0..3) p[base+j]=SpatialVector(x,180f-25f*j)
        }
        return p
    }
    private fun curl(p: MutableList<SpatialVector>,reach: Float=1f) = p.apply {
        for(base in listOf(9,13,17)) this[base+3]=this[0]+(this[base]-this[0])*reach
    }
    private fun transform(p: List<SpatialVector>,angle: Float,scale: Float,mirror: Boolean) = p.map {
        val x=(if(mirror) -it.x else it.x)*scale
        val y=it.y*scale
        SpatialVector(x*cos(angle)-y*sin(angle)+700f,x*sin(angle)+y*cos(angle)-80f)
    }
    @Test fun openingAndClosingAreIndependentOfImageScaleRotationAndHandedness() {
        for(mirror in listOf(false,true)) for(angle in listOf(0f,.7f,2.8f)) {
            val closed=curl(open()).apply { this[4]=this[8] }
            val result=StereoFingerGestures.estimate(transform(closed,angle,1.5f,mirror),
                transform(closed,angle+.12f,.7f,mirror),false)
            assertTrue(result.gripValid);assertTrue(result.grip)
            assertTrue(result.triggerValid);assertEquals(1f,result.trigger,0f)
            val released=StereoFingerGestures.estimate(transform(open(),angle,1.5f,mirror),
                transform(open(),angle+.12f,.7f,mirror),true)
            assertTrue(released.gripValid);assertFalse(released.grip)
            assertTrue(released.triggerValid);assertEquals(0f,released.trigger,0f)
        }
    }
    @Test fun curlThresholdsProvideObservedHysteresisAndRelease() {
        val partial=curl(open(),1.4f)
        val idle=StereoFingerGestures.estimate(partial,partial,false)
        val held=StereoFingerGestures.estimate(partial,partial,true)
        assertTrue(idle.gripValid);assertFalse(idle.grip)
        assertTrue(held.gripValid);assertTrue(held.grip)
        val opening=curl(open(),1.6f)
        assertFalse(StereoFingerGestures.estimate(opening,opening,true).grip)
        val two=curl(open()).apply { for(i in 17..20) this[i]=open()[i] }
        assertFalse(StereoFingerGestures.estimate(two,two,false).grip)
        assertTrue(StereoFingerGestures.estimate(two,two,true).grip)
    }
    @Test fun shortStraightProjectedFingersDoNotCountAsCurled() {
        val straight=open().apply {
            for(base in listOf(9,13,17)) for(j in 1..3) this[base+j]=this[base]+SpatialVector(y=-4.5f*j)
        }
        val result=StereoFingerGestures.estimate(straight,straight,false)
        assertTrue(result.gripValid);assertFalse(result.grip)
    }
    @Test fun pinchRequiresBothEyesAndUsesTheirMoreOpenEstimate() {
        fun pinch(ratio: Float)=open().apply { this[4]=this[8]+SpatialVector(x=75f*ratio) }
        val result=StereoFingerGestures.estimate(pinch(.25f),pinch(.30f),false)
        assertTrue(result.triggerValid);assertEquals((.55f-.30f)/.35f,result.trigger,.0001f)
        val conflict=StereoFingerGestures.estimate(pinch(.25f),pinch(.50f),false)
        assertFalse(conflict.triggerValid);assertEquals(0f,conflict.trigger,0f)
        assertTrue(conflict.gripValid);assertFalse(conflict.grip)
    }
    @Test fun conflictingCurlDoesNotReportAnObservedRelease() {
        val result=StereoFingerGestures.estimate(curl(open()),open(),true)
        assertFalse(result.gripValid);assertFalse(result.grip)
        assertTrue(result.triggerValid)
    }
    @Test fun invalidJointsOnlyInvalidateTheirOwnGesture() {
        val badPinch=open().apply { this[4]=SpatialVector(Float.NaN,190f) }
        val pinch=StereoFingerGestures.estimate(badPinch,open(),false)
        assertFalse(pinch.triggerValid);assertTrue(pinch.gripValid)
        val badGrip=open().apply { this[15]=SpatialVector(300f,Float.POSITIVE_INFINITY) }
        val grip=StereoFingerGestures.estimate(open(),badGrip,true)
        assertTrue(grip.triggerValid);assertFalse(grip.gripValid);assertFalse(grip.grip)
    }
    @Test fun missingViewsAndDegeneratePalmsCannotArmInputs() {
        val flat=open().apply { for(i in listOf(0,5,9,13,17)) this[i]=SpatialVector(i.toFloat(),0f) }
        val collapsed=open().apply { this[17]=this[5] }
        for(invalid in listOf(null,emptyList(),flat,collapsed)) {
            val result=StereoFingerGestures.estimate(open(),invalid,true)
            assertFalse(result.triggerValid);assertFalse(result.gripValid)
            assertEquals(0f,result.trigger,0f);assertFalse(result.grip)
        }
    }
}
