package com.droiddeck.launcher.frontend

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class SoundtrackOrderTest {
    private fun track(name: String, disc: Int, number: Int) = Soundtrack.Track(File("/music/$name"), name, disc, number, 1000L)

    @Test fun savedOrderIsAppliedAndRenumbered() {
        val disc = mapOf(1 to listOf(track("a", 1, 1), track("b", 1, 2), track("c", 1, 3)))
        val result = SoundtrackOrder.ordered(disc, listOf("/music/c", "/music/a", "/music/b"))
        assertEquals(listOf("c", "a", "b"), result.getValue(1).map { it.title })
        assertEquals(listOf(1, 2, 3), result.getValue(1).map { it.number })
    }

    @Test fun unknownTracksKeepTheirOrderAfterKnownOnes() {
        val disc = mapOf(1 to listOf(track("a", 1, 1), track("b", 1, 2), track("c", 1, 3)))
        val result = SoundtrackOrder.ordered(disc, listOf("/music/c"))
        assertEquals(listOf("c", "a", "b"), result.getValue(1).map { it.title })
    }

    @Test fun discsStayApart() {
        val disc = mapOf(1 to listOf(track("a", 1, 1), track("b", 1, 2)), 2 to listOf(track("c", 2, 1), track("d", 2, 2)))
        val result = SoundtrackOrder.ordered(disc, listOf("/music/d", "/music/b", "/music/a", "/music/c"))
        assertEquals(listOf("b", "a"), result.getValue(1).map { it.title })
        assertEquals(listOf("d", "c"), result.getValue(2).map { it.title })
    }

    @Test fun emptyOrderChangesNothing() {
        val disc = mapOf(1 to listOf(track("a", 1, 1), track("b", 1, 2)))
        assertEquals(disc, SoundtrackOrder.ordered(disc, emptyList()))
    }
}
