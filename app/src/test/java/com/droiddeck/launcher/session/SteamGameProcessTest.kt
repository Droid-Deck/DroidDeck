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

    @Test fun steamsInstallScriptStepIsNotTheGame() {
        val wrapper = arrayOf("/root/.local/share/Steam/linuxarm64/steam-launch-wrapper", "--oom-score-adjust", "900", "--")
        val reaper = "/root/.local/share/Steam/steamrtarm64/reaper"
        val proton = "/root/.local/share/Steam/compatibilitytools.d/DroidDeck-Proton 11-12.8/droiddeck-proton-wrap"
        val evaluator = arrayOf(reaper, "SteamLaunch", "AppId=351710", "Install=1", "--",
            proton, "run", "/root/.local/share/Steam/legacycompat/iscriptevaluator.exe", "legacycompat\\evaluatorscript_351710.vdf")
        val game = arrayOf(reaper, "SteamLaunch", "AppId=351710", "--",
            proton, "waitforexitandrun", "/root/.local/share/Steam/steamapps/common/Neptunia Rebirth2/NeptuniaReBirth2.exe")

        assertFalse(SteamGameProcess.matches(cmdline(*evaluator), "351710"))
        assertFalse(SteamGameProcess.matches(cmdline(*wrapper, *evaluator), "351710"))
        assertTrue(SteamGameProcess.matches(cmdline(*game), "351710"))
        assertTrue(SteamGameProcess.matches(cmdline(*wrapper, *game), "351710"))
        assertTrue(SteamGameProcess.matches(cmdline(reaper, "SteamLaunch", "AppId=351710", "--", "game", "Install=1"), "351710"))
    }

    @Test fun shortcutCGameIdsMatchTheir32BitSteamAppId() {
        val cgameId = ((976310L shl 32) or 0x02000000L).toString()

        assertTrue(
            SteamGameProcess.matches(
                cmdline("reaper", "SteamLaunch", "AppId=976310", "--", "proton", "waitforexitandrun", "game.exe"),
                cgameId,
            )
        )

        assertTrue(
            SteamGameProcess.matches(
                cmdline("steam", "-applaunch", "976310"),
                cgameId,
            )
        )

        assertFalse(
            SteamGameProcess.matches(
                cmdline("reaper", "SteamLaunch", "AppId=123", "--", "proton", "waitforexitandrun", "game.exe"),
                cgameId,
            )
        )
    }

    @Test fun runningLooksAtEveryProcess() {
        val proc = tmp.newFolder("proc")
        fun process(pid: String, vararg args: String) = File(proc, pid).apply { mkdirs() }.resolve("cmdline").writeBytes(cmdline(*args))
        process("100", "pgrep", "-a", "-f", "SteamLaunch AppId=[0-9]")
        process("self", "reaper", "SteamLaunch", "AppId=1")
        assertFalse(SteamGameProcess.running("976310", proc))
        assertFalse(SteamGameProcess.running("1", proc))
        process("101", "reaper", "SteamLaunch", "AppId=1")
        assertTrue(SteamGameProcess.running("1", proc))
        process("200", "reaper", "SteamLaunch", "AppId=976310", "--", "proton", "waitforexitandrun", "MK11.exe")
        assertTrue(SteamGameProcess.running("976310", proc))
        assertFalse(SteamGameProcess.running("123", proc))
        process("300", "reaper", "SteamLaunch", "AppId=351710", "Install=1", "--", "proton", "run", "iscriptevaluator.exe")
        assertFalse(SteamGameProcess.running("351710", proc))
        process("301", "reaper", "SteamLaunch", "AppId=351710", "--", "proton", "waitforexitandrun", "game.exe")
        assertTrue(SteamGameProcess.running("351710", proc))
    }
}
