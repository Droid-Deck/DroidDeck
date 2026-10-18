package com.droiddeck.launcher.glasses

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.droiddeck.launcher.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CalibrationDistanceCheckTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun board(z: Float)=List(24) { i -> SpatialVector((i%4-1.5f)*.03f,(i/4-2.5f)*.03f,z) }

    @Test
    @Config(qualifiers = "fr")
    fun rejectedCaptureExplainsTheProblemInTheSelectedLanguage() {
        val result = CalibrationDistanceCheck.evaluate(context, .3048f, 3, null)
        assertFalse(result.accepted)
        assertEquals(context.getString(R.string.spatial_distance_corners, 3), result.message)
        assertFalse(result.message.contains("checkerboard corners detected"))
        assertTrue(result.message.contains("3 / 24"))
    }

    @Test fun missingAndPartialBoardsExplainVisibilityRatherThanDistance() {
        for (count in listOf(0,12,23)) {
            val result=CalibrationDistanceCheck.evaluate(context,.508f,count,null)
            assertFalse(result.accepted)
            assertTrue(result.message.contains("$count / 24"))
        }
    }
    @Test fun fullBoardWithRejectedStereoGeometryCannotPass() {
        assertFalse(CalibrationDistanceCheck.evaluate(context,.508f,24,null).accepted)
        assertFalse(CalibrationDistanceCheck.evaluate(context,.508f,24,board(.508f).dropLast(1)).accepted)
        assertFalse(CalibrationDistanceCheck.evaluate(context,.508f,24,board(.508f).toMutableList().also {
            it[0]=SpatialVector(z=Float.NaN)
        }).accepted)
    }
    @Test fun preservesFiveCentimeterToleranceAndReportsBothUnits() {
        val locale=Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertTrue(CalibrationDistanceCheck.evaluate(context,.508f,24,board(.508f)).accepted)
            assertTrue(CalibrationDistanceCheck.evaluate(context,.3048f,24,board(.3048f)).accepted)
            assertFalse(CalibrationDistanceCheck.evaluate(context,.508f,24,board(.3048f)).accepted)
            assertTrue(CalibrationDistanceCheck.evaluate(context,.508f,24,board(.557f)).accepted)
            val result=CalibrationDistanceCheck.evaluate(context,.508f,24,board(.60f))
            assertFalse(result.accepted)
            assertTrue(result.message.contains("60.0 cm (23.62 in)"))
            assertTrue(result.message.contains("50.8 cm (20.00 in)"))
        } finally { Locale.setDefault(locale) }
    }
    @Test fun checksCameraToCenterDistanceRatherThanOnlyForwardDepth() {
        val points=board(.4f).map { it+SpatialVector(x=.3f) }
        assertTrue(CalibrationDistanceCheck.evaluate(context,.5f,24,points).accepted)
        assertFalse(CalibrationDistanceCheck.evaluate(context,.4f,24,points).accepted)
    }
    @Test fun rejectsInvalidRequestedDistances() {
        for(distance in listOf(0f,Float.NaN,Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { CalibrationDistanceCheck.evaluate(context,distance,24,board(.5f)) }
        }
    }
}
