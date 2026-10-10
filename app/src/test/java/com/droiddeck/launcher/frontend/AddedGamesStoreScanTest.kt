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

/** A store install is one more folder the scan finds, named and aimed by its sidecar. */
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
        // Inside the runtime's tree the folder needs no bind: its guest path is its path from the tree's root.
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

    @Test fun anOlderSidecarsSteamSwitchIsIgnoredEveryInstallIsListed() {
        val folder = install(Store.AMAZON, "amzn1.adg.product.x", "Yakuza 0", "Yakuza0.exe")
        val file = StoreGameSidecar.file(folder)
        file.writeText(file.readText().replaceFirst("{", "{\"addToSteam\": false,"))
        assertTrue(AddedGames.scan(app).any { it.folder.absolutePath == folder.absolutePath })
    }

    @Test fun theListingCarriesTheLauncherAndTheTitle() {
        val folder = install(Store.EPIC, "abc", "Celeste", "Celeste.exe", launcher = true)
        val games = AddedGames.scan(app).filter { it.folder.absolutePath == folder.absolutePath }
        val listing = AddedGames.writeListing(app, games).readText()
        assertTrue(listing.contains("\"name\":\"Celeste\""))
        assertTrue(listing.contains(StoreLaunch.LAUNCHER))
    }
}
