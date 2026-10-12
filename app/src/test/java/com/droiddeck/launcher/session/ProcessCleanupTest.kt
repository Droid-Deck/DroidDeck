package com.droiddeck.launcher.session

import org.junit.Assert.*
import org.junit.Test

class ProcessCleanupTest {
    private class Processes {
        var time = 0L
        val entries = linkedMapOf<Int, ProcessCleanup.Entry>()
        val signals = mutableListOf<Pair<Int, Int>>()
        val signalTimes = mutableListOf<Triple<Int, Int, Long>>()
        var onSleep: () -> Unit = {}
        var onRead: (Int) -> Unit = {}
        var onSignal: (Int, Int) -> Unit = { _, _ -> }
        val cleanup = ProcessCleanup(
            snapshot = { entries.values.toList() },
            current = { pid -> onRead(pid); entries[pid] },
            signal = { pid, signal ->
                signals.add(pid to signal)
                signalTimes.add(Triple(pid, signal, time))
                onSignal(pid, signal)
            },
            now = { time },
            sleep = { time += it; onSleep() },
        )

        fun add(pid: Int, parent: Int = 1, started: Long = 10, state: Char = 'S'): ProcessCleanup.Entry =
            ProcessCleanup.Entry(ProcessCleanup.Identity(pid, started), parent, state).also { entries[pid] = it }
    }

    @Test fun emptySnapshotMustRemainEmptyOnTheNextPass() {
        val processes = Processes()
        val result = processes.cleanup.terminate { it }
        assertTrue(result.complete)
        assertEquals(0, result.stopped)
        assertEquals(50L, processes.time)
        assertTrue(processes.signals.isEmpty())
    }

    @Test fun waitsForGracefulExitWithoutEscalating() {
        val processes = Processes()
        processes.add(10)
        processes.onSleep = { if (processes.time >= 250) processes.entries.clear() }
        val result = processes.cleanup.terminate { it }
        assertTrue(result.complete)
        assertEquals(1, result.stopped)
        assertEquals(listOf(10 to 18, 10 to 15), processes.signals)
        assertEquals(300L, processes.time)
    }

    @Test fun escalatesAndWaitsUntilTheKilledProcessActuallyExits() {
        val processes = Processes()
        processes.add(10)
        processes.onSleep = {
            if (processes.time >= ProcessCleanup.GRACE_MS + 150) processes.entries.clear()
        }
        val result = processes.cleanup.terminate { it }
        assertTrue(result.complete)
        assertEquals(1, result.stopped)
        assertEquals(listOf(10 to 18, 10 to 15, 10 to 9), processes.signals)
        assertEquals(ProcessCleanup.GRACE_MS + 200, processes.time)
    }

    @Test fun anUnkillableProcessIsReportedAsRemainingNotStopped() {
        val processes = Processes()
        val process = processes.add(10)
        val result = processes.cleanup.terminate { it }
        assertFalse(result.complete)
        assertEquals(0, result.stopped)
        assertEquals(listOf(process.identity), result.remaining)
        assertEquals(ProcessCleanup.GRACE_MS + ProcessCleanup.KILL_WAIT_MS, processes.time)
    }

    @Test fun discoversChildrenCreatedDuringTheGracePeriod() {
        val processes = Processes()
        processes.add(10)
        processes.onSignal = { pid, signal -> if (pid == 10 && signal == 15) processes.add(11, parent = 10) }
        processes.onSleep = { if (processes.time >= 150) processes.entries.clear() }
        val result = processes.cleanup.terminate { it }
        assertTrue(result.complete)
        assertEquals(2, result.stopped)
        assertEquals(listOf(10 to 18, 10 to 15, 11 to 18, 11 to 15), processes.signals)
    }

    @Test fun newlyDiscoveredChildGetsItsOwnGracePeriodNearTheGlobalBoundary() {
        val processes = Processes()
        processes.add(10)
        var added = false
        processes.onSleep = {
            if (processes.time >= 1000 && !added) {
                processes.add(11, parent = 10)
                added = true
            }
            if (processes.time >= 1250) processes.entries.remove(10)
            if (processes.time >= 2250) processes.entries.remove(11)
        }

        val result = processes.cleanup.terminate { it }

        assertTrue(result.complete)
        assertEquals(2, result.stopped)
        assertEquals(
            listOf(
                Triple(11, 18, 1000L),
                Triple(11, 15, 1000L),
                Triple(11, 9, 2200L),
            ),
            processes.signalTimes.filter { it.first == 11 },
        )
    }

    @Test fun childFirstSeenAfterTheGlobalGraceDeadlineGetsTermAndIsReportedIfBudgetExpires() {
        val processes = Processes()
        processes.add(10)
        var added = false
        processes.onSleep = {
            if (processes.time >= ProcessCleanup.GRACE_MS + 50 && !added) {
                processes.entries.remove(10)
                processes.add(11, parent = 10)
                added = true
            }
        }

        val result = processes.cleanup.terminate { it }

        assertFalse(result.complete)
        assertEquals(listOf(11), result.remaining.map { it.pid })
        assertEquals(listOf(10 to 18, 10 to 15, 10 to 9, 11 to 18, 11 to 15), processes.signals)
        assertEquals(1250L, processes.signalTimes.last().third)
        assertFalse(processes.signals.any { it == (11 to 9) })
        assertEquals(ProcessCleanup.GRACE_MS + ProcessCleanup.KILL_WAIT_MS, processes.time)
    }

