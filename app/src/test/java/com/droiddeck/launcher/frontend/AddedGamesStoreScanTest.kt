package com.droiddeck.launcher.frontend

import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.StoreInstallRoot
import com.droiddeck.launcher.stores.StoreLaunch
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AddedGamesStoreScanTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun install(store: Store, id: String, title: String, exe: String, launcher: Boolean = false): File {
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        val folder = File(StoreInstallRoot.storeDir(StoreInstallRoot.installRoot(app), store), StoreInstallRoot.folderName(title, id))
        File(folder, exe).apply { parentFile!!.mkdirs(); writeText("game") }
        File(folder, "unins000.exe").writeText("uninstaller")
        var sidecar = StoreGameSidecar(store, id, title, exe, args = if (launcher) listOf("-EpicPortal") else emptyList())
        if (launcher) sidecar = StoreLaunch.writeLauncher(folder, sidecar)
        sidecar.write(folder)
        return folder
    }

    @Test fun aStoreGameIsFoundUnderTheInternalRootWithItsSource() {
        val folder = install(Store.GOG, "1207658924", "Heroes of Might and Magic 3: Complete", "HOMM3.exe")
        val games = AddedGames.scan(app)
        val game = games.single { it.folder.absolutePath == folder.absolutePath }
        assertEquals("Heroes of Might and Magic 3: Complete", game.name)
        assertEquals("HOMM3.exe", game.exe.name)
        assertEquals(Store.GOG.id, game.source)
        assertEquals("1207658924", game.storeId)
        assertEquals("/root/Games/Stores/GOG/Heroes of Might and Magic 3 Complete/HOMM3.exe", game.guestExe)
        assertEquals("/root/Games/Stores/GOG/Heroes of Might and Magic 3 Complete", game.guestDir)
    }

    @Test fun theLauncherIsWhatTheShortcutRunsAndTheExeIsWhatItShows() {
        val folder = install(Store.EPIC, "abc", "Celeste", "Celeste.exe", launcher = true)
        val game = AddedGames.scan(app).single { it.folder.absolutePath == folder.absolutePath }
        assertEquals("Celeste.exe", game.exe.name)
        assertTrue(game.guestExe.endsWith("/" + StoreLaunch.LAUNCHER))
        assertEquals(Store.EPIC.id, game.source)
    }

    @Test fun anEpicGameAlwaysUsesItsLauncherWrapperEvenAfterAnExeIsPicked() {
        val folder = install(Store.EPIC, "picked", "Picked Epic", "Binaries/Game.exe", launcher = true)
        AddedExes.put(app, AddedExes.Entry(folder.path, File(folder, "Binaries/Game.exe").path, picked = true))
        val game = AddedGames.scan(app).single { it.folder.absolutePath == folder.absolutePath }
        assertEquals("Binaries/Game.exe", game.exe.relativeTo(folder).path)
        assertTrue(game.guestExe.endsWith("/" + StoreLaunch.LAUNCHER))
    }

    @Test fun aDifferentPickedExeOnAnEpicGameIsRunDirectly() {
        val folder = install(Store.EPIC, "other", "Other Epic", "Binaries/Game.exe", launcher = true)
        val tool = File(folder, "Tools/Editor.exe").apply { parentFile!!.mkdirs(); writeText("tool") }
        AddedExes.put(app, AddedExes.Entry(folder.path, tool.path, picked = true))
        val game = AddedGames.scan(app).single { it.folder.absolutePath == folder.absolutePath }
        assertEquals("Tools/Editor.exe", game.exe.relativeTo(folder).path)
        assertTrue(game.guestExe.endsWith("/Tools/Editor.exe"))
    }

    @Test fun anOlderSidecarsSteamSwitchIsIgnoredEveryInstallIsListed() {
        val folder = install(Store.AMAZON, "amzn1.adg.product.x", "Yakuza 0", "Yakuza0.exe")
        val file = StoreGameSidecar.file(folder)
        file.writeText(file.readText().replaceFirst("{", "{\"addToSteam\": false,"))
        assertTrue(AddedGames.scan(app).any { it.folder.absolutePath == folder.absolutePath })
    }

    private fun installLinux(): File {
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        val folder = File(StoreInstallRoot.storeDir(StoreInstallRoot.installRoot(app), Store.ITCH), StoreInstallRoot.folderName("Lin Game", "77"))
        File(folder, "LinGame.x86_64").apply { parentFile!!.mkdirs(); writeText("game") }
        StoreGameSidecar(Store.ITCH, "77", "Lin Game", "LinGame.x86_64", extra = mapOf("platform" to "linux")).write(folder)
        return folder
    }

    @Test fun aLinuxInstallIsListedAsLinuxWithItsProgramAsTheExe() {
        val folder = installLinux()
        val game = AddedGames.scan(app).single { it.folder.absolutePath == folder.absolutePath }
        assertTrue(game.linux)
        assertEquals("LinGame.x86_64", game.exe.name)
        assertTrue(game.guestExe.endsWith("/LinGame.x86_64"))
        assertEquals(Store.ITCH.id, game.source)
    }

    @Test fun onlyALinuxGameCarriesTheLinuxMarkInTheListing() {
        val linux = installLinux()
        val windows = install(Store.GOG, "1207658924", "Heroes of Might and Magic 3: Complete", "HOMM3.exe")
        val games = AddedGames.scan(app)
        val listing = org.json.JSONArray(AddedGames.writeListing(app, games).readText())
        val byName = (0 until listing.length()).map { listing.getJSONObject(it) }.associateBy { it.getString("name") }
        assertTrue(byName.getValue("Lin Game").optBoolean("linux"))
        assertFalse(byName.getValue("Heroes of Might and Magic 3: Complete").has("linux"))
        assertFalse(games.single { it.folder.absolutePath == windows.absolutePath }.linux)
        assertTrue(games.single { it.folder.absolutePath == linux.absolutePath }.linux)
    }

    @Test fun theListingCarriesTheLauncherAndTheTitle() {
        val folder = install(Store.EPIC, "abc", "Celeste", "Celeste.exe", launcher = true)
        val games = AddedGames.scan(app).filter { it.folder.absolutePath == folder.absolutePath }
        val listing = AddedGames.writeListing(app, games).readText()
        assertTrue(listing.contains("\"name\":\"Celeste\""))
        assertTrue(listing.contains(StoreLaunch.LAUNCHER))
        assertTrue(listing.contains("\"store\":\"${Store.EPIC.id}\""))
    }
}
