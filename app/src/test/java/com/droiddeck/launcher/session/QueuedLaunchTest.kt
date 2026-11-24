package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class QueuedLaunchTest {
    private val minute = 60_000L

    @Test fun aQueuedLaunchIsWantedUntilItsLifetimeEnds() {
        val queue = QueuedLaunch<String>(15 * minute)
        queue.queue("steam", now = 100)
        assertEquals("steam", queue.current(100 + 14 * minute))
        assertNull(queue.current(100 + 16 * minute))
        assertNull(queue.launch)
    }

    @Test fun aRestoredLaunchKeepsTheTimeItWasQueued() {
        val queue = QueuedLaunch<String>(15 * minute)
        queue.restore("steam", queuedAt = 0, now = 14 * minute)
        assertEquals("steam", queue.current(14 * minute))
        assertEquals(0L, queue.queuedAt)
        assertNull(queue.current(16 * minute))
    }

    @Test fun aLaunchRestoredAfterItsLifetimeIsDropped() {
        val queue = QueuedLaunch<String>(15 * minute)
        queue.restore("steam", queuedAt = 0, now = 20 * minute)
        assertNull(queue.launch)
    }

    @Test fun aLaunchRestoredWithoutAValidTimeIsDropped() {
        val queue = QueuedLaunch<String>(15 * minute)
        queue.restore("steam", queuedAt = 0, now = -1)
        assertNull(queue.launch)
        queue.restore(null, queuedAt = 5, now = 10)
        assertNull(queue.launch)
    }

    @Test fun clearingForgetsTheLaunchSoAnOldCallbackDoesNotMatchIt() {
        val queue = QueuedLaunch<Any>(15 * minute)
        val launch = Any()
        queue.queue(launch, now = 0)
        assertSame(launch, queue.current(1))
        queue.clear()
        assertNull(queue.current(1))
    }
}
