package com.droiddeck.launcher.glasses

import org.junit.Assert.*
import org.junit.Test

class StereoPointValidationTest {
    private val p1=doubleArrayOf(400.0,0.0,320.0,0.0, 0.0,400.0,240.0,0.0, 0.0,0.0,1.0,0.0)
    private val p2=p1.copyOf().apply { this[3]=-24.0 }
    private val point=doubleArrayOf(0.0,0.0,.5,1.0)
    private val left=doubleArrayOf(320.0,240.0)
    private val right=doubleArrayOf(272.0,240.0)
    @Test fun metricDepthAndReprojectionThresholdAreUnchanged() {
        val result=StereoPointValidation.reconstruct(point,left,right,p1,p2)!!
        assertEquals(.5f,result.z,0f)
        assertNotNull(StereoPointValidation.reconstruct(point,left,doubleArrayOf(275.0,240.0),p1,p2))
        assertNull(StereoPointValidation.reconstruct(point,left,doubleArrayOf(275.01,240.0),p1,p2))
    }
    @Test fun aRejectedLandmarkDoesNotRemoveItsValidNeighbors() {
        val observations=listOf(left,doubleArrayOf(Double.NaN,240.0),left)
        val points=observations.map { StereoPointValidation.reconstruct(point,it,right,p1,p2) }
        assertNotNull(points[0]);assertNull(points[1]);assertNotNull(points[2])
    }
    @Test fun invalidDepthAndProjectionNeverProduceValidPoints() {
        for(z in listOf(-.5,.149,1.501,Double.NaN,Double.POSITIVE_INFINITY))
            assertNull(StereoPointValidation.reconstruct(doubleArrayOf(0.0,0.0,z,1.0),left,right,p1,p2))
        assertNull(StereoPointValidation.reconstruct(point.copyOf().apply { this[3]=0.0 },left,right,p1,p2))
        assertNull(StereoPointValidation.reconstruct(point,left,right,p1,p2.copyOf().apply { this[10]=-1.0 }))
        assertNull(StereoPointValidation.reconstruct(point,left,right,p1,p2.copyOf().apply { this[0]=Double.NaN }))
    }
}
