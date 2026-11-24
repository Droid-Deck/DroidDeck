package com.droiddeck.launcher.session

import com.droiddeck.launcher.session.Onboarding.Facts
import com.droiddeck.launcher.session.Onboarding.Progress
import com.droiddeck.launcher.session.Onboarding.State
import com.droiddeck.launcher.session.Onboarding.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingTest {
    private val none = Facts(runtime = false, proton = false, steam = false)

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
        assertEquals(30 + 15 + 25, Onboarding.overall(rows))
    }

    @Test fun reportingClearsAPriorFailure() {
        Onboarding.fail(Step.STEAM, "boom")
        Onboarding.report(Step.STEAM, "again", 3)
        assertTrue(Onboarding.failed.value[Step.STEAM] == null)
        Onboarding.finish(Step.STEAM)
        assertTrue(Onboarding.active.value[Step.STEAM] == null)
    }
}
