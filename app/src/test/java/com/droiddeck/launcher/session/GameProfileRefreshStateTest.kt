package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameProfileRefreshStateTest {
    @Test
    fun firstFailureKeepsInitialStateWithoutCachedData() {
        val state = GameProfileRefreshState<String>().failed()

        assertTrue(state.failed)
        assertFalse(state.loaded)
        assertNull(state.data)
    }

    @Test
    fun failureAfterSuccessPreservesCachedData() {
        val state = GameProfileRefreshState<String>().succeeded("cached profiles").failed()

        assertTrue(state.failed)
        assertTrue(state.loaded)
        assertEquals("cached profiles", state.data)
    }

    @Test
    fun successAfterFailureReplacesCachedDataAndClearsWarning() {
        val state = GameProfileRefreshState<String>()
            .succeeded("old profiles")
            .failed()
            .succeeded("new profiles")

        assertFalse(state.failed)
        assertTrue(state.loaded)
        assertEquals("new profiles", state.data)
    }

    @Test
    fun retryingClearsWarningWithoutDiscardingCachedData() {
        val state = GameProfileRefreshState<String>().succeeded("cached profiles").failed().retrying()

        assertFalse(state.failed)
        assertTrue(state.loaded)
        assertEquals("cached profiles", state.data)
    }
}
