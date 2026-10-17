package com.droiddeck.launcher.frontend

import com.droiddeck.launcher.session.SessionPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class SteamGameArtTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @get:Rule val tmp = TemporaryFolder()

    @Before fun noGamesFolders() {
        AddedGamesPublisher.now = true
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        SessionPrefs.setAddedGamesDirs(app, emptyList())
    }

    private fun game(art: File? = null) = Library.SteamGame(620, "Portal 2", art, "Steam library")

    private fun image(name: String) = File(tmp.root, name).apply { writeBytes(ByteArray(8)) }

    @Test fun aChosenImageReplacesTheCoverAndResetBringsBackSteams() {
        val stock = image("stock.jpg")
        val picked = image("mine.png")
        val option = AddedGameArt.Option(AddedGameArt.FILE, picked.path, picked.path)
        assertTrue(SteamGameArt.choose(app, game(stock), AddedGameArt.Slot.COVER, option))

        val shown = SteamGameArt.withOverrides(app, game(stock))
        assertTrue(shown.art!!.name.matches(Regex("chosen-p-\\d+\\.png")))
        val states = SteamGameArt.describe(app, game(stock))
        assertTrue(states.first { it.slot == AddedGameArt.Slot.COVER }.chosen)
        assertFalse(states.first { it.slot == AddedGameArt.Slot.BACKGROUND }.chosen)
        assertEquals(picked.path, SteamGameArt.chosenSource(app, 620, AddedGameArt.Slot.COVER).second)

        SteamGameArt.reset(app, game(stock), AddedGameArt.Slot.COVER)
        assertEquals(stock, SteamGameArt.withOverrides(app, game(stock)).art)
        assertNull(SteamGameArt.chosenFile(app, 620, AddedGameArt.Slot.COVER))
        assertEquals("", SteamGameArt.chosenSource(app, 620, AddedGameArt.Slot.COVER).first)
    }

    @Test fun aGameWithoutChoicesIsLeftAsItIs() {
        val g = game(image("stock.jpg"))
        assertTrue(SteamGameArt.withOverrides(app, g) === g)
    }

    @Test fun aChoiceForOneGameDoesNotTouchAnother() {
        val picked = image("mine.png")
        SteamGameArt.choose(app, game(), AddedGameArt.Slot.BACKGROUND, AddedGameArt.Option(AddedGameArt.FILE, picked.path, picked.path))
        val other = Library.SteamGame(730, "Counter-Strike 2", null, "Steam library")
        assertTrue(SteamGameArt.withOverrides(app, other) === other)
        assertTrue(SteamGameArt.withOverrides(app, game()).hero!!.name.startsWith("chosen-hero"))
    }
}
