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
}
