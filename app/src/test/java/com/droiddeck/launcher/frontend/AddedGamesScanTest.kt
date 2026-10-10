package com.droiddeck.launcher.frontend

import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AddedGamesScanTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @get:Rule val tmp = TemporaryFolder()

    @Test fun theSteamLibraryInsideAGamesFolderIsNotAGame() {
        val games = tmp.newFolder("PC")
        File(games, "Example").mkdirs()
        File(games, "Example/Example.exe").writeText("game")
        File(games, "steamapps/downloading").mkdirs()
        File(games, "steamapps/downloading/Depot.exe").writeText("depot")
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        SessionPrefs.setAddedGamesDirs(app, listOf(games.path))
        assertEquals(listOf("Example"), AddedGames.scan(app).map { it.name })
    }

    @Test fun aStorePackageLaunchesTheExecutableItDeclares() {
        val game = tmp.newFolder("Minecraft Dungeons")
        File(game, "Dungeons.exe").writeText("unreal bootstrap")
        File(game, "Dungeons/Binaries/Win64").mkdirs()
        File(game, "Dungeons/Binaries/Win64/Dungeons.exe").writeText("the game, renamed from Dungeons-Win64-Shipping.exe")
        File(game, "MicrosoftGame.config").writeText(
            "<Game><ExecutableList>\n  <Executable Name=\"Dungeons\\Binaries\\Win64\\Dungeons.exe\"\n\n    Id=\"Game\" />\n</ExecutableList></Game>",
        )
        assertEquals(File(game, "Dungeons/Binaries/Win64/Dungeons.exe").path, AddedGames.candidates(game).first().path)
        assertEquals(2, AddedGames.candidates(game).size)
    }

    @Test fun anAppxManifestNamesTheGameWhenThereIsNoConfig() {
        val game = tmp.newFolder("Store Game")
        File(game, "Launcher.exe").writeText("bootstrap")
        File(game, "Game/Binaries/Win64").mkdirs()
        File(game, "Game/Binaries/Win64/Game.exe").writeText("game")
        File(game, "appxmanifest.xml").writeText("<Package><Applications><Application Id=\"Game\" Executable=\"Game\\Binaries\\Win64\\Game.exe\" /></Applications></Package>")
        assertEquals(File(game, "Game/Binaries/Win64/Game.exe").path, AddedGames.candidates(game).first().path)
    }

    @Test fun aDeclaredTopLevelExecutableKeepsItsPlace() {
        val game = tmp.newFolder("Minecraft Dungeons II")
        File(game, "Dungeons.exe").writeText("bootstrap")
        File(game, "Other.exe").writeText("a much longer other executable")
        File(game, "MicrosoftGame.config").writeText("<Executable Name=\"Dungeons.exe\" Id=\"App\" />")
        assertEquals(listOf("Dungeons.exe", "Other.exe"), AddedGames.candidates(game).map { it.name })
    }

    @Test fun declaredHelpersMissingFilesAndPathsOutsideTheFolderAreIgnored() {
        val game = tmp.newFolder("Pass Game")
        File(game, "PassGame.exe").writeText("game")
        File(game, "gamelaunchhelper.exe").writeText("needs gaming services")
        tmp.newFile("Outside.exe").writeText("not this game")
        File(game, "MicrosoftGame.config").writeText(
            "<Executable Name=\"gamelaunchhelper.exe\" /><Executable Name=\"Missing.exe\" /><Executable Name=\"..\\Outside.exe\" />",
        )
        assertEquals(emptyList<File>(), AddedGames.declaredExes(game))
        assertEquals(listOf("PassGame.exe"), AddedGames.candidates(game).map { it.name })
    }
}
