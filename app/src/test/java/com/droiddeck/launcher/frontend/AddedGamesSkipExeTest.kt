package com.droiddeck.launcher.frontend

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddedGamesSkipExeTest {
    @Test fun gameTitlesContainingShortSkipWordsAreKept() {
        listOf("BeachBuggyRacing.exe", "Reach.exe", "Breach.exe", "Crashday.exe", "CrashBandicoot.exe",
            "Observer.exe", "Observer-Win64-Shipping.exe", "Game.exe").forEach { assertFalse(it, AddedGames.skipExe(it)) }
    }

    @Test fun installersToolsAndServersAreSkipped() {
        listOf("unins000.exe", "setup.exe", "vc_redist.x64.exe", "DXSETUP.exe", "CrashReportClient.exe",
            "UnityCrashHandler64.exe", "gamelaunchhelper.exe", "EACLauncher.exe", "DedicatedServer.exe",
            "UE4Editor.exe", "valheim_server.exe", "GameConfig.exe", "GameConfigure.exe", "Settings.exe", "Updater.exe",
            "EasyAntiCheat_Setup.exe").forEach { assertTrue(it, AddedGames.skipExe(it)) }
    }
}
