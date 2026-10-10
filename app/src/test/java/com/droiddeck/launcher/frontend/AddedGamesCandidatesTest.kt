package com.droiddeck.launcher.frontend

import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The .exe picker below a game folder (five levels, past installer folders, ranked) and the names it finds. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AddedGamesCandidatesTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @get:Rule val tmp = TemporaryFolder()

    private fun exe(root: File, path: String, size: Int = 10): File =
        File(root, path).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(size)) }

    private fun names(folder: File) = AddedGames.candidates(folder).map { it.relativeTo(folder).invariantSeparatorsPath }

    @Test fun anUnrealShippingBuildThreeDownWinsOverTheTopLevelHelpers() {
        val game = tmp.newFolder("Some Game")
        exe(game, "CrashReportClient.exe", 5000)
        exe(game, "Engine/Binaries/Win64/UnrealCEFSubProcess.exe", 9000)
        val shipping = exe(game, "SomeGame/Binaries/Win64/SomeGame-Win64-Shipping.exe", 100)
        exe(game, "SomeGame/Binaries/Win64/Tool.exe", 8000)
        assertEquals("SomeGame/Binaries/Win64/SomeGame-Win64-Shipping.exe", names(game).first())
        assertFalse(names(game).any { it.contains("CrashReport") })
        assertEquals(shipping, AddedGames.candidates(game).first())
    }

    @Test fun aShippingExeNotNamedAfterTheFolderStillRanksAboveOtherDeepExes() {
        val game = tmp.newFolder("Folder")
        exe(game, "Proj/Binaries/Win64/Proj-Win64-Shipping.exe", 10)
        exe(game, "Proj/Tools/Big.exe", 9000)
        assertEquals("Proj/Binaries/Win64/Proj-Win64-Shipping.exe", names(game).first())
    }

    @Test fun fiveLevelsDownIsFoundSixIsNot() {
        val game = tmp.newFolder("Deep")
        exe(game, "a/b/c/d/e/Five.exe")
        exe(game, "a/b/c/d/e/f/Six.exe")
        assertEquals(listOf("a/b/c/d/e/Five.exe"), names(game))
    }

    @Test fun installerAndRedistFoldersAreSkipped() {
        val game = tmp.newFolder("Skips")
        for (dir in listOf("_CommonRedist", "CommonRedist", "Redist", "DirectX", "DotNetFx", "vcredist_x64", "Prerequisites",
            "Support", "Engine/Extras", "Engine/Binaries/ThirdParty", "__Installer", "Installers", ".git", "\$PLUGINSDIR")) {
            exe(game, "$dir/Thing.exe")
        }
        val game2 = exe(game, "Bin/Game.exe")
        assertEquals(listOf("Bin/Game.exe"), names(game))
        assertTrue(game2.isFile)
    }

    @Test fun theWalkStopsAtItsBudget() {
        val game = tmp.newFolder("Huge")
        for (i in 0 until GameExePicker.WALK_BUDGET + 50) File(game, "data/d%04d".format(i)).mkdirs()
        exe(game, "zzz/Late.exe")
        // data/ alone fills the budget before zzz/ is listed.
        assertEquals(emptyList<String>(), names(game))
        val small = tmp.newFolder("Small")
        exe(small, "x/y/Found.exe")
        assertEquals(listOf("x/y/Found.exe"), names(small))
    }

    @Test fun theUsersPickIsKeptOverTheDeepScan() {
        val games = tmp.newFolder("PC")
        val game = File(games, "Some Game")
        val top = exe(game, "Launcher.exe", 10)
        exe(game, "SomeGame/Binaries/Win64/SomeGame-Win64-Shipping.exe", 100)
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        SessionPrefs.setAddedGamesDirs(app, listOf(games.path))
        SessionPrefs.setAddedGameExe(app, game.path, top.path)
        val g = AddedGames.scan(app).single()
        assertEquals(top.path, g.exe.path)
    }

    @Test fun aDeepExeStartsInItsOwnFolder() {
        val games = tmp.newFolder("PC")
        val game = File(games, "Some Game")
        exe(game, "SomeGame/Binaries/Win64/SomeGame-Win64-Shipping.exe", 100)
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        SessionPrefs.setAddedGamesDirs(app, listOf(games.path))
        val g = AddedGames.scan(app).single()
        assertEquals("/root/Games/PC/Some Game/SomeGame/Binaries/Win64/SomeGame-Win64-Shipping.exe", g.guestExe)
        assertEquals("/root/Games/PC/Some Game/SomeGame/Binaries/Win64", g.guestDir)
    }

    @Test fun twoLookalikeExesAreUncertainANamedOneIsNot() {
        val unsure = tmp.newFolder("Folder A")
        exe(unsure, "x86/Run.exe"); exe(unsure, "x64/Thing.exe")
        assertTrue(GameExePicker.rank(unsure).uncertain)
        val sure = tmp.newFolder("Example")
        exe(sure, "Example.exe"); exe(sure, "bin/Tool.exe")
        val r = GameExePicker.rank(sure)
        assertFalse(r.uncertain)
        assertEquals("Example.exe", r.exes.first().name)
    }

    @Test fun aLauncherRanksBelowTheGame() {
        val game = tmp.newFolder("Grand Theft Auto V")
        exe(game, "PlayGTAV.exe"); exe(game, "GTA5.exe")
        assertEquals("GTA5.exe", names(game).first())
    }

    @Test fun anUncertainGuessIsMarkedAndAPickIsNot() {
        val games = tmp.newFolder("PC")
        val game = File(games, "Folder A")
        exe(game, "x86/Run.exe"); val pick = exe(game, "x64/Thing.exe")
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        SessionPrefs.setAddedGamesDirs(app, listOf(games.path))
        assertTrue(AddedGames.scan(app).single().exeUncertain)
        SessionPrefs.setAddedGameExe(app, game.path, pick.path)
        assertFalse(AddedGames.scan(app).single().exeUncertain)
    }

    @Test fun theGamesOwnTitleNamesItAndItsShortcutIdStays() {
        val games = tmp.newFolder("PC")
        val game = File(games, "ws-v1.2")
        exe(game, "ws-v1.2.exe")
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        SessionPrefs.setAddedGamesDirs(app, listOf(games.path))
        val before = AddedGames.scan(app).single()
        assertEquals("ws-v1.2", before.name)

        File(game, "goggame-1207658691.info").writeText("""{"gameId":"1207658691","name":"The Witcher 3: Wild Hunt"}""")
        game.setLastModified(game.lastModified() + 10_000)
        val after = AddedGames.scan(app).single()
        assertEquals("The Witcher 3 - Wild Hunt", after.name)
        // Already added under the folder name: the shortcut keeps its id.
        assertEquals(before.appId, after.appId)
    }

    @Test fun onlyACleanedFileNameKeepsTheFolderName() {
        val games = tmp.newFolder("PC")
        val game = File(games, "My Game [Repack]")
        exe(game, "MyGame-Win64-Shipping.exe")
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        SessionPrefs.setAddedGamesDirs(app, listOf(games.path))
        assertEquals("My Game [Repack]", AddedGames.scan(app).single().name)
    }
}
