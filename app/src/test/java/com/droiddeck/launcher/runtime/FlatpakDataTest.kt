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

    private val gone = "/storage/0000-0000/Android/data/x/files/steam"

    private fun pinTo(storage: String) {
        SessionPrefs.flatpakCardData(app).filter { it.startsWith("$id>") }.forEach { SessionPrefs.setFlatpakCardData(app, it, false) }
        SessionPrefs.setFlatpakCardData(app, "$id>$storage", true)
    }

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
        pinTo(gone)
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

    @Test fun aFailedRetryKeepsTheJournalUntilTheDestinationIsVerifiedEmpty() {
        seed()
        val marker = FlatpakData.marker(id, cardFolder())
        assertTrue(SessionPrefs.setFlatpakDataMoving(app, marker, true))
        File(card, "flatpak-data").mkdirs()
        cardFolder().writeText("blocked")
        assertEquals(app.getString(R.string.store_data_failed), move(id, true))
        assertEquals(setOf(marker), SessionPrefs.flatpakDataMoving(app))
        assertTrue(cardFolder().delete())
        File(cardFolder(), ".srb2kart").mkdirs()
        File(cardFolder(), "partial.bin").writeText("half")
        assertNull(move(id, true))
        assertFalse(File(cardFolder(), "partial.bin").exists())
        assertEquals(1000, File(cardFolder(), ".srb2kart/addons/map.pk3").length())
        assertTrue(SessionPrefs.flatpakDataMoving(app).isEmpty())
    }

    @Test fun savesWrittenWhileDetachedAreNotClearedByAnInterruptedMoveBackRetry() {
        seed()
        assertNull(move(id, true))
        val internal = FlatpakData.internalDir(app, id)
        assertTrue(SessionPrefs.setFlatpakDataMoving(app, FlatpakData.marker(id, internal), true))
        File(internal, "partial.bin").writeText("half")
        pinTo(gone)
        assertTrue(FlatpakData.binds(app).isEmpty())
        val save = File(internal, "save.dat").apply { writeText("new") }
        pinTo(card.path)
        assertEquals(app.getString(R.string.store_data_failed), move(id, false))
        assertEquals("new", save.readText())
        assertEquals(1000, File(cardFolder(), ".srb2kart/addons/map.pk3").length())
        assertEquals(setOf(id), FlatpakData.enabled(app))
    }

    @Test fun anInterruptedMoveBackStaysRetryableWhenTheCardSessionRanAttached() {
        seed()
        assertNull(move(id, true))
        val internal = FlatpakData.internalDir(app, id)
        assertTrue(SessionPrefs.setFlatpakDataMoving(app, FlatpakData.marker(id, internal), true))
        File(internal, "partial.bin").writeText("half")
        assertEquals(2, FlatpakData.binds(app).size)
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
        pinTo(gone)
        assertTrue(FlatpakData.binds(app).isEmpty())
        val save = File(FlatpakData.internalDir(app, id), "save.dat").apply { writeText("new") }
        pinTo(card.path)
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

    @Test fun aSymlinkAtTheDataFolderRootRefusesTheMove() {
        val other = "org.other.App"
        seed(other)
        val internal = FlatpakData.internalDir(app, id)
        internal.parentFile!!.mkdirs()
        Files.createSymbolicLink(internal.toPath(), FlatpakData.internalDir(app, other).toPath())
        assertEquals(app.getString(R.string.store_data_symlinks), move(id, true))
        assertEquals(1000, File(FlatpakData.internalDir(app, other), ".srb2kart/addons/map.pk3").length())
        assertTrue(Files.isSymbolicLink(internal.toPath()))
    }

    @Test fun aSymlinkAtTheCardFolderRootRefusesTheMoveBack() {
        val other = "org.other.App"
        seed(other)
        val cardDir = File(card, "flatpak-data/$id")
        cardDir.parentFile!!.mkdirs()
        Files.createSymbolicLink(cardDir.toPath(), FlatpakData.internalDir(app, other).toPath())
        assertEquals(app.getString(R.string.store_data_symlinks), move(id, false))
        assertEquals(1000, File(FlatpakData.internalDir(app, other), ".srb2kart/addons/map.pk3").length())
    }

    @Test fun cleaningAnOldCopyNeverFollowsALinkIntoAnotherApp() {
        val other = "org.other.App"
        seed(other)
        seed()
        assertNull(move(id, true))
        val internal = FlatpakData.internalDir(app, id)
        val old = File(internal.parentFile, ".${internal.name}.old")
        Files.createSymbolicLink(old.toPath(), FlatpakData.internalDir(app, other).toPath())
        assertEquals(2, FlatpakData.binds(app).size)
        assertFalse(Files.exists(old.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals(1000, File(FlatpakData.internalDir(app, other), ".srb2kart/addons/map.pk3").length())
    }

    @Test fun cleaningAnOldCopyNeverFollowsALinkInsideIt() {
        val other = "org.other.App"
        seed(other)
        seed()
        assertNull(move(id, true))
        val internal = FlatpakData.internalDir(app, id)
        val old = File(internal.parentFile, ".${internal.name}.old").apply { mkdirs() }
        Files.createSymbolicLink(File(old, "link").toPath(), FlatpakData.internalDir(app, other).toPath())
        assertEquals(2, FlatpakData.binds(app).size)
        assertFalse(old.exists())
        assertEquals(1000, File(FlatpakData.internalDir(app, other), ".srb2kart/addons/map.pk3").length())
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

    private fun otherWithOnlyKept(): File {
        val other = FlatpakData.internalDir(app, "org.other.App")
        File(other, ".ld.so/real").apply { mkdirs() }
        return other
    }

    @Test fun aSymlinkAtTheInternalFolderRootRefusesTheMoveBack() {
        seed()
        assertNull(move(id, true))
        val other = otherWithOnlyKept()
        val internal = FlatpakData.internalDir(app, id)
        assertTrue(internal.deleteRecursively())
        Files.createSymbolicLink(internal.toPath(), other.toPath())
        assertEquals(app.getString(R.string.store_data_symlinks), move(id, false))
        assertEquals(setOf(id), FlatpakData.enabled(app))
        assertEquals(1000, File(cardFolder(), ".srb2kart/addons/map.pk3").length())
        assertTrue(File(other, ".ld.so/real").exists())
        assertEquals(listOf(".ld.so"), other.list()!!.toList())
        assertTrue(Files.isSymbolicLink(internal.toPath()))
    }

    @Test fun aSymlinkAtTheInternalFolderRootIsNeverBoundOrCleaned() {
        seed()
        assertNull(move(id, true))
        val other = otherWithOnlyKept()
        val internal = FlatpakData.internalDir(app, id)
        assertTrue(internal.deleteRecursively())
        Files.createSymbolicLink(internal.toPath(), other.toPath())
        assertTrue(FlatpakData.binds(app).isEmpty())
        assertEquals(1000, File(cardFolder(), ".srb2kart/addons/map.pk3").length())
        assertEquals(listOf(".ld.so"), other.list()!!.toList())
    }

    @Test fun aSymlinkAtTheCardFolderRootIsNeverBound() {
        seed()
        assertNull(move(id, true))
        val other = otherWithOnlyKept()
        assertTrue(cardFolder().deleteRecursively())
        Files.createSymbolicLink(cardFolder().toPath(), other.toPath())
        assertTrue(FlatpakData.binds(app).isEmpty())
        assertEquals(listOf(".ld.so"), other.list()!!.toList())
    }

    @Test fun dataStaysBoundToTheCardItWasMovedTo() {
        seed()
        assertNull(move(id, true))
        val second = tmp.newFolder("second")
        SessionPrefs.setGameStorage(app, second.path, "SD2")
        assertEquals(listOf("${cardFolder().path}:/root/.var/app/$id", "${FlatpakData.internalDir(app, id).path}/.ld.so:/root/.var/app/$id/.ld.so"), FlatpakData.binds(app))
        assertFalse(File(second, "flatpak-data").exists())
        assertNull(move(id, false))
        assertEquals(1000, File(FlatpakData.internalDir(app, id), ".srb2kart/addons/map.pk3").length())
        assertFalse(cardFolder().exists())
        assertTrue(FlatpakData.enabled(app).isEmpty())
    }

    @Test fun aReadOnlyCardRefusesTheMoveAndKeepsTheData() {
        seed()
        assertEquals(app.getString(R.string.store_data_failed), FlatpakData.move(app, id, true, { _, _ -> }, { free }, { false }))
        assertEquals(1000, File(FlatpakData.internalDir(app, id), ".srb2kart/addons/map.pk3").length())
        assertTrue(FlatpakData.enabled(app).isEmpty())
        assertTrue(FlatpakData.entries(cardFolder()).isEmpty())
        assertTrue(SessionPrefs.flatpakDataMoving(app).isEmpty())
    }

    @Test fun aReadOnlyCardRefusesTheMoveBackAndKeepsTheChoice() {
        seed()
        assertNull(move(id, true))
        assertEquals(app.getString(R.string.store_data_failed), FlatpakData.move(app, id, false, { _, _ -> }, { free }, { false }))
        assertEquals(setOf(id), FlatpakData.enabled(app))
        assertEquals(1000, File(cardFolder(), ".srb2kart/addons/map.pk3").length())
        assertTrue(FlatpakData.entries(FlatpakData.internalDir(app, id)).isEmpty())
    }

    @Test fun aReadOnlyCardFallsBackToInternalDataWithoutForgettingTheChoice() {
        seed()
        assertNull(move(id, true))
        assertTrue(FlatpakData.binds(app) { false }.isEmpty())
        assertEquals(setOf(id), FlatpakData.enabled(app))
        assertEquals(1000, File(cardFolder(), ".srb2kart/addons/map.pk3").length())
    }

    @Test fun aSymlinkAtTheCardDataParentIsNeverBoundAndRefusesTheMoveBack() {
        seed()
        assertNull(move(id, true))
        val parent = File(card, "flatpak-data")
        val real = File(tmp.newFolder("redirected"), "flatpak-data")
        assertTrue(parent.renameTo(real))
        Files.createSymbolicLink(parent.toPath(), real.toPath())
        assertTrue(FlatpakData.binds(app).isEmpty())
        assertEquals(app.getString(R.string.store_data_symlinks), move(id, false))
        assertEquals(1000, File(real, "$id/.srb2kart/addons/map.pk3").length())
        assertEquals(setOf(id), FlatpakData.enabled(app))
    }

    @Test fun aSymlinkAtTheCardDataParentRefusesTheMoveToTheCard() {
        seed()
        val real = File(tmp.newFolder("redirected"), "flatpak-data").apply { mkdirs() }
        Files.createSymbolicLink(File(card, "flatpak-data").toPath(), real.toPath())
        assertEquals(app.getString(R.string.store_data_symlinks), move(id, true))
        assertTrue(real.list()!!.isEmpty())
        assertEquals(1000, File(FlatpakData.internalDir(app, id), ".srb2kart/addons/map.pk3").length())
        assertTrue(FlatpakData.enabled(app).isEmpty())
    }

    @Test fun aSymlinkAtTheInternalDataParentRefusesTheMove() {
        seed()
        val appDir = FlatpakData.internalDir(app, id).parentFile!!
        val real = File(tmp.newFolder("redirected"), "app")
        assertTrue(appDir.renameTo(real))
        Files.createSymbolicLink(appDir.toPath(), real.toPath())
        assertEquals(app.getString(R.string.store_data_symlinks), move(id, true))
        assertEquals(1000, File(real, "$id/.srb2kart/addons/map.pk3").length())
        assertTrue(FlatpakData.enabled(app).isEmpty())
    }

    @Test fun aJournalThatCannotBeClearedGivesTheSessionAnEmptyFolderInsteadOfInternalData() {
        seed()
        assertNull(move(id, true))
        val internal = FlatpakData.internalDir(app, id)
        assertTrue(SessionPrefs.setFlatpakDataMoving(app, FlatpakData.marker(id, internal), true))
        File(internal, "partial.bin").writeText("half")
        pinTo(gone)
        val binds = FlatpakData.binds(app, unmark = { false })
        assertEquals(1, binds.size)
        val scratch = binds.single().substringBefore(":")
        assertNotEquals(internal.path, scratch)
        assertTrue(binds.single().endsWith(":/root/.var/app/$id"))
        assertTrue(File(scratch).list()!!.isEmpty())
        assertEquals("half", File(internal, "partial.bin").readText())
        assertTrue(SessionPrefs.flatpakDataMoving(app).contains(FlatpakData.marker(id, internal)))
    }

    @Test fun aClearedJournalLetsTheSessionUseInternalData() {
        seed()
        assertNull(move(id, true))
        val internal = FlatpakData.internalDir(app, id)
        assertTrue(SessionPrefs.setFlatpakDataMoving(app, FlatpakData.marker(id, internal), true))
        pinTo(gone)
        assertTrue(FlatpakData.binds(app).isEmpty())
        assertTrue(SessionPrefs.flatpakDataMoving(app).isEmpty())
    }
}
