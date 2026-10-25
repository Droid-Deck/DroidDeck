package com.droiddeck.launcher.session

import com.droiddeck.launcher.core.GameEnvironment
import com.droiddeck.launcher.frontend.AddedGames
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.stores.epic.EpicClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class NoSteamLaunchTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @After fun clean() {
        File(context.filesDir, "game-environment.json").delete()
        NoSteamLaunch.names.clear()
    }

    private val steamGame = Library.SteamGame(413150, "Stardew Valley", null, "internal")
    private val shortcut = Library.SteamGame((2733369024L).toInt(), "Hogwarts Legacy", null, Library.ADDED, source = "epic")
    private val launcher = Library.SteamGame(EpicClient.LAUNCHER_APP_ID, Library.EPIC_LAUNCHER_NAME, null, Library.EPIC_LAUNCHER, source = "epic")

    @Test fun eachKindOfGameKeepsTheSettingUnderItsOwnId() {
        assertEquals("413150", NoSteamLaunch.scope(steamGame))
        assertEquals("2733369024", NoSteamLaunch.scope(shortcut))
        assertEquals(EpicClient.shortcutAppId.toString(), NoSteamLaunch.scope(launcher))
        assertNull(NoSteamLaunch.scope(Library.SteamGame(0, "nothing", null, "internal")))
    }

    @Test fun theSettingIsRememberedPerGame() {
        assertFalse(NoSteamLaunch.enabled(context, shortcut))
        GameEnvironmentStore.setGameNoSteam(context, "2733369024", true)
        assertTrue(NoSteamLaunch.enabled(context, shortcut))
        assertTrue(NoSteamLaunch.enabled(context, 2733369024L))
        assertFalse(NoSteamLaunch.enabled(context, steamGame))
        assertFalse(NoSteamLaunch.enabled(context, launcher))
        GameEnvironmentStore.setGameNoSteam(context, EpicClient.shortcutAppId.toString(), true)
        assertTrue(NoSteamLaunch.enabled(context, launcher))
        GameEnvironmentStore.setGameNoSteam(context, "2733369024", false)
        assertFalse(NoSteamLaunch.enabled(context, shortcut))
        assertTrue(GameEnvironment.gameNoSteam(GameEnvironmentStore.read(context), EpicClient.shortcutAppId.toString()))
    }

    @Test fun eachGameStartsInItsOwnProtonOrTheLauncherWithoutSteam() {
        val game = AddedGames.Game(
            File("/x/Hogwarts Legacy"), "Hogwarts Legacy", File("/x/Hogwarts Legacy/.droiddeck-launch.bat"),
            "/mnt/droiddeck-sd/Games/Epic/Hogwarts Legacy/.droiddeck-launch.bat", "/mnt/droiddeck-sd/Games/Epic/Hogwarts Legacy",
            2733369024L, 0L, emptyList(), source = "epic", launchOptions = "-windowed",
        )
        val added = NoSteamLaunch.command(shortcut, game)!!
        assertEquals(NoSteamLaunch.PROTON_RUN, added.program)
        assertEquals(
            listOf("--app", "2733369024", "--dir", game.guestDir, "--options", "-windowed", "--", game.guestExe),
            added.args,
        )
        val plain = NoSteamLaunch.addedCommand(AddedGames.Game(game.folder, game.name, game.exe, game.guestExe, game.guestDir, 2733369024L, 0L, emptyList(), source = "gog"))
        assertEquals(listOf("--app", "2733369024", "--dir", game.guestDir, "--", game.guestExe), plain.args)
        assertNull(NoSteamLaunch.command(shortcut, null))
        val steam = NoSteamLaunch.command(steamGame, null)!!
        assertEquals(listOf("--app", "413150"), steam.args)
        val epic = NoSteamLaunch.command(launcher, null)!!
        assertEquals(NoSteamLaunch.EPIC to listOf("run"), epic.program to epic.args)
    }

    @Test fun anAddedGameOwnedOnSteamUsesItsSteamIdForSettingsAndLaunch() {
        val routed = AddedGames.Game(
            File("/x/Stardew Valley"), "Stardew Valley", File("/x/Stardew Valley/Stardew Valley.exe"),
            "/mnt/droiddeck-sd/Games/Stardew Valley/Stardew Valley.exe", "/mnt/droiddeck-sd/Games/Stardew Valley",
            2733369024L, 413150L, emptyList(), steamAppId = 413150,
        )
        assertEquals(413150L, NoSteamLaunch.settingsId(routed))
        val entry = Library.SteamGame(413150, "Stardew Valley", null, Library.ADDED)
        assertEquals(NoSteamLaunch.settingsId(routed).toString(), NoSteamLaunch.scope(entry))
        val command = NoSteamLaunch.command(entry, routed)!!
        assertEquals(NoSteamLaunch.PROTON_RUN to listOf("--app", "413150"), command.program to command.args)
        val plain = AddedGames.Game(File("/x/a"), "a", File("/x/a/a.exe"), "/g/a.exe", "/g", 2733369024L, 0L, emptyList())
        assertEquals(2733369024L, NoSteamLaunch.settingsId(plain))
    }

    @Test fun theRunningSessionIsNamedAfterItsGame() {
        val command = NoSteamLaunch.command(steamGame, null)!!
        assertNull(NoSteamLaunch.nameFor(command.program, command.args))
        NoSteamLaunch.remember(command)
        assertEquals("Stardew Valley", NoSteamLaunch.nameFor(command.program, command.args))
        assertEquals(Library.EPIC_LAUNCHER_NAME, NoSteamLaunch.nameFor(NoSteamLaunch.EPIC, listOf("run")))
        assertNull(NoSteamLaunch.nameFor("/usr/bin/retroarch", emptyList()))
        assertNull(NoSteamLaunch.nameFor(NoSteamLaunch.PROTON_RUN, listOf("/x/a.exe")))
    }
}
