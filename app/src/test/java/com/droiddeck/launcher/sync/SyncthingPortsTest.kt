package com.droiddeck.launcher.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncthingPortsTest {
    @Test
    fun skipsBusyPorts() {
        val busy = setOf(18384, 18385, 22100)
        val picked = SyncthingPortPicker.pick({ it !in busy }, { it !in busy }, { it !in busy })
        assertEquals(SyncthingPorts(18386, 22101, 21127), picked)
    }

    @Test(expected = IllegalStateException::class)
    fun failsWhenNoPortIsFree() {
        SyncthingPortPicker.pick({ false }, { true }, { true })
    }
}
