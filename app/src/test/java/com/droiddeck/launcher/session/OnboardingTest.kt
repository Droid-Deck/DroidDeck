package com.droiddeck.launcher.session

import com.droiddeck.launcher.session.Onboarding.Facts
import com.droiddeck.launcher.session.Onboarding.Progress
import com.droiddeck.launcher.session.Onboarding.State
import com.droiddeck.launcher.session.Onboarding.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OnboardingTest {
    private val none = Facts(runtime = false, proton = false, steam = false)

    @Before fun fresh() = Onboarding.resetProgress()

    @Test fun everythingReadyShowsNothing() {
        val rows = Onboarding.rows(emptyMap(), emptyMap(), Facts())
        assertFalse(Onboarding.pending(rows))
        assertEquals(100, Onboarding.overall(rows))
    }

    @Test fun stepsAreActiveFailedOrWaiting() {
        val rows = Onboarding.rows(mapOf(Step.RUNTIME to Progress("x", 50)), mapOf(Step.PROTON to "no"), none)
        assertEquals(listOf(State.ACTIVE, State.FAILED, State.WAITING), rows.map { it.state })
        assertTrue(Onboarding.pending(rows))
    }

    @Test fun anActiveStepStaysActiveWhenItsFactIsAlreadyTrue() {
        val rows = Onboarding.rows(mapOf(Step.STEAM to Progress("x", -1)), emptyMap(), Facts())
        assertEquals(State.ACTIVE, rows.first { it.step == Step.STEAM }.state)
    }

    @Test fun overallWeighsDownloadsBySize() {
        val rows = Onboarding.rows(mapOf(Step.RUNTIME to Progress("x", 50)), emptyMap(), Facts(runtime = false))
        assertEquals(10 + 30 + 50, Onboarding.overall(rows))
    }

    @Test fun overallNeverGoesBackwardsWhileStepsHandOver() {
        val prefetched = Onboarding.rows(mapOf(Step.PROTON to Progress("x", 100)), emptyMap(), Facts(runtime = true, proton = false, steam = false))
        val before = Onboarding.overall(prefetched)
        val handover = Onboarding.rows(mapOf(Step.STEAM to Progress("y", 4)), emptyMap(), Facts(runtime = true, proton = false, steam = false))
        assertTrue(Onboarding.overall(handover) >= before)
        val installing = Onboarding.rows(mapOf(Step.PROTON to Progress("z", 0), Step.STEAM to Progress("y", 4)), emptyMap(), Facts(runtime = true, proton = false, steam = false))
        assertTrue(Onboarding.overall(installing) >= before)
    }

    @Test fun overallStartsOverOnceEverythingIsReady() {
        Onboarding.overall(Onboarding.rows(mapOf(Step.RUNTIME to Progress("x", 90)), emptyMap(), Facts(runtime = false)))
        assertEquals(100, Onboarding.overall(Onboarding.rows(emptyMap(), emptyMap(), Facts())))
        val again = Onboarding.rows(mapOf(Step.RUNTIME to Progress("x", 10)), emptyMap(), Facts(runtime = false))
        assertEquals(2 + 30 + 50, Onboarding.overall(again))
    }

    @Test fun settlingWithEverythingReadyStartsTheNextAttemptFromZero() {
        Onboarding.overall(Onboarding.rows(mapOf(Step.RUNTIME to Progress("x", 90)), emptyMap(), Facts(runtime = false)))
        Onboarding.active.value = emptyMap()
        Onboarding.failed.value = emptyMap()
        Onboarding.facts.value = Facts()
        Onboarding.settle()
        val again = Onboarding.rows(mapOf(Step.RUNTIME to Progress("x", 10)), emptyMap(), Facts(runtime = false))
        assertEquals(2 + 30 + 50, Onboarding.overall(again))
    }

    @Test fun reportingClearsAPriorFailure() {
        Onboarding.fail(Step.STEAM, "boom")
        Onboarding.report(Step.STEAM, "again", 3)
        assertTrue(Onboarding.failed.value[Step.STEAM] == null)
        Onboarding.finish(Step.STEAM)
        assertTrue(Onboarding.active.value[Step.STEAM] == null)
    }
}
