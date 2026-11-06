package com.droiddeck.launcher.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WowChatTest {
    @Test
    fun everyWowClientMatches() {
        listOf("wow.exe", "Wow.exe", "wowclassic.exe", "wowclassict.exe", "wowb.exe", "wow-64.exe", "wowt.exe").forEach {
            assertTrue(it, WowChat.isWow(it))
        }
    }

    @Test
    fun otherProgramsAreUnaffected() {
        listOf(null, "", "battle.net.exe", "steam.exe", "notwow.exe", "wow.exe.bak", "wow", "cyberpunk2077.exe").forEach {
            assertFalse(it.toString(), WowChat.isWow(it))
        }
    }
}