    @Test fun doesNotSignalAReusedPid() {
        val processes = Processes()
        val original = processes.add(10)
        processes.onRead = { processes.add(10, started = 20) }
        val result = processes.cleanup.terminate { entries -> entries.filter { it.identity == original.identity } }
        assertTrue(result.complete)
        assertEquals(1, result.stopped)
        assertTrue(processes.signals.isEmpty())
        assertEquals(20L, processes.entries.getValue(10).identity.started)
    }

    @Test fun rechecksIdentityBetweenResumeAndTerminate() {
        val processes = Processes()
        val original = processes.add(10)
        processes.onSignal = { _, _ -> processes.add(10, started = 20) }
        val result = processes.cleanup.terminate { entries -> entries.filter { it.identity == original.identity } }
        assertTrue(result.complete)
        assertEquals(listOf(10 to 18), processes.signals)
    }

    @Test fun zombiesCountAsExitedWithoutWaitingForTheirParentToReapThem() {
        val processes = Processes()
        val process = processes.add(10)
        processes.onSleep = { processes.entries[10] = process.copy(state = 'Z') }
        val result = processes.cleanup.terminate { it }
        assertTrue(result.complete)
        assertEquals(1, result.stopped)
        assertFalse(processes.signals.any { it.second == 9 })
    }

    @Test fun protectedRootAndDescendantsAreNotSignalled() {
        val processes = Processes()
        processes.add(10)
        val protected = processes.add(20)
        processes.add(21, parent = 20)
        processes.add(22, parent = 21)
        processes.onSleep = { processes.entries.remove(10) }
        val result = processes.cleanup.terminate { entries ->
            val spared = ProcessCleanup.descendants(entries, setOf(protected.identity))
            entries.filter { it.identity !in spared }
        }
        assertTrue(result.complete)
        assertEquals(1, result.stopped)
        assertEquals(setOf(20, 21, 22), processes.entries.keys)
        assertTrue(processes.signals.all { it.first == 10 })
    }

    @Test fun trackedDescendantsRemainTargetsAfterTheirParentExits() {
        val processes = Processes()
        val root = processes.add(10)
        val child = processes.add(11, parent = 10)
        val tracked = hashSetOf(root.identity)
        processes.onSleep = {
            processes.entries.remove(10)
            if (processes.time < 150) processes.entries[11] = child.copy(parent = 1)
            else processes.entries.remove(11)
        }
        val result = processes.cleanup.terminate { entries ->
            tracked.addAll(ProcessCleanup.descendants(entries, tracked))
            entries.filter { it.identity in tracked }
        }
        assertTrue(result.complete)
        assertEquals(2, result.stopped)
        assertTrue(child.identity in tracked)
    }

    @Test fun descendantsDoNotFollowReusedRootPids() {
        val processes = Processes()
        val original = processes.add(10)
        processes.add(10, started = 20)
        processes.add(11, parent = 10)
        assertTrue(ProcessCleanup.descendants(processes.entries.values.toList(), setOf(original.identity)).isEmpty())
    }

    @Test fun anEmptyPassAtTheDeadlineStillRequiresConfirmation() {
        val processes = Processes()
        processes.add(10)
        val deadline = ProcessCleanup.GRACE_MS + ProcessCleanup.KILL_WAIT_MS
        processes.onSleep = {
            if (processes.time == deadline) processes.entries.clear()
            if (processes.time > deadline) processes.add(11)
        }
        val result = processes.cleanup.terminate { it }
        assertFalse(result.complete)
        assertEquals(listOf(11), result.remaining.map { it.pid })
        assertEquals(deadline + 50, processes.time)
    }

    @Test(expected = java.io.IOException::class)
    fun failedProcessScanCannotReportSuccessfulCleanup() {
        ProcessCleanup(
            snapshot = { throw java.io.IOException("unreadable process list") },
            current = { null }, signal = { _, _ -> }, now = { 0 }, sleep = {},
        ).terminate { it }
    }

    @Test fun sessionCleanupTargetsItsSandboxesButSparesIndependentGuestCommands() {
        val sessionRoot = Processes().add(10)
        val sessionSandbox = Processes().add(20)
        val independentCommand = Processes().add(30).copy(executable = "/runtime/bin/proot")
        val independentSandbox = Processes().add(40, parent = 30).copy(executable = "/runtime/bin/proot")
        val processList = listOf(sessionRoot, sessionSandbox, independentCommand, independentSandbox)
        val targets = OrphanReaper.sessionTargets(
            processList, setOf(sessionRoot.identity), setOf(sessionSandbox.identity), "/runtime/",
        )
        val unprotected = OrphanReaper.unprotectedTargets(
            processList, targets, setOf(independentCommand.identity),
        )

        assertEquals(setOf(10, 20, 30, 40), targets.map { it.identity.pid }.toSet())
        assertEquals(setOf(10, 20), unprotected.map { it.identity.pid }.toSet())
    }
}
