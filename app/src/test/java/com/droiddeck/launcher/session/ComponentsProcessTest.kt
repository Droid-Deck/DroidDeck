package com.droiddeck.launcher.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentsProcessTest {
    private val guestPath = "/root/.local/share/Steam/steamapps/common/Proton"
    private val hostPath = "/data/user/0/com.droiddeck.launcher.dev/files/root/root/.local/share/Steam/steamapps/common/Proton"

    @Test
    fun `running game is recognized through guest path`() {
        val command = "python3 $guestPath/proton waitforexitandrun game.exe"

        assertTrue(ComponentsManager.processUsesProton(listOf(guestPath, hostPath), command, gameOnly = true))
    }

    @Test
    fun `running game is recognized through Android host path`() {
        val command = "python3 $hostPath/proton waitforexitandrun game.exe"

        assertTrue(ComponentsManager.processUsesProton(listOf(guestPath, hostPath), command, gameOnly = true))
    }

    @Test
    fun `Steam startup Proton process is not reported as a running game`() {
        val command = "python3 $hostPath/proton run wineboot.exe"

        assertFalse(ComponentsManager.processUsesProton(listOf(guestPath, hostPath), command, gameOnly = true))
        assertTrue(ComponentsManager.processUsesProton(listOf(guestPath, hostPath), command, gameOnly = false))
    }

    @Test
    fun `unrelated Proton process is ignored`() {
        val command = "python3 /other/Proton/proton waitforexitandrun game.exe"

        assertFalse(ComponentsManager.processUsesProton(listOf(guestPath, hostPath), command, gameOnly = true))
    }
}
