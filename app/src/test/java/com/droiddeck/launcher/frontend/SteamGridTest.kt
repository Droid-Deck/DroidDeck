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

    @Test fun aWideCoverGoesToTheUnsuffixedGridFileAndComesBackFromSteam() {
        File(LinuxRuntime.rootDir(app), "root/.local/share/Steam/userdata/123/config").mkdirs()
        val picked = image("wide.png", 14)
        assertTrue(SteamGameArt.choose(app, game(), AddedGameArt.Slot.WIDE, AddedGameArt.Option(AddedGameArt.FILE, picked.path, picked.path)))
        assertEquals(14L, File(grid, "620.png").length())
        assertFalse(File(grid, "620p.png").exists())
        assertTrue(SteamGameArt.chosenFile(app, 620, AddedGameArt.Slot.WIDE)!!.name.matches(Regex("chosen-header-\\d+\\.png")))
        assertNull(SteamGameArt.chosenFile(app, 620, AddedGameArt.Slot.COVER))

        File(grid, "620.png").delete()
        assertTrue(SteamGrid.sync(app, listOf(target())))
        assertNull(SteamGameArt.chosenFile(app, 620, AddedGameArt.Slot.WIDE))
    }

    private val corner = LogoPosition.Value(LogoPosition.Pin.UPPER_CENTER, 35, 35)

    @Test fun aLogoPositionIsWrittenToSteamsJsonKeepingWhatElseIsInIt() {
        File(LinuxRuntime.rootDir(app), "root/.local/share/Steam/userdata/123/config").mkdirs()
        grid.mkdirs()
        File(grid, "620.json").writeText("""{"nVersion":1,"other":"kept"}""")
        assertTrue(SteamGameArt.setPosition(app, game(), corner))
        val written = org.json.JSONObject(File(grid, "620.json").readText())
        assertEquals("kept", written.getString("other"))
        assertEquals(corner, LogoPosition.parse(written))
        assertEquals(corner, SteamGameArt.position(app, game()))

        assertTrue(SteamGameArt.setPosition(app, game(), null))
        val left = org.json.JSONObject(File(grid, "620.json").readText())
        assertFalse(left.has("logoPosition"))
        assertEquals("kept", left.getString("other"))
        assertNull(SteamGameArt.position(app, game()))
    }

    @Test fun aLogoPositionAloneLeavesNoFileBehindWhenCleared() {
        File(LinuxRuntime.rootDir(app), "root/.local/share/Steam/userdata/123/config").mkdirs()
        assertTrue(SteamGameArt.setPosition(app, game(), corner))
        assertEquals(corner, LogoPosition.parse(File(grid, "620.json").readText()))
        assertTrue(SteamGameArt.setPosition(app, game(), null))
        assertFalse(File(grid, "620.json").exists())
    }

    @Test fun aLogoPositionSetInSteamIsTakenOverAndRemovingItDropsIt() {
        File(LinuxRuntime.rootDir(app), "root/.local/share/Steam/userdata/123/config").mkdirs()
        grid.mkdirs()
        val theirs = File(grid, "620.json").apply { writeText(LogoPosition.toJson(corner).toString()) }
        SteamGrid.sync(app, listOf(target()))
        assertEquals(corner, SteamGameArt.position(app, game()))

        theirs.writeText(LogoPosition.toJson(LogoPosition.Value(LogoPosition.Pin.CENTER, 80, 80)).toString())
        theirs.setLastModified(theirs.lastModified() + 5000)
        SteamGrid.sync(app, listOf(target()))
        assertEquals(LogoPosition.Pin.CENTER, SteamGameArt.position(app, game())?.pin)

        theirs.delete()
        SteamGrid.sync(app, listOf(target()))
        assertNull(SteamGameArt.position(app, game()))
    }

    @Test fun aLogoPositionChosenHereIsNotTakenBackAsASteamChange() {
        File(LinuxRuntime.rootDir(app), "root/.local/share/Steam/userdata/123/config").mkdirs()
        assertTrue(SteamGameArt.setPosition(app, game(), corner))
        SteamGrid.sync(app, listOf(target()))
        assertEquals(corner, SteamGameArt.position(app, game()))
        assertEquals(corner, LogoPosition.parse(File(grid, "620.json").readText()))
    }

    @Test fun aLogoPositionWithoutSteamAccountIsStillKeptHere() {
        assertTrue(SteamGameArt.setPosition(app, game(), corner))
        assertEquals(corner, SteamGameArt.position(app, game()))
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

    @Test fun theOtherClientsEmptyGridGetsTheChoiceInsteadOfDroppingIt() {
        File(LinuxRuntime.rootDir(app), "root/.local/share/Steam/userdata/123/config").mkdirs()
        val picked = image("mine.png", 12)
        SteamGameArt.choose(app, game(), AddedGameArt.Slot.COVER, AddedGameArt.Option(AddedGameArt.FILE, picked.path, picked.path))
        assertFalse(SteamGrid.sync(app, listOf(target())))

        val x64 = File(SessionPrefs.steamRoot(app, "x86_64"), "userdata/123/config").apply { mkdirs() }
        SessionPrefs.setSteamClientArch(app, "x86_64")
        assertFalse(SteamGrid.sync(app, listOf(target())))
        assertEquals(12L, SteamGameArt.chosenFile(app, 620, AddedGameArt.Slot.COVER)?.length())
        assertEquals(12L, File(x64, "grid/620p.png").length())

        SessionPrefs.setSteamClientArch(app, "arm64")
        assertFalse(SteamGrid.sync(app, listOf(target())))
        assertEquals(12L, File(grid, "620p.png").length())
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

    @Test fun aChoiceSurvivesTheGridFolderBeingWipedAndIsPlacedAgain() {
        File(LinuxRuntime.rootDir(app), "root/.local/share/Steam/userdata/123/config").mkdirs()
        val picked = image("mine.png", 12)
        SteamGameArt.choose(app, game(), AddedGameArt.Slot.COVER, AddedGameArt.Option(AddedGameArt.FILE, picked.path, picked.path))
        assertEquals(12L, File(grid, "620p.png").length())

        assertTrue(grid.deleteRecursively())
        grid.mkdirs()
        assertFalse(SteamGrid.sync(app, listOf(target())))
        assertEquals(12L, SteamGameArt.chosenFile(app, 620, AddedGameArt.Slot.COVER)?.length())
        assertEquals(12L, File(grid, "620p.png").length())
    }
}
