package com.droiddeck.launcher.input

import org.junit.Assert.assertEquals
import org.junit.Test

class HeldInputTest {
    @Test
    fun normalRoutingUsesDefaultEmitter() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val input = HeldInput { code, down -> events.add(code to down) }
        input.set(1, 30, true)
        input.set(1, 30, false)
        assertEquals(listOf(30 to true, 30 to false), events)
    }

    @Test
    fun orderedSenderHandlesDownRepeatedDownSecondOwnerAndFinalRelease() {
        val defaultEvents = mutableListOf<Pair<Int, Boolean>>()
        val orderedEvents = mutableListOf<Pair<Int, Boolean>>()
        val input = HeldInput { code, down -> defaultEvents.add(code to down) }
        val ordered: (Int, Boolean) -> Unit = { code, down -> orderedEvents.add(code to down) }
        input.set(1, 30, true, ordered)
        input.set(1, 30, true, ordered)
        input.set(2, 30, true, ordered)
        input.set(1, 30, false)
        input.set(2, 30, false)
        assertEquals(listOf(30 to true, 30 to false), orderedEvents)
        assertEquals(emptyList<Pair<Int, Boolean>>(), defaultEvents)
    }

    @Test
    fun releaseChoosesMatchingRoute() {
        val defaultEvents = mutableListOf<Pair<Int, Boolean>>()
        val orderedEvents = mutableListOf<Pair<Int, Boolean>>()
        val input = HeldInput { code, down -> defaultEvents.add(code to down) }
        val ordered: (Int, Boolean) -> Unit = { code, down -> orderedEvents.add(code to down) }
        input.set(1, 31, true, ordered)
        input.set(2, 32, true)
        input.release(2)
        input.release(1)
        assertEquals(listOf(31 to true, 31 to false), orderedEvents)
        assertEquals(listOf(32 to true, 32 to false), defaultEvents)
    }

    @Test
    fun clearChoosesMatchingRoute() {
        val defaultEvents = mutableListOf<Pair<Int, Boolean>>()
        val orderedEvents = mutableListOf<Pair<Int, Boolean>>()
        val input = HeldInput { code, down -> defaultEvents.add(code to down) }
        val ordered: (Int, Boolean) -> Unit = { code, down -> orderedEvents.add(code to down) }
        input.set(1, 40, true, ordered)
        input.set(2, 41, true)
        input.clear()
        assertEquals(listOf(40 to true, 40 to false), orderedEvents)
        assertEquals(listOf(41 to true, 41 to false), defaultEvents)
    }
}
