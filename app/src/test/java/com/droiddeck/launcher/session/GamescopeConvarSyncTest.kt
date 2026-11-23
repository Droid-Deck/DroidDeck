package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GamescopeConvarSyncTest {
    private var wanted: Int? = 0
    private var accept = true
    private val sent = mutableListOf<Pair<String, Int>>()
    private val retries = mutableListOf<Runnable>()

    private fun sync(applied: Int? = null) = GamescopeConvarSync(
        "debug_set_fps_limit", { wanted },
        execute = { it.run() },
        retryLater = { retries += it },
        set = { name, value -> sent += name to value; accept },
        maxRetries = 2,
        applied = applied,
    )

    @Test fun aNewValueIsSentOnce() {
        val sync = sync()
        wanted = 60
        sync.restart()
        sync.request()
        assertEquals(listOf("debug_set_fps_limit" to 60), sent)
        assertEquals(60, sync.applied)
    }

    @Test fun theValueGamescopeAlreadyHasIsNotSent() {
        wanted = -1
        sync(applied = -1).restart()
        assertEquals(emptyList<Pair<String, Int>>(), sent)
    }

    @Test fun nothingIsSentWhileTheConvarIsNotOurs() {
        wanted = null
        val sync = sync()
        sync.restart()
        assertEquals(emptyList<Pair<String, Int>>(), sent)
        assertNull(sync.applied)
    }

    @Test fun aRefusedValueIsRetriedAFewTimesThenLeft() {
        accept = false
        wanted = 60
        sync().restart()
        while (retries.isNotEmpty()) retries.removeAt(0).run()
        assertEquals(3, sent.size)
    }

    @Test fun aRestartRetriesAgainAfterTheAgentComesBack() {
        accept = false
        wanted = 60
        val sync = sync()
        sync.restart()
        while (retries.isNotEmpty()) retries.removeAt(0).run()
        accept = true
        sync.restart()
        assertEquals(4, sent.size)
        assertEquals(60, sync.applied)
    }

    @Test fun forgettingTheAppliedValueSendsItAgain() {
        wanted = 60
        val sync = sync()
        sync.restart()
        sync.applied = null
        sync.restart()
        assertEquals(listOf("debug_set_fps_limit" to 60, "debug_set_fps_limit" to 60), sent)
    }

    @Test fun clearingTheLimitIsSent() {
        wanted = 60
        val sync = sync()
        sync.restart()
        wanted = 0
        sync.restart()
        assertEquals(listOf("debug_set_fps_limit" to 60, "debug_set_fps_limit" to 0), sent)
    }
}
