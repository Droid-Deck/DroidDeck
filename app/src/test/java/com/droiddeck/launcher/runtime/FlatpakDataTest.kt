package com.droiddeck.launcher.runtime

import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.SessionPhase
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionState
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FlatpakDataTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @get:Rule val tmp = TemporaryFolder()
    private val id = "org.srb2.SRB2Kart"
    private lateinit var card: File

    @Before fun attachCard() {
        card = tmp.newFolder("card")
        SessionPrefs.setGameStorage(app, card.path, "SD")
        SessionState.running = false
        SessionState.phase = SessionPhase.IDLE
    }

    @After fun idle() {
        SessionState.running = false
        SessionState.phase = SessionPhase.IDLE
    }

    private fun seed(name: String = id, bytes: Int = 1000) {
        val addons = File(FlatpakData.internalDir(app, name), ".srb2kart/addons").apply { mkdirs() }
        File(addons, "map.pk3").writeBytes(ByteArray(bytes) { it.toByte() })
        File(FlatpakData.internalDir(app, name), "config/kartconfig.cfg").apply { parentFile!!.mkdirs() }.writeText("x")
        File(FlatpakData.internalDir(app, name), ".ld.so/real").apply { mkdirs() }
        Files.createSymbolicLink(File(FlatpakData.internalDir(app, name), ".ld.so/active").toPath(), File("real").toPath())
    }

    private var free = 1L shl 30

    private fun move(app: String, toCard: Boolean) = FlatpakData.move(this.app, app, toCard, { _, _ -> }, { free })

    @Test fun nothingIsBoundUntilAnAppOptsIn() {
        seed()
        assertTrue(FlatpakData.binds(app).isEmpty())
    }

    @Test fun movingToTheCardKeepsTheFilesAndBindsThemOverTheGuestFolder() {
        seed()
        assertNull(move(id, true))
        val onCard = File(card, "flatpak-data/$id")
        assertEquals(1000, File(onCard, ".srb2kart/addons/map.pk3").length())
        assertEquals("x", File(onCard, "config/kartconfig.cfg").readText())
        val internal = FlatpakData.internalDir(app, id)
        assertTrue(internal.isDirectory)
        assertEquals(listOf(".ld.so"), internal.list()!!.toList())
        assertTrue(Files.isSymbolicLink(File(internal, ".ld.so/active").toPath()))
        assertEquals(
            listOf("${onCard.path}:/root/.var/app/$id", "${internal.path}/.ld.so:/root/.var/app/$id/.ld.so"),
            FlatpakData.binds(app),
        )
        assertEquals(setOf(id), FlatpakData.enabled(app))
    }

    @Test fun movingBackRestoresInternalDataAndDropsTheBind() {
        seed()
        assertNull(move(id, true))
        assertNull(move(id, false))
        assertEquals(1000, File(FlatpakData.internalDir(app, id), ".srb2kart/addons/map.pk3").length())
        assertFalse(File(card, "flatpak-data/$id").exists())
        assertTrue(FlatpakData.binds(app).isEmpty())
        assertTrue(FlatpakData.enabled(app).isEmpty())
    }

    @Test fun otherAppsAndUnchosenAppsStayInternal() {
        seed()
        seed("org.example.Other")
        assertNull(move(id, true))
        assertTrue(File(FlatpakData.internalDir(app, "org.example.Other"), ".srb2kart/addons/map.pk3").isFile)
        assertEquals(2, FlatpakData.binds(app).size)
    }

    @Test fun dataWithSymlinksIsRefusedAndLeftWhereItIs() {
        seed()
        Files.createSymbolicLink(File(FlatpakData.internalDir(app, id), "link").toPath(), File("config").toPath())
        assertEquals(app.getString(R.string.store_data_symlinks), move(id, true))
        assertTrue(File(FlatpakData.internalDir(app, id), ".srb2kart/addons/map.pk3").isFile)
        assertTrue(FlatpakData.enabled(app).isEmpty())
        assertFalse(File(card, "flatpak-data/$id").exists())
    }

    @Test fun aFullDestinationRefusesTheMoveAndKeepsTheData() {
        seed(bytes = 4096)
        val bytes = FlatpakData.size(FlatpakData.internalDir(app, id))
        free = bytes + (63L shl 20)
        assertEquals(app.getString(R.string.store_data_space, com.droiddeck.launcher.core.FileUtils.sizeToString(free)), move(id, true))
        assertTrue(File(FlatpakData.internalDir(app, id), ".srb2kart/addons/map.pk3").isFile)
        assertTrue(File(card, "flatpak-data/$id").list().orEmpty().isEmpty())
        assertTrue(File(card, "flatpak-data").listFiles().orEmpty().none { it.name.endsWith(".moving") })
        free = bytes + (64L shl 20)
        assertNull(move(id, true))
    }

    @Test fun aRunningSessionBlocksTheMove() {
        seed()
        SessionState.running = true
        assertEquals(app.getString(R.string.store_data_session), move(id, true))
        assertTrue(FlatpakData.enabled(app).isEmpty())
    }

    @Test fun anUnattachedCardFallsBackToInternalWithoutForgettingTheChoice() {
        seed()
        assertNull(move(id, true))
        SessionPrefs.setGameStorage(app, "/storage/0000-0000/Android/data/x/files/steam", "gone")
        assertTrue(FlatpakData.binds(app).isEmpty())
        assertEquals(setOf(id), FlatpakData.enabled(app))
        assertEquals(app.getString(R.string.store_data_no_storage), move(id, false))
    }

    @Test fun internalOnlyStorageOffersNothing() {
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        assertFalse(FlatpakData.available(app))
        assertEquals(app.getString(R.string.store_data_no_storage), move(id, true))
    }

    @Test fun aNonEmptyDestinationIsNeverOverwritten() {
        seed()
        File(card, "flatpak-data/$id").mkdirs()
        File(card, "flatpak-data/$id/keep.txt").writeText("old")
        assertEquals(app.getString(R.string.store_data_failed), move(id, true))
        assertEquals("old", File(card, "flatpak-data/$id/keep.txt").readText())
        assertTrue(File(FlatpakData.internalDir(app, id), ".srb2kart/addons/map.pk3").isFile)
    }

    @Test fun unsafeIdsNeverReachABindOrAPath() {
        assertEquals(app.getString(R.string.store_data_failed), move("../../etc", true))
        listOf(".", "..", "...").forEach { assertEquals(app.getString(R.string.store_data_failed), move(it, true)) }
        SessionPrefs.setFlatpakCardData(app, "..", true)
        SessionPrefs.setFlatpakCardData(app, ".", true)
        assertTrue(FlatpakData.binds(app).isEmpty())
        SessionPrefs.setFlatpakCardData(app, "a:b/../c", true)
        assertTrue(FlatpakData.binds(app).isEmpty())
    }

    @Test fun sessionBindsWaitForAMoveInProgress() {
        seed()
        val inMove = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var result: String? = "pending"
        val mover = Thread {
            result = FlatpakData.move(app, id, true, { _, _ -> }, { inMove.countDown(); release.await(10, java.util.concurrent.TimeUnit.SECONDS); free })
        }
        mover.start()
        assertTrue(inMove.await(10, java.util.concurrent.TimeUnit.SECONDS))
        SessionState.running = true
        var binds: List<String>? = null
        val session = Thread { binds = FlatpakData.binds(app) }
        try {
            session.start()
            session.join(300)
            assertTrue(session.isAlive)
        } finally {
            release.countDown()
            mover.join(10_000)
            session.join(10_000)
        }
        assertNull(result)
        assertEquals(2, binds!!.size)
        assertTrue(binds!!.first().startsWith(File(card, "flatpak-data/$id").path))
    }

    @Test fun aMoveIsRefusedOnceASessionIsRunning() {
        seed()
        SessionState.running = true
        assertEquals(app.getString(R.string.store_data_session), move(id, true))
        assertTrue(FlatpakData.enabled(app).isEmpty())
    }

    @Test fun aSessionStillTearingDownBlocksTheMove() {
        seed()
        SessionState.phase = SessionPhase.STOPPING
        assertEquals(app.getString(R.string.store_data_session), move(id, true))
        SessionState.phase = SessionPhase.PREPARING
        assertEquals(app.getString(R.string.store_data_session), move(id, true))
        assertTrue(FlatpakData.enabled(app).isEmpty())
        SessionState.phase = SessionPhase.IDLE
        assertNull(move(id, true))
    }

    private fun cardFolder() = File(card, "flatpak-data/$id")

    @Test fun aMoveInterruptedMidPlacementCanBeRetried() {
        seed()
        assertTrue(SessionPrefs.setFlatpakDataMoving(app, FlatpakData.marker(id, cardFolder()), true))
        File(cardFolder(), ".srb2kart").mkdirs()
        File(cardFolder(), "partial.bin").writeText("half")
        assertNull(move(id, true))
        assertFalse(File(cardFolder(), "partial.bin").exists())
        assertEquals(1000, File(cardFolder(), ".srb2kart/addons/map.pk3").length())
        assertTrue(SessionPrefs.flatpakDataMoving(app).isEmpty())
        assertTrue(FlatpakData.entries(FlatpakData.internalDir(app, id)).isEmpty())
    }

    @Test fun aJournalForAnotherDestinationNeverAuthorisesClearingThisOne() {
        seed()
        val other = File(tmp.newFolder("other"), "flatpak-data/$id")
        assertTrue(SessionPrefs.setFlatpakDataMoving(app, FlatpakData.marker(id, other), true))
        File(cardFolder(), "keep.txt").apply { parentFile!!.mkdirs() }.writeText("old")
        assertEquals(app.getString(R.string.store_data_failed), move(id, true))
        assertEquals("old", File(cardFolder(), "keep.txt").readText())
        assertTrue(File(FlatpakData.internalDir(app, id), ".srb2kart/addons/map.pk3").isFile)
    }

    @Test fun anInterruptedMoveBackCanBeRetried() {
        seed()
        assertNull(move(id, true))
        val internal = FlatpakData.internalDir(app, id)
        assertTrue(SessionPrefs.setFlatpakDataMoving(app, FlatpakData.marker(id, internal), true))
        File(internal, "partial.bin").writeText("half")
        assertNull(move(id, false))
        assertFalse(File(internal, "partial.bin").exists())
        assertEquals(1000, File(internal, ".srb2kart/addons/map.pk3").length())
    }

    @Test fun oldDataLeftAtTheSourceIsDroppedAtSessionStart() {
        seed()
        assertNull(move(id, true))
        val internal = FlatpakData.internalDir(app, id)
        File(internal.parentFile, ".${internal.name}.old").apply { mkdirs() }.let { File(it, "stale.bin").writeText("old") }
        assertEquals(2, FlatpakData.binds(app).size)
        assertFalse(File(internal.parentFile, ".${internal.name}.old").exists())
        assertEquals(1000, File(cardFolder(), ".srb2kart/addons/map.pk3").length())
    }

    @Test fun dataWrittenWhileTheCardWasDetachedSurvivesItsReturn() {
        seed()
        assertNull(move(id, true))
        SessionPrefs.setGameStorage(app, "/storage/0000-0000/Android/data/x/files/steam", "gone")
        assertTrue(FlatpakData.binds(app).isEmpty())
        val save = File(FlatpakData.internalDir(app, id), "save.dat").apply { writeText("new") }
        SessionPrefs.setGameStorage(app, card.path, "SD")
        assertEquals(2, FlatpakData.binds(app).size)
        assertEquals("new", save.readText())
        assertEquals(app.getString(R.string.store_data_failed), move(id, false))
        assertEquals("new", save.readText())
        assertEquals(1000, File(cardFolder(), ".srb2kart/addons/map.pk3").length())
        assertEquals(setOf(id), FlatpakData.enabled(app))
    }

    @Test fun aSymlinkOnTheCardRefusesTheMoveBack() {
        seed()
        assertNull(move(id, true))
        Files.createSymbolicLink(File(cardFolder(), "link").toPath(), File("config").toPath())
        assertEquals(app.getString(R.string.store_data_symlinks), move(id, false))
        assertEquals(setOf(id), FlatpakData.enabled(app))
        assertEquals(1000, File(cardFolder(), ".srb2kart/addons/map.pk3").length())
        assertTrue(FlatpakData.entries(FlatpakData.internalDir(app, id)).isEmpty())
    }

    @Test fun anUnreadableSourceIsNotTreatedAsEmpty() {
        FlatpakData.internalDir(app, id).apply { parentFile!!.mkdirs() }.writeText("not a folder")
        assertEquals(app.getString(R.string.store_data_failed), move(id, true))
        assertTrue(FlatpakData.enabled(app).isEmpty())
        assertTrue(FlatpakData.internalDir(app, id).isFile)
    }

    @Test fun anAppWithoutDataMovesAsAnEmptyFolder() {
        assertNull(move(id, true))
        assertEquals(setOf(id), FlatpakData.enabled(app))
    }
}
