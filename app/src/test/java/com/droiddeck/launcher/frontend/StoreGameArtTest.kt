package com.droiddeck.launcher.frontend

import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.StoreInstallRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StoreGameArtTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @get:Rule val tmp = TemporaryFolder()

    @Before fun setUp() {
        AddedGamesPublisher.now = true
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
    }

    private fun install(store: Store, id: String, title: String): File {
        val folder = File(StoreInstallRoot.storeDir(StoreInstallRoot.installRoot(app), store), StoreInstallRoot.folderName(title, id))
        File(folder, "Game.exe").apply { parentFile!!.mkdirs(); writeText("game") }
        StoreGameSidecar(store, id, title, "Game.exe").write(folder)
        return folder
    }

    @Test fun anArtChoiceOnAStoreGameIsKeptShownAndResetsWithoutTouchingItsTitle() {
        for ((store, id, title) in listOf(Triple(Store.GOG, "1207658924", "Heroes III"), Triple(Store.EPIC, "abc", "Celeste"), Triple(Store.AMAZON, "amzn1.x", "Yakuza 0"), Triple(Store.XBOX, "9NBLGGH4X1", "Forza Horizon"), Triple(Store.ITCH, "12345", "Night in the Woods"))) {
            val folder = install(store, id, title)
            val image = File(tmp.root, "$id.png").apply { writeBytes(ByteArray(9) { 1 }) }
            val option = AddedGameArt.Option(AddedGameArt.FILE, image.path, image.path)

            assertTrue(AddedGameEdits.setArt(app, folder.path, AddedGameArt.Slot.COVER, option))
            val game = AddedGames.single(app, folder.path)!!
            assertEquals(title, game.name)
            assertEquals(store.id, game.source)
            val shown = Library.addedGame(app, game)
            assertEquals(AddedGameArt.chosen(AddedGameArt.cacheDir(app, game), AddedGameArt.Slot.COVER), shown.art)
            assertEquals(9L, shown.art?.length())

            AddedGameEdits.resetArt(app, folder.path, AddedGameArt.Slot.COVER)
            assertNull(AddedGameArt.chosen(AddedGameArt.cacheDir(app, game), AddedGameArt.Slot.COVER))
        }
    }
}
