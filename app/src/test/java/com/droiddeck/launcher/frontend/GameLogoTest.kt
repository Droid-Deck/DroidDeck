package com.droiddeck.launcher.frontend

import com.droiddeck.launcher.session.SessionPrefs
import org.json.JSONArray
import org.json.JSONObject
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
class GameLogoTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @get:Rule val tmp = TemporaryFolder()

    @Before fun noGamesFolders() {
        AddedGamesPublisher.now = true
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        SessionPrefs.setAddedGamesDirs(app, emptyList())
    }

    private fun image(name: String) = File(tmp.root, name).apply { parentFile?.mkdirs(); writeBytes(ByteArray(8) { 1 }) }

    @Test fun steamsCachedLogoIsFoundInTheAppFolderOrAHashedOne() {
        val cache = tmp.newFolder("librarycache")
        val logo = File(cache, "620/3f1c/logo.png").apply { parentFile?.mkdirs(); writeBytes(ByteArray(4) { 1 }) }
        assertEquals(logo, Library.steamCacheImage(cache, 620, Library.STEAM_LOGOS))
        File(cache, "730").mkdirs()
        File(cache, "730/logo.png").writeBytes(ByteArray(0))
        assertNull(Library.steamCacheImage(cache, 730, Library.STEAM_LOGOS))
    }

    @Test fun aSteamScanKeepsEveryArtworkSlotAndSeesNewHashDirectories() {
        val root = SessionPrefs.steamRoot(app, "arm64")
        File(root, "steamapps/appmanifest_620.acf").apply {
            parentFile!!.mkdirs()
            writeText("\"AppState\"\n{\n\"name\" \"Portal 2\"\n\"StateFlags\" \"4\"\n}\n")
        }
        val cache = File(root, "appcache/librarycache/620")
        fun cached(path: String) = File(cache, path).apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        val cover = cached("library_capsule.jpg")
        val hero = cached("hash/library_hero.jpg")
        val icon = cached("${"a".repeat(40)}.jpg")
        val game = Library.steamGames(app, arch = "arm64").single()
        assertEquals(listOf(cover, hero, icon, null), listOf(game.art, game.hero, game.icon, game.logo))

        val logo = cached("new-hash/logo.png")
        val refreshed = Library.steamGames(app, arch = "arm64").single()
        assertEquals(listOf(cover, hero, icon, logo), listOf(refreshed.art, refreshed.hero, refreshed.icon, refreshed.logo))
    }

    @Test fun aChosenLogoWinsOverSteamsAndKeepsTheRest() {
        val stock = image("logo.png")
        val game = Library.SteamGame(620, "Portal 2", null, "Steam library", logo = stock)
        assertTrue(SteamGameArt.withOverrides(app, game) === game)
        val picked = image("mine.png")
        assertTrue(SteamGameArt.choose(app, game, AddedGameArt.Slot.LOGO, AddedGameArt.Option(AddedGameArt.FILE, picked.path, picked.path)))
        val shown = SteamGameArt.withOverrides(app, game)
        assertTrue(shown.logo!!.name.startsWith("chosen-logo"))
        assertNull(shown.art)
        SteamGameArt.reset(app, game, AddedGameArt.Slot.LOGO)
        assertEquals(stock, SteamGameArt.withOverrides(app, game).logo)
    }

    @Test fun anAddedGameShowsItsFolderLogoUnlessOneWasChosen() {
        val dir = tmp.newFolder("Celeste")
        val own = File(dir, "logo.png").apply { writeBytes(ByteArray(4) { 1 }) }
        val exe = File(dir, "Celeste.exe").apply { writeText("x") }
        val g = AddedGames.Game(dir, "Celeste", exe, "/g/Celeste.exe", "/g", 0x82000001L, 0x82000001L, emptyList(), source = "gog")
        assertEquals(own, Library.addedGame(app, g, emptyList()).logo)
        val picked = image("pick.png")
        assertTrue(AddedGameArt.choose(app, g, AddedGameArt.Slot.LOGO, AddedGameArt.Option(AddedGameArt.FILE, picked.path, picked.path)))
        assertTrue(Library.addedGame(app, g, emptyList()).logo!!.name.startsWith("chosen-logo"))
        val bare = AddedGames.Game(tmp.newFolder("Bare"), "Bare", exe, "/g/Bare.exe", "/g", 0x82000002L, 0x82000002L, emptyList())
        assertNull(Library.addedGame(app, bare, emptyList()).logo)
    }

    @Test fun theLibraryCacheKeepsTheLogoAndReadsOlderEntries() {
        val logo = image("logo.png")
        val game = Library.SteamGame(620, "Portal 2", null, "Steam library", logo = logo)
        LibraryCache.save(app, listOf(game, Library.SteamGame(730, "CS2", null, "Steam library")))
        val back = LibraryCache.load(app)
        assertEquals(logo, back[0].logo)
        assertNull(back[1].logo)
        assertEquals(logo, game.withArt(null, null, null).logo)
        val file = File(app.filesDir, "library-cache.json")
        file.writeText(JSONArray().put(JSONObject().put("appId", 1).put("name", "Old").put("library", "x").put("gameId", 1L)).toString())
        assertNull(LibraryCache.load(app).single().logo)
    }
}
