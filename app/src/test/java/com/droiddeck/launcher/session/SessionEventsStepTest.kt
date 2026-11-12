package com.droiddeck.launcher.session

import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionEventsStepTest {
    private lateinit var dir: File

    @Before fun open() {
        dir = File.createTempFile("session", "").apply { delete(); mkdirs() }
        SessionState.logDirectory = dir
        SessionState.eventsFile = File(dir, "events.jsonl")
    }

    @After fun close() {
        SessionState.logDirectory = null
        SessionState.eventsFile = null
        dir.deleteRecursively()
    }

    private fun events() = File(dir, "events.jsonl").readLines().map(::JSONObject)

    @Test fun stepRecordsBeginAndEndAroundTheBlockAndReturnsItsValue() {
        assertEquals(7, SessionEvents.step("probe") { 7 })
        val lines = events()
        assertEquals(listOf("begin", "end"), lines.map { it.getString("state") })
        assertTrue(lines.all { it.getString("event") == "start.step" && it.getString("step") == "probe" })
        assertTrue(lines[1].getLong("ms") >= 0)
    }

    @Test fun stepStillRecordsTheEndWhenTheBlockThrows() {
        runCatching { SessionEvents.step("probe") { error("boom") } }
        assertEquals(listOf("begin", "end"), events().map { it.getString("state") })
    }

    @Test fun stepKeepsBothEventsInTheSessionWhereItStarted() {
        val other = File.createTempFile("session", "").apply { delete(); mkdirs() }
        try {
            SessionEvents.step("probe") {
                SessionState.logDirectory = other
                SessionState.eventsFile = File(other, "events.jsonl")
            }
            assertEquals(listOf("begin", "end"), events().map { it.getString("state") })
            assertTrue(!File(other, "events.jsonl").exists())
        } finally {
            other.deleteRecursively()
        }
    }

    @Test fun stepPropagatesTheOriginalExceptionUnchanged() {
        val failure = IllegalStateException("boom")
        val thrown = runCatching { SessionEvents.step("probe") { throw failure } }.exceptionOrNull()
        assertTrue(thrown === failure)
    }

    @Test fun stepsOfDifferentLaunchesAreRecordedIndependentlyInOrder() {
        SessionEvents.step("controller-environment") { }
        SessionEvents.step("guest-launch") { }
        assertEquals(
            listOf("controller-environment", "controller-environment", "guest-launch", "guest-launch"),
            events().map { it.getString("step") },
        )
    }

    @Test fun stepRecordsNothingElseAndLeavesSessionStateUntouched() {
        SessionState.running = false
        SessionEvents.step("probe") { }
        assertEquals(false, SessionState.running)
        assertEquals(2, events().size)
    }
}
