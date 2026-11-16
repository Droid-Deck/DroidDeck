package com.droiddeck.launcher.input

import org.junit.Assert.assertEquals
import org.junit.Test

class FakeInputImuModeTest {
    private fun set(mode: Int) = Int.MIN_VALUE or mode

    @Test fun motionIsUnknownUntilEitherProcessSetsAMode() {
        assertEquals(-1, ImuModes.combine(0, 0, GONE))
    }

    @Test fun motionFollowsSteamAloneBeforeRemotePlayStarts() {
        assertEquals(0x18, ImuModes.combine(set(0x18), 0, GONE))
        assertEquals(0, ImuModes.combine(set(0), 0, GONE))
    }

    @Test fun motionStaysOnWhileEitherProcessWantsIt() {
        assertEquals(0x18, ImuModes.combine(set(0), set(0x18), RUNNING))
        assertEquals(0x18, ImuModes.combine(set(0x18), set(0), RUNNING))
    }

    @Test fun remotePlayDecidesWhileSteamHasSetNoMode() {
        assertEquals(0, ImuModes.combine(0, set(0), RUNNING))
        assertEquals(0x18, ImuModes.combine(0, set(0x18), RUNNING))
    }

    @Test fun aRemotePlayClientThatHasGoneIsIgnored() {
        assertEquals(0, ImuModes.combine(set(0), set(0x18), GONE))
        assertEquals(-1, ImuModes.combine(0, set(0x18), GONE))
    }

    private companion object {
        const val RUNNING = true
        const val GONE = false
    }
}
