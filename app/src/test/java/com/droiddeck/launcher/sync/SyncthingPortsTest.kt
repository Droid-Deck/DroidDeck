package com.droiddeck.launcher.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncthingPortsTest {
    @Test
    fun skipsBusyPorts() {
        val busy = setOf(22100, 21127)
        val picked = SyncthingPortPicker.pick({ it !in busy }, { it !in busy })
        assertEquals(SyncthingPorts(22101, 21128), picked)
    }

    @Test(expected = IllegalStateException::class)
    fun failsWhenNoPortIsFree() {
        SyncthingPortPicker.pick({ false }, { true })
    }
}
