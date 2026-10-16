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

    @Test fun aSteamImageAlreadyThereIsKeptOverAnOlderChoiceOnTheFirstLook() {
        File(LinuxRuntime.rootDir(app), "root/.local/share/Steam/userdata/123/config").mkdirs()
        val old = image("old.png", 5)
        val dir = SteamGameArt.dir(app, 620)
        assertTrue(AddedGameArt.store(dir, AddedGameArt.Slot.COVER, AddedGameArt.Option(AddedGameArt.FILE, old.path, old.path)))
        grid.mkdirs()
        File(grid, "620p.png").writeBytes(ByteArray(30) { 3 })
        assertTrue(SteamGrid.sync(app, listOf(target())))
        assertEquals(30L, SteamGameArt.chosenFile(app, 620, AddedGameArt.Slot.COVER)?.length())
        assertEquals(30L, File(grid, "620p.png").length())
    }

    @Test fun aChoiceSurvivesWhenOnlyDroidDecksOwnWriterFileIsInTheFolder() {
        File(LinuxRuntime.rootDir(app), "root/.local/share/Steam/userdata/123/config").mkdirs()
        val picked = image("mine.png", 12)
        SteamGameArt.choose(app, game(), AddedGameArt.Slot.COVER, AddedGameArt.Option(AddedGameArt.FILE, picked.path, picked.path))
        File(grid, "620p.png").writeBytes(ByteArray(12) { 9 })
        File(grid, ".droiddeck-art.json").writeText("""{"620p.png": 12}""")
        assertFalse(SteamGrid.sync(app, listOf(target())))
        assertEquals(12L, SteamGameArt.chosenFile(app, 620, AddedGameArt.Slot.COVER)?.length())
    }

    @Test fun anImageSteamCannotReadIsNotWrittenAndTheCurrentOneStays() {
        File(LinuxRuntime.rootDir(app), "root/.local/share/Steam/userdata/123/config").mkdirs()
        val png = image("mine.png", 12)
        SteamGameArt.choose(app, game(), AddedGameArt.Slot.COVER, AddedGameArt.Option(AddedGameArt.FILE, png.path, png.path))
        val ico = image("mine.ico", 6)
        SteamGrid.write(app, target(), AddedGameArt.Slot.COVER, ico)
        assertEquals(12L, File(grid, "620p.png").length())
        assertFalse(File(grid, "620p.ico").exists())
    }

    @Test fun aChoiceSteamCannotReadIsRefusedAndNotKept() {
        File(LinuxRuntime.rootDir(app), "root/.local/share/Steam/userdata/123/config").mkdirs()
        val ico = image("mine.ico", 6)
        assertFalse(SteamGameArt.choose(app, game(), AddedGameArt.Slot.COVER, AddedGameArt.Option(AddedGameArt.FILE, ico.path, ico.path)))
        assertNull(SteamGameArt.chosenFile(app, 620, AddedGameArt.Slot.COVER))
    }

    @Test fun aStaleFileLeftByAnEarlierFailedWriteIsReplacedByTheLocalChoice() {
        File(LinuxRuntime.rootDir(app), "root/.local/share/Steam/userdata/123/config").mkdirs()
        val picked = image("mine.png", 12)
        SteamGameArt.choose(app, game(), AddedGameArt.Slot.COVER, AddedGameArt.Option(AddedGameArt.FILE, picked.path, picked.path))
        SteamGameArt.chosenFile(app, 620, AddedGameArt.Slot.COVER)!!.writeBytes(ByteArray(15) { 4 })
        assertFalse(SteamGrid.sync(app, listOf(target())))
        assertEquals(15L, File(grid, "620p.png").length())
    }

    @Test fun aRefusedReplacementLeavesTheLastGoodChoiceInPlace() {
        File(LinuxRuntime.rootDir(app), "root/.local/share/Steam/userdata/123/config").mkdirs()
        val png = image("mine.png", 12)
        assertTrue(SteamGameArt.choose(app, game(), AddedGameArt.Slot.COVER, AddedGameArt.Option(AddedGameArt.FILE, png.path, png.path)))
        val kept = SteamGameArt.chosenFile(app, 620, AddedGameArt.Slot.COVER)
        val ico = image("mine.ico", 6)
        assertFalse(SteamGameArt.choose(app, game(), AddedGameArt.Slot.COVER, AddedGameArt.Option(AddedGameArt.FILE, ico.path, ico.path)))
        assertEquals(kept, SteamGameArt.chosenFile(app, 620, AddedGameArt.Slot.COVER))
        assertEquals(12L, File(grid, "620p.png").length())
    }

    @Test fun choicesMadeInTheSameMillisecondGetDifferentNamesAndTheLastOneWins() {
        File(LinuxRuntime.rootDir(app), "root/.local/share/Steam/userdata/123/config").mkdirs()
        val dir = SteamGameArt.dir(app, 620)
        val names = (1..5).map { n ->
            val image = image("pick$n.png", n)
            AddedGameArt.storeNew(dir, AddedGameArt.Slot.COVER, AddedGameArt.Option(AddedGameArt.FILE, image.path, image.path))!!.name
        }
        assertEquals(5, names.toSet().size)
        assertEquals(names.sorted(), names)
        assertEquals(5L, AddedGameArt.chosen(dir, AddedGameArt.Slot.COVER)?.length())
    }
}
