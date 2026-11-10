package com.droiddeck.launcher.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SteamGameProcessTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun cmdline(vararg args: String) = args.joinToString("\u0000", postfix = "\u0000").toByteArray()

    @Test fun theSessionsOwnSearchesAreNotAGame() {
        assertFalse(SteamGameProcess.matches(cmdline("pgrep", "-a", "-f", "SteamLaunch AppId=[0-9]"), "976310"))
        assertFalse(SteamGameProcess.matches(cmdline("pgrep", "-f", "reaper SteamLaunch"), "976310"))
        assertFalse(SteamGameProcess.matches(cmdline("pgrep", "-f", "reaper SteamLaunch AppId=976310( |$)"), "976310"))
        assertFalse(SteamGameProcess.matches(cmdline("sh", "-c", "pgrep -f 'proton waitforexitandrun'"), "976310"))
        assertFalse(SteamGameProcess.matches(cmdline("/usr/bin/steam", "steam://rungameid/976310"), "976310"))
        assertFalse(SteamGameProcess.matches(cmdline("reaper", "SteamLaunch", "AppId=976310"), ""))
        assertFalse(SteamGameProcess.matches(cmdline("reaper", "SteamLaunch", "AppId=976310"), "abc"))
    }

    @Test fun steamsReaperAndProtonAreAGame() {
        assertTrue(SteamGameProcess.matches(cmdline("/root/.local/share/Steam/steamrtarm64/reaper", "SteamLaunch", "AppId=976310", "--",
            "/root/.local/share/Steam/compatibilitytools.d/droiddeck-proton-arm64/proton", "waitforexitandrun", "MK11.exe"), "976310"))
        assertFalse(SteamGameProcess.matches(cmdline("/root/.local/share/Steam/steamrtarm64/reaper", "SteamLaunch", "AppId=976310", "--",
            "/root/.local/share/Steam/compatibilitytools.d/droiddeck-proton-arm64/proton", "waitforexitandrun", "MK11.exe"), "123"))
        assertFalse(SteamGameProcess.matches(cmdline("python3", "/proton", "waitforexitandrun", "game.exe"), "976310"))
    }

    @Test fun runningLooksAtEveryProcess() {
        val proc = tmp.newFolder("proc")
        fun process(pid: String, vararg args: String) = File(proc, pid).apply { mkdirs() }.resolve("cmdline").writeBytes(cmdline(*args))
        process("100", "pgrep", "-a", "-f", "SteamLaunch AppId=[0-9]")
        process("self", "reaper", "SteamLaunch", "AppId=1")
        assertFalse(SteamGameProcess.running("976310", proc))
        assertTrue(SteamGameProcess.running("1", proc))
        process("200", "reaper", "SteamLaunch", "AppId=976310", "--", "proton", "waitforexitandrun", "MK11.exe")
        assertTrue(SteamGameProcess.running("976310", proc))
        assertFalse(SteamGameProcess.running("123", proc))
    }
}
