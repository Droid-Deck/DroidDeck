package com.droiddeck.launcher.frontend

import com.droiddeck.launcher.runtime.LinuxRuntime
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
class SteamGridTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var grid: File

    @Before fun account() {
        AddedGamesPublisher.now = true
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        SessionPrefs.setAddedGamesDirs(app, emptyList())
        grid = File(LinuxRuntime.rootDir(app), "root/.local/share/Steam/userdata/123/config/grid")
    }

    private fun game() = Library.SteamGame(620, "Portal 2", null, "Steam library")
    private fun target() = SteamGrid.target(game(), app)
    private fun image(name: String, size: Int) = File(tmp.root, name).apply { writeBytes(ByteArray(size) { 1 }) }

    @Test fun aChoiceIsPlacedInTheGridFolderWhileSteamIsClosed() {
        File(LinuxRuntime.rootDir(app), "root/.local/share/Steam/userdata/123/config").mkdirs()
        val picked = image("mine.png", 12)
        SteamGameArt.choose(app, game(), AddedGameArt.Slot.COVER, AddedGameArt.Option(AddedGameArt.FILE, picked.path, picked.path))
        assertEquals(12L, File(grid, "620p.png").length())

        SteamGameArt.reset(app, game(), AddedGameArt.Slot.COVER)
        assertFalse(File(grid, "620p.png").exists())
    }

    @Test fun artSetInSteamIsTakenOverAndRemovingItInSteamDropsIt() {
        File(LinuxRuntime.rootDir(app), "root/.local/share/Steam/userdata/123/config").mkdirs()
        grid.mkdirs()
        val theirs = File(grid, "620_hero.jpg").apply { writeBytes(ByteArray(20) { 2 }) }
        assertTrue(SteamGrid.sync(app, listOf(target())))
        assertEquals(20L, SteamGameArt.chosenFile(app, 620, AddedGameArt.Slot.BACKGROUND)?.length())
        assertEquals(AddedGameArt.STEAM, SteamGameArt.chosenSource(app, 620, AddedGameArt.Slot.BACKGROUND).first)
        assertFalse(SteamGrid.sync(app, listOf(target())))

        theirs.delete()
        assertTrue(SteamGrid.sync(app, listOf(target())))
        assertNull(SteamGameArt.chosenFile(app, 620, AddedGameArt.Slot.BACKGROUND))
    }

    @Test fun aChoiceMadeHereIsNotTakenBackAsASteamChange() {
        File(LinuxRuntime.rootDir(app), "root/.local/share/Steam/userdata/123/config").mkdirs()
        val picked = image("mine.png", 12)
        SteamGameArt.choose(app, game(), AddedGameArt.Slot.LOGO, AddedGameArt.Option(AddedGameArt.FILE, picked.path, picked.path))
        File(grid, "620_logo.png").writeBytes(picked.readBytes())
        assertFalse(SteamGrid.sync(app, listOf(target())))
        assertEquals(AddedGameArt.FILE, SteamGameArt.chosenSource(app, 620, AddedGameArt.Slot.LOGO).first)
    }

    @Test fun artDroidDeckPlacedItselfIsNotTakenAsAChoice() {
        File(LinuxRuntime.rootDir(app), "root/.local/share/Steam/userdata/123/config").mkdirs()
        grid.mkdirs()
        File(grid, "620p.png").writeBytes(ByteArray(7))
        File(grid, ".droiddeck-art.json").writeText("""{"620p.png": 7}""")
        assertFalse(SteamGrid.sync(app, listOf(target())))
        assertNull(SteamGameArt.chosenFile(app, 620, AddedGameArt.Slot.COVER))
    }

    @Test fun withoutASteamAccountNothingHappens() {
        assertFalse(SteamGrid.sync(app, listOf(target())))
    }
}
