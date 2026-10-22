package com.droiddeck.launcher.session

import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionSuspendControllerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun everyWorkerThreadIsStoppedAfterTheRoot() {
        for (count in listOf(17, 68)) {
            val fixture = fixture(count)
            fixture.controller.freeze { assertTrue(it) }
            assertTrue(fixture.game.threads.all { it.state == 't' })
            assertTrue(fixture.signals.count { it.pid == GAME && it.signal == STOP } >= count)
            assertEquals(ROOT, fixture.signals.first { it.signal == STOP }.pid)
            assertTrue(fixture.now < 1000)
            assertEquals(1, fixture.audioSuspends)
        }
    }

    @Test fun pageOutRunsInPreloadedGuestsBeforeTheyStop() {
        val fixture = fixture(17)
        fixture.control.writeText("0")
        fixture.controller.freeze(pageOut = true) { assertTrue(it) }
        val urgent = fixture.signals.filter { it.signal == URG }
        assertEquals(listOf(GAME), urgent.map { it.pid })
        assertTrue(fixture.signals.indexOfFirst { it.signal == URG } < fixture.signals.indexOfFirst { it.signal == STOP })
        assertEquals("1", fixture.control.readText())
        assertEquals(listOf(Triple(1, 1_000_000L, 3_000_000L)), fixture.pagedOut)
        assertEquals(0, fixture.unacknowledged)
        assertTrue(fixture.game.threads.all { it.state == 't' })
        assertFalse(File(fixture.control.parentFile, ".bl-zram.ack.$GAME").exists())
    }

    @Test fun unchangedSwapIsNotTakenForAFinishedPageOut() {
        val fixture = fixture(17)
        fixture.control.writeText("0")
        fixture.handlerStalls = true
        var stoppedEarly = false
        fixture.afterSleep = {
            if (fixture.now == 3000L) fixture.handlerStalls = false
            if (fixture.handlerStalls && fixture.signals.any { it.signal == STOP }) stoppedEarly = true
        }
        fixture.controller.freeze(pageOut = true) { assertTrue(it) }
        assertFalse(stoppedEarly)
        assertEquals(0, fixture.unacknowledged)
    }

    @Test fun replacedHandlerIsNotWaitedForPastTheStartWindow() {
        val fixture = fixture(17)
        fixture.control.writeText("0")
        fixture.handlerReplaced = true
        fixture.controller.freeze(pageOut = true) { assertTrue(it) }
        assertEquals(1, fixture.unacknowledged)
        assertTrue(fixture.now in 2000L..3000L)
    }

    @Test fun cancelledFreezeRollsBackWithoutStopping() {
        val fixture = fixture(17)
        fixture.control.writeText("0")
        fixture.handlerStalls = true
        fixture.afterSleep = { if (fixture.now >= 500) fixture.controller.cancelFreeze() }
        fixture.controller.freeze(pageOut = true) { assertFalse(it) }
        assertFalse(fixture.signals.any { it.signal == STOP })
        assertTrue(fixture.game.threads.all { it.state == 'S' })
        assertEquals(1, fixture.audioResumes)
    }

    @Test fun noPageOutWithoutTheRequestOrTheControlFile() {
        val visible = fixture(17)
        visible.control.writeText("0")
        visible.controller.freeze { assertTrue(it) }
        assertFalse(visible.signals.any { it.signal == URG })
        assertEquals("0", visible.control.readText())
        val disabled = fixture(17)
        disabled.controller.freeze(pageOut = true) { assertTrue(it) }
        assertFalse(disabled.signals.any { it.signal == URG })
        assertTrue(disabled.pagedOut.isEmpty())
    }

    @Test fun cpuMustBeStableAcrossTwoCompleteSnapshots() {
        val fixture = fixture(17)
        var changes = 0
        fixture.afterSleep = {
            if (fixture.game.threads.all { it.state == 't' } && changes < 3) {
                fixture.game.threads.last().cpu++
                changes++
            }
        }
        fixture.controller.freeze { assertTrue(it) }
        assertEquals(3, changes)
        assertTrue(fixture.signals.count { it.pid == GAME && it.signal == STOP } >= 17)
    }

    @Test fun reusedTidAndNewThreadMustJoinTheStableInventory() {
        val fixture = fixture(17)
        var changed = false
        fixture.afterSleep = {
            if (!changed && fixture.game.threads.all { it.state == 't' }) {
                changed = true
                fixture.game.threads.last().apply { started++; state = 'S'; cpu = 0 }
                fixture.game.threads += Task(500, 9000, 'S')
            }
        }
        fixture.controller.freeze { assertTrue(it) }
        assertTrue(changed)
        assertTrue(fixture.game.threads.all { it.state == 't' })
        assertTrue(fixture.signals.count { it.pid == GAME && it.signal == STOP } >= 19)
    }

    @Test fun zombieLeaderDoesNotHideRunningWorkers() {
        val fixture = fixture(17)
        fixture.game.threads.first().state = 'Z'
        fixture.sync()
        fixture.controller.freeze { assertTrue(it) }
        assertEquals('Z', fixture.game.threads.first().state)
        assertTrue(fixture.game.threads.drop(1).all { it.state == 't' })
        assertTrue(fixture.signals.count { it.pid == GAME && it.signal == STOP } >= 16)
    }

    @Test fun lateReparentedTraceeIsDiscoveredAndResumed() {
        val fixture = fixture(17)
        val late = Group(600, 1, ROOT, mutableListOf(Task(600, 6000, 'S')))
        fixture.afterSleep = {
            if (fixture.root.threads.first().state == 'T' && late !in fixture.groups) fixture.groups += late
        }
        fixture.controller.freeze { assertTrue(it) }
        assertEquals('t', late.threads.first().state)
        fixture.controller.resume { assertTrue(it) }
        assertEquals('S', late.threads.first().state)
    }

    @Test fun descendantHelperKeepsRootStopAndResumeOrdering() {
        val fixture = fixture(17)
        val helper = Group(700, ROOT, 0, mutableListOf(Task(700, 7000, 'S')))
        fixture.groups += helper
        fixture.helpers += helper.pid
        fixture.sync()
        fixture.controller.freeze { assertTrue(it) }
        val stops = fixture.signals.filter { it.signal == STOP }
        assertTrue(stops.indexOfFirst { it.pid == ROOT } < stops.indexOfFirst { it.pid == helper.pid })
        val before = fixture.signals.size
        fixture.controller.resume { assertTrue(it) }
        assertEquals(listOf(ROOT, helper.pid, GAME), fixture.signals.drop(before).take(3).map { it.pid })
    }

    @Test fun retainedHelperDoesNotAdoptAReusedPidOnRefreeze() {
        val fixture = fixture(17)
        val helper = Group(700, 9000, 0, mutableListOf(Task(700, 7000, 'S')))
        fixture.groups += helper
        fixture.helpers += helper.pid
        fixture.sync()
        fixture.controller.freeze { assertTrue(it) }
        fixture.blockGameContinue = true
        fixture.controller.resume { assertFalse(it) }
        helper.threads.first().apply { started++; state = 'S' }
        fixture.sync()
        val before = fixture.signals.size
        fixture.controller.freeze { assertTrue(it) }
        assertFalse(fixture.signals.drop(before).any { it.pid == helper.pid })
    }

    @Test fun timeoutRollsBackAllKnownProcessesAndAudio() {
        val fixture = fixture(17)
        fixture.neverStop = true
        fixture.controller.freeze { assertFalse(it) }
        assertTrue(fixture.now <= 8100)
        assertEquals(ROOT, fixture.signals.first { it.signal == CONT }.pid)
        assertTrue(fixture.game.threads.all { it.state == 'S' })
        assertEquals(1, fixture.audioResumes)
    }

    @Test fun missingKnownStatCannotBeMistakenForAnExitedProcess() {
        val fixture = fixture(17)
        fixture.afterSleep = {
            if (fixture.root.threads.first().state == 'T') fixture.missingGameStat = true
        }
        fixture.controller.freeze { assertFalse(it) }
        assertEquals(1, fixture.audioResumes)
        fixture.missingGameStat = false
        fixture.sync()
        fixture.controller.resume { assertTrue(it) }
        assertTrue(fixture.game.threads.all { it.state == 'S' })
    }

    @Test fun unavailableOrEmptyThreadInventoryCannotReportSuccess() {
        for (empty in listOf(false, true)) {
            val fixture = fixture(17)
            fixture.emptyGameTasks = empty
            fixture.missingGameTasks = !empty
            fixture.sync()
            fixture.controller.freeze { assertFalse(it) }
            assertEquals(1, fixture.audioResumes)
            assertTrue(fixture.game.threads.all { it.state == 'S' })
        }
    }

    @Test fun disappearingRootFailsAndStillResumesKnownChildren() {
        val fixture = fixture(17)
        fixture.afterSleep = {
            if (fixture.root.threads.first().state == 'T') fixture.rootGone = true
        }
        fixture.controller.freeze { assertFalse(it) }
        assertTrue(fixture.game.threads.all { it.state == 'S' })
        assertEquals(1, fixture.audioResumes)
    }

    @Test fun replacementRootIsNeverStoppedOrContinued() {
        val fixture = fixture(17)
        var atReplacement = -1
        fixture.afterSleep = {
            if (atReplacement < 0 && fixture.root.threads.first().state == 'T') {
                atReplacement = fixture.signals.size
                fixture.root.threads.first().apply { started++; state = 'S' }
            }
        }
        fixture.controller.freeze { assertFalse(it) }
        assertTrue(atReplacement > 0)
        assertFalse(fixture.signals.drop(atReplacement).any { it.pid == ROOT })
        assertTrue(fixture.game.threads.all { it.state == 'S' })
    }

    @Test fun reusedChildPidIsNotContinuedDuringResume() {
        val fixture = fixture(17)
        fixture.controller.freeze { assertTrue(it) }
        fixture.game.threads.first().apply { started++; state = 'S' }
        fixture.game.parent = 1
        fixture.game.tracer = 0
        fixture.sync()
        val before = fixture.signals.size
        fixture.controller.resume { assertTrue(it) }
        assertFalse(fixture.signals.drop(before).any { it.pid == GAME })
    }

    @Test fun closeCancelsFreezeAndRestoresSavedRootAfterServiceClearsIt() {
        val executor = QueueExecutor()
        val fixture = fixture(68, executor)
        var closed = false
        var requested = false
        fixture.afterSleep = {
            if (!requested && fixture.root.threads.first().state == 'T') {
                requested = true
                fixture.rootPid = -1
                fixture.controller.closeAndResume { closed = true }
            }
        }
        fixture.controller.freeze { assertFalse(it) }
        executor.drain()
        assertTrue(closed)
        assertTrue(executor.isShutdown)
        assertTrue(fixture.game.threads.all { it.state == 'S' })
        assertEquals(1, fixture.audioResumes)
    }

    @Test fun successfulFreezeIsRecheckedAndAudioIsReassertedAfterFailedResume() {
        val fixture = fixture(17)
        fixture.controller.freeze { assertTrue(it) }
        fixture.blockGameContinue = true
        fixture.controller.resume { assertFalse(it) }
        assertEquals(1, fixture.audioResumes)
        fixture.blockGameContinue = false
        fixture.game.threads.last().state = 'S'
        fixture.sync()
        fixture.controller.freeze { assertTrue(it) }
        assertTrue(fixture.game.threads.all { it.state == 't' })
        assertEquals(2, fixture.audioSuspends)
    }

    @Test fun failedRollbackCanBeRefrozenAndAudioIsSuspendedAgain() {
        val fixture = fixture(17)
        fixture.neverStop = true
        fixture.blockGameContinue = true
        fixture.controller.freeze { assertFalse(it) }
        fixture.neverStop = false
        fixture.blockGameContinue = false
        fixture.controller.freeze { assertTrue(it) }
        assertTrue(fixture.game.threads.all { it.state == 't' })
        assertEquals(2, fixture.audioSuspends)
        assertEquals(1, fixture.audioResumes)
    }

    @Test fun repeatedFreezeResumeKeepsOriginalRootFirstOrdering() {
        val fixture = fixture(17)
        repeat(2) {
            fixture.controller.freeze { assertTrue(it) }
            val before = fixture.signals.size
            fixture.controller.resume { assertTrue(it) }
            assertEquals(ROOT, fixture.signals.drop(before).first().pid)
            assertTrue(fixture.game.threads.all { it.state == 'S' })
        }
        fixture.controller.freeze { assertTrue(it) }
        fixture.rootPid = -1
        var closed = false
        fixture.controller.closeAndResume { closed = true }
        assertTrue(closed)
        assertTrue(fixture.game.threads.all { it.state == 'S' })
    }

    @Test fun rejectedExecutorDoesNotFreezeOnTheCallingThread() {
        val executor = QueueExecutor().apply { shutdown() }
        val fixture = fixture(17, executor)
        fixture.controller.freeze { assertFalse(it) }
        assertTrue(fixture.signals.isEmpty())
        assertEquals(0, fixture.audioSuspends)
    }

    private fun fixture(count: Int, executor: QueueExecutor = InlineExecutor()) =
        Fixture(temporary.newFolder(), temporary.newFolder(), count, executor)

    private data class Task(val tid: Int, var started: Long, var state: Char, var cpu: Long = 0)
    private data class Group(val pid: Int, var parent: Int, var tracer: Int, val threads: MutableList<Task>)
    private data class SentSignal(val pid: Int, val signal: Int)

    private class Fixture(val proc: File, tmp: File, count: Int, executor: QueueExecutor) {
        val root = Group(ROOT, 9000, 0, mutableListOf(Task(ROOT, 1000, 'S')))
        val game = Group(GAME, ROOT, ROOT, MutableList(count) { Task(GAME + it, 2000L + it, 'S') })
        val groups = mutableListOf(root, game)
        val helpers = mutableListOf<Int>()
        val signals = mutableListOf<SentSignal>()
        var rootPid = ROOT
        var now = 0L
        var audioSuspends = 0
        var audioResumes = 0
        var neverStop = false
        var blockGameContinue = false
        var missingGameStat = false
        var missingGameTasks = false
        var emptyGameTasks = false
        var rootGone = false
        var afterSleep: (() -> Unit)? = null
        val control = File(tmp, ".bl-zram")
        val pagedOut = mutableListOf<Triple<Int, Long, Long>>()
        private var gameResidentKb = 4_000_000L
        private var gameSwapKb = 0L
        private var pagingOut = false
        var unacknowledged = -1
        var handlerStalls = false
        var handlerReplaced = false
        private var continuing = false
        val controller = SessionSuspendController(
            { rootPid }, { helpers }, { audioSuspends++ }, { audioResumes++ },
            proc, ::signal, ::threadSignal, ::sleep, { now }, executor,
            zramControl = control,
            pagedOut = { processes, unacknowledged, resident, swap, _, _ ->
                pagedOut += Triple(processes, resident, swap)
                this.unacknowledged = unacknowledged
            },
        )

        init { sync() }

        private fun signal(pid: Int, signal: Int) {
            signals += SentSignal(pid, signal)
            val group = groups.first { it.pid == pid }
            if (signal == STOP) {
                continuing = false
                if (pid == ROOT) group.threads.forEach { it.state = 'T' }
                else group.threads.firstOrNull {
                    it.state !in listOf('T', 't', 'Z', 'X') && !(neverStop && it == game.threads.last())
                }?.state = 't'
            } else if (signal == URG) {
                pagingOut = true
            } else if (signal == CONT) {
                continuing = true
                if (!(pid == GAME && blockGameContinue)) group.threads.forEach { if (it.state != 'Z') it.state = 'S' }
            }
            sync()
        }

        private fun threadSignal(pid: Int, tid: Int, signal: Int) {
            signals += SentSignal(pid, signal)
            val task = groups.first { it.pid == pid }.threads.first { it.tid == tid }
            if (signal == STOP && task.state !in listOf('T', 't', 'Z', 'X') && !(neverStop && task == game.threads.last())) {
                task.state = 't'
            }
            sync()
        }

        private fun sleep(millis: Long) {
            now += millis
            if (pagingOut && !handlerReplaced) {
                File(control.parentFile, "${control.name}.ack.$GAME").apply { if (!exists()) writeText("") }
            }
            if (pagingOut && !handlerStalls && !handlerReplaced) {
                if (gameResidentKb > 1_000_000L) {
                    gameResidentKb -= 1_000_000L
                    gameSwapKb += 1_000_000L
                } else {
                    File(control.parentFile, "${control.name}.ack.$GAME").writeText("1")
                    pagingOut = false
                }
            }
            if (root.threads.first().state != 'T' && !continuing) game.threads.forEach {
                if (it.state != 'Z') { it.state = 'S'; it.cpu++ }
            }
            afterSleep?.invoke()
            sync()
        }

        fun sync() {
            for (group in groups) {
                val dir = File(proc, group.pid.toString())
                if (group == root && rootGone) { dir.deleteRecursively(); continue }
                dir.mkdirs()
                val stat = File(dir, "stat")
                if (group == game && missingGameStat) stat.delete()
                else stat.writeText(stat(group, group.threads.first()))
                File(dir, "status").writeText(
                    "TracerPid: ${group.tracer}\n" +
                        if (group == game) "VmRSS:\t$gameResidentKb kB\nVmSwap:\t$gameSwapKb kB\n" else "",
                )
                if (group == game) File(dir, "maps").writeText("7f00000000-7f00001000 r-xp 00000000 fe:05 42  /usr/local/lib/libblsession.so\n")
                val taskDir = File(dir, "task")
                if (group == game && missingGameTasks) { taskDir.deleteRecursively(); continue }
                taskDir.mkdirs()
                if (group == game && emptyGameTasks) { taskDir.listFiles().orEmpty().forEach { it.deleteRecursively() }; continue }
                for (task in group.threads) {
                    val entry = File(taskDir, task.tid.toString()).apply { mkdirs() }
                    File(entry, "stat").writeText(stat(group, task))
                }
            }
        }

        private fun stat(group: Group, task: Task): String {
            val fields = MutableList(20) { "0" }
            fields[0] = task.state.toString()
            fields[1] = group.parent.toString()
            fields[11] = task.cpu.toString()
            fields[19] = task.started.toString()
            return "${task.tid} (fixture (worker)) ${fields.joinToString(" ")}"
        }
    }

    private open class QueueExecutor : AbstractExecutorService() {
        private val queue = ArrayDeque<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) {
            if (stopped) throw RejectedExecutionException()
            queue.add(command)
        }
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            return queue.toMutableList().also { queue.clear() }
        }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && queue.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }

    private class InlineExecutor : QueueExecutor() {
        override fun execute(command: Runnable) { super.execute(command); drain() }
    }

    private companion object {
        const val ROOT = 100
        const val GAME = 200
        const val STOP = 19
        const val CONT = 18
        const val URG = 23
    }
}
