package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestRequestCoordinatorTest {
    @Test fun coalescesPendingRequestsToTheLatestValue() {
        val coordinator = LatestRequestCoordinator<String>()

        assertTrue(coordinator.submit("first"))
        val first = coordinator.takeLatest()!!
        assertFalse(coordinator.submit("second"))
        assertFalse(coordinator.submit("third"))

        assertFalse(coordinator.isLatest(first))
        val latest = coordinator.takeLatest()!!
        assertEquals("third", latest.value)
        assertTrue(coordinator.isLatest(latest))
        assertNull(coordinator.takeLatest())
    }

    @Test fun requestAfterWorkerBecomesIdleStartsAnotherWorker() {
        val coordinator = LatestRequestCoordinator<String>()

        assertTrue(coordinator.submit("first"))
        coordinator.takeLatest()
        assertNull(coordinator.takeLatest())

        assertTrue(coordinator.submit("second"))
        assertEquals("second", coordinator.takeLatest()!!.value)
    }

    @Test fun newerRequestInvalidatesCompletedResultBeforeItIsApplied() {
        val coordinator = LatestRequestCoordinator<String>()

        assertTrue(coordinator.submit("first"))
        val completed = coordinator.takeLatest()!!
        assertFalse(coordinator.submit("second"))

        assertFalse(coordinator.isLatest(completed))
        assertEquals("second", coordinator.takeLatest()!!.value)
    }

    @Test fun repeatedPollDoesNotInvalidateEquivalentInFlightResult() {
        val coordinator = LatestRequestCoordinator<String>()

        assertTrue(coordinator.submit("same"))
        val inFlight = coordinator.takeLatest()!!
        assertFalse(coordinator.submit("same"))

        assertTrue(coordinator.isLatest(inFlight))
        assertEquals("same", coordinator.takeLatest()!!.value)
    }
}
