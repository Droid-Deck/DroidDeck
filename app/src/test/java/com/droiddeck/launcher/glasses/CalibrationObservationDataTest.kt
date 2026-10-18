package com.droiddeck.launcher.glasses

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class CalibrationObservationDataTest {
    private fun view(count: Int=24) = JSONObject().put("objects",JSONArray((0 until count).map {
        JSONArray(listOf((it%4+1)*0.03,(it/4+1)*0.03,0.0))
    })).put("left",JSONArray((0 until count).map { JSONArray(listOf(100.0+it,200.0)) }))
        .put("right",JSONArray((0 until count).map { JSONArray(listOf(80.0+it,200.0)) }))
        .put("head",JSONArray(listOf(0.1,0.2,0.3,1.0,0.0,0.0,0.0)))

    @Test fun preservesPartialBoardCorrespondencesAndHeadPose() {
        for(count in listOf(12,20,24)) {
            val restored=CalibrationObservationData.parse(JSONObject(view(count).toString()))
            assertEquals(count,restored.objects.size)
            assertEquals(20.0,restored.left.last()[0]-restored.right.last()[0],0.0)
            assertEquals(SpatialVector(.1f,.2f,.3f),restored.head.position)
            assertEquals(SpatialRotation(),restored.head.rotation)
        }
    }
    @Test fun rejectsTruncatedOrMismatchedCorrespondences() {
        assertThrows(IllegalArgumentException::class.java) { CalibrationObservationData.parse(view(11)) }
        assertThrows(IllegalArgumentException::class.java) { CalibrationObservationData.parse(view(25)) }
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationObservationData.parse(view().put("right",view(20).getJSONArray("right")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationObservationData.parse(view().also { it.getJSONArray("left").put(0,JSONArray(listOf(1.0))) })
        }
    }
    @Test fun rejectsInvalidHeadRotationAndFloatOverflow() {
        assertThrows(IllegalStateException::class.java) {
            CalibrationObservationData.parse(view().put("head",JSONArray(listOf(0,0,0,0,0,0,0))))
        }
        assertThrows(IllegalStateException::class.java) {
            CalibrationObservationData.parse(view().put("head",JSONArray(listOf(1e100,0,0,1,0,0,0))))
        }
    }
}
