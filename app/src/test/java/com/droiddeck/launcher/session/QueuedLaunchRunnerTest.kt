package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueuedLaunchRunnerTest {
    private class Clock {
        var now = 0L
        private class Task(val at: Long, val run: Runnable)
        private val tasks = mutableListOf<Task>()
        fun schedule(delay: Long, run: Runnable) { tasks += Task(now + delay, run) }
        fun cancel(run: Runnable) { tasks.removeAll { it.run === run } }
        /** Moves time forward and runs what is due, as the main thread would. */
        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val next = tasks.filter { it.at <= end }.minByOrNull { it.at } ?: break
                tasks.remove(next)
                now = maxOf(now, next.at)
                next.run.run()
            }
            now = end
        }
    }

    private class Owner(clock: Clock, queue: QueuedLaunch<String>, val state: State) {
        class State { var blocked = true; var busy = false }
        val started = mutableListOf<String>()
        val runner = QueuedLaunchRunner(
            queue = queue, now = { clock.now }, blocked = { state.blocked }, busy = { state.busy },
            schedule = clock::schedule, unschedule = clock::cancel, start = { started += it }, retryMs = 2_000,
        )
    }

    private val minute = 60_000L

    @Test fun aLaunchWaitsWhileTheSettingBlocksItAndStartsOnceItDoesNot() {
        val clock = Clock()
        val owner = Owner(clock, QueuedLaunch<String>(15 * minute).also { it.queue("steam", 0) }, Owner.State())
        owner.runner.poke()
        clock.advance(5_000)
        assertEquals(emptyList<String>(), owner.started)
        owner.state.blocked = false
        owner.runner.poke()
        clock.advance(1)
        assertEquals(listOf("steam"), owner.started)
    }

    @Test fun aBusyRuntimeIsRetriedEveryTwoSecondsUntilItIsFree() {
        val clock = Clock()
        val state = Owner.State().apply { blocked = false; busy = true }
        val owner = Owner(clock, QueuedLaunch<String>(15 * minute).also { it.queue("steam", 0) }, state)
        owner.runner.poke()
        clock.advance(7_000)
        assertEquals(emptyList<String>(), owner.started)
        state.busy = false
        clock.advance(2_001)
        assertEquals(listOf("steam"), owner.started)
        clock.advance(10_000)
        assertEquals(listOf("steam"), owner.started)
    }

    @Test fun aLaunchThatWaitsPastItsLifetimeIsNeverStarted() {
        val clock = Clock()
        val state = Owner.State().apply { blocked = false; busy = true }
        val owner = Owner(clock, QueuedLaunch<String>(15 * minute).also { it.queue("steam", 0) }, state)
        owner.runner.poke()
        clock.advance(16 * minute)
        state.busy = false
        clock.advance(10_000)
        assertEquals(emptyList<String>(), owner.started)
    }

    @Test fun onlyTheRestoredOwnerStartsAfterTheFirstOneIsDestroyedMidRetry() {
        val clock = Clock()
        val state = Owner.State().apply { blocked = false; busy = true }
        val firstQueue = QueuedLaunch<String>(15 * minute).also { it.queue("steam", 0) }
        val first = Owner(clock, firstQueue, state)
        first.runner.poke()
        clock.advance(3_000)

        val saved = firstQueue.launch
        val savedAt = firstQueue.queuedAt
        first.runner.destroy()
        val secondQueue = QueuedLaunch<String>(15 * minute).also { it.restore(saved, savedAt, clock.now) }
        val second = Owner(clock, secondQueue, state)
        second.runner.poke()

        state.busy = false
        clock.advance(10_000)
        assertEquals(emptyList<String>(), first.started)
        assertEquals(listOf("steam"), second.started)
        assertNull(firstQueue.launch)
    }

    @Test fun aDestroyedOwnerNeverStartsEvenIfItsRetryWasAlreadyDue() {
        val clock = Clock()
        val state = Owner.State().apply { blocked = false }
        val owner = Owner(clock, QueuedLaunch<String>(15 * minute).also { it.queue("steam", 0) }, state)
        owner.runner.poke()
        owner.runner.destroy()
        clock.advance(10_000)
        assertEquals(emptyList<String>(), owner.started)
    }

    @Test fun pokingTwiceBeforeTheFirstAttemptStartsTheLaunchOnce() {
        val clock = Clock()
        val state = Owner.State().apply { blocked = false }
        val owner = Owner(clock, QueuedLaunch<String>(15 * minute).also { it.queue("steam", 0) }, state)
        owner.runner.poke()
        owner.runner.poke()
        clock.advance(1)
        assertEquals(listOf("steam"), owner.started)
    }
}
