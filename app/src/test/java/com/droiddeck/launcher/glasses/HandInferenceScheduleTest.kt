package com.droiddeck.launcher.glasses

import org.junit.Assert.*
import org.junit.Test

class HandInferenceScheduleTest {
    private fun frame(schedule: HandInferenceSchedule, time: Long, left: List<Int> = listOf(0),
                      right: List<Int> = left, rawLeft: Int = left.size, rawRight: Int = right.size): Int {
        val maximum = schedule.select(time)
        schedule.observe(time, left, right, rawLeft, rawRight)
        return maximum
    }

    private fun stable(schedule: HandInferenceSchedule, side: Int = 0) {
        for (time in listOf(0L, 80L, 160L)) assertEquals(2, frame(schedule, time, listOf(side)))
    }

    @Test fun requiresThreeStereoConsistentFramesBeforeSingleHandTracking() {
        val schedule = HandInferenceSchedule()
        stable(schedule)
        assertEquals(1, frame(schedule, 240))
        assertEquals(1, frame(schedule, 320))
        assertEquals(2, frame(schedule, 400))
        assertEquals(1, frame(schedule, 480))
    }

    @Test fun discoversSecondHandAtBothCameraInputCadences() {
        for (cadence in listOf(40L, 80L)) {
            val schedule = HandInferenceSchedule()
            val searches = ArrayList<Long>()
            for (time in 0L..1200L step cadence) {
                if (frame(schedule, time) == 2) searches.add(time)
            }
            assertTrue(searches.size >= 5)
            assertTrue(searches.zipWithNext().all { (first, second) -> second - first <= 240 })
        }
    }

    @Test fun delayedFrameStillSearchesAtFirstFrameAfterDiscoveryDeadline() {
        val schedule = HandInferenceSchedule()
        stable(schedule)
        assertEquals(1, frame(schedule, 240))
        assertEquals(1, frame(schedule, 399))
        assertEquals(2, frame(schedule, 479))
        assertEquals(1, frame(schedule, 559))
    }

    @Test fun secondHandDiscoveredBySearchKeepsBothHandDetectorsSelected() {
        val schedule = HandInferenceSchedule()
        stable(schedule)
        assertEquals(1, frame(schedule, 240))
        assertEquals(2, frame(schedule, 400, listOf(0, 1)))
        for (time in listOf(480L, 560L, 640L)) assertEquals(2, frame(schedule, time, listOf(1, 0)))
        for (time in listOf(720L, 800L, 880L)) assertEquals(2, frame(schedule, time))
        assertEquals(1, frame(schedule, 960))
    }

    @Test fun anyCameraLossImmediatelyReturnsToFullSearch() {
        for ((left, right) in listOf(emptyList<Int>() to listOf(0), listOf(0) to emptyList())) {
            val schedule = HandInferenceSchedule()
            stable(schedule)
            assertEquals(1, frame(schedule, 240, left, right))
            assertEquals(2, frame(schedule, 320))
        }
    }

    @Test fun ambiguousOrFilteredDetectionsNeverMaintainSingleHandMode() {
        val invalid = listOf(
            listOf(0) to listOf(1),
            listOf(0, 0) to listOf(0),
            listOf(0) to listOf(0, 0),
            listOf(-1) to listOf(-1),
            listOf(2) to listOf(2)
        )
        for ((left, right) in invalid) {
            val schedule = HandInferenceSchedule()
            stable(schedule)
            frame(schedule, 240, left, right)
            assertEquals(2, frame(schedule, 320))
        }
        for ((rawLeft, rawRight) in listOf(2 to 1, 1 to 2, 0 to 1, -1 to 1)) {
            val schedule = HandInferenceSchedule()
            stable(schedule)
            frame(schedule, 240, rawLeft = rawLeft, rawRight = rawRight)
            assertEquals(2, frame(schedule, 320))
        }
    }

    @Test fun changingSidesRequiresFreshFullSearchStability() {
        val schedule = HandInferenceSchedule()
        stable(schedule)
        assertEquals(1, frame(schedule, 240, listOf(1)))
        for (time in listOf(320L, 400L, 480L)) assertEquals(2, frame(schedule, time, listOf(1)))
        assertEquals(1, frame(schedule, 560, listOf(1)))
    }

    @Test fun inconsistentInitialSidesCannotAccumulateStability() {
        val schedule = HandInferenceSchedule()
        for (index in 0..8) assertEquals(2, frame(schedule, index * 80L, listOf(index % 2)))
    }

    @Test fun resetDropsSessionState() {
        val schedule = HandInferenceSchedule()
        stable(schedule)
        frame(schedule, 240)
        schedule.reset()
        stable(schedule, 1)
        assertEquals(1, frame(schedule, 240, listOf(1)))
    }

    @Test fun longGapAndNonmonotonicTimeRequireFreshStability() {
        for (time in listOf(160L, 120L, 561L, -1L)) {
            val schedule = HandInferenceSchedule()
            stable(schedule)
            assertEquals(2, frame(schedule, time))
            assertEquals(2, frame(schedule, maxOf(0, time) + 80))
        }
    }

    @Test fun unobservedInferenceAndMismatchedObservationResetSchedule() {
        val schedule = HandInferenceSchedule()
        stable(schedule)
        assertEquals(1, schedule.select(240))
        assertEquals(2, frame(schedule, 320))
        schedule.observe(240, listOf(0), listOf(0))
        assertEquals(2, frame(schedule, 400))
    }
}
