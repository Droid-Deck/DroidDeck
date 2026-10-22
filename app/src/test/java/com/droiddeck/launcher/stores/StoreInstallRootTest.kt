package com.droiddeck.launcher.stores

import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StoreInstallRootTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun theDefaultRootIsInternalWhateverTheSteamLibrarySettingSays() {
        val card = File(app.filesDir, "fake-card/steam").apply { mkdirs() }
        SessionPrefs.setGameStorage(app, card.path, "Card")
        assertEquals(File(LinuxRuntime.rootDir(app), "root/Games/Stores").absolutePath, StoreInstallRoot.installRoot(app).absolutePath)
        assertTrue(StoreInstallRoot.roots(app).any { it.absolutePath == File(card, "Games").absolutePath })
        assertFalse(StoreInstallRoot.targets(app).first().removable)
    }

    @Test fun theSteamLibraryFolderIsOfferedAsAnInstallTarget() {
        val library = File(app.filesDir, "Documents/Games").apply { mkdirs() }
        SessionPrefs.setGameStorage(app, library.path, "Games")
        val targets = StoreInstallRoot.targets(app)
        assertEquals(2, targets.size)
        assertEquals(File(library, "Games").absolutePath, targets.last().root.absolutePath)
        assertTrue(targets.last().folder)
        assertTrue(targets.last().label.startsWith("Games"))
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        assertEquals(1, StoreInstallRoot.targets(app).size)
    }

    @Test fun anExistingInstallWinsOverAFreshFolderUnderTheChosenRoot() {
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        val existing = File(StoreInstallRoot.storeDir(StoreInstallRoot.internalRoot(app), Store.GOG), "Old Name").apply { mkdirs() }
        StoreGameSidecar(Store.GOG, "42", "New Name", "game.exe").write(existing)
        val other = File(app.filesDir, "elsewhere/Games")
        assertEquals(existing.absolutePath, StoreInstallRoot.folderFor(app, Store.GOG, "42", "New Name", other).absolutePath)
        assertEquals(File(StoreInstallRoot.storeDir(other, Store.GOG), "Fresh").absolutePath, StoreInstallRoot.folderFor(app, Store.GOG, "7", "Fresh", other).absolutePath)
    }

    @Test fun aFolderLeftWithoutASidecarIsAnUnfinishedInstallAndIsResumedInPlace() {
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        val epic = StoreInstallRoot.storeDir(StoreInstallRoot.internalRoot(app), Store.EPIC)
        val half = File(epic, StoreInstallRoot.folderName("Metalstorm", "abc")).apply { mkdirs() }
        File(half, ".chunks").mkdirs()
        File(half, "Metalstorm.exe").writeText("MZ")
        assertFalse(StoreInstallRoot.isFinished(half))
        assertTrue(StoreInstallRoot.unfinished(app).any { it.store == Store.EPIC && it.id == null && it.folder.absolutePath == half.absolutePath })
        assertEquals(half.absolutePath, StoreInstallRoot.folderFor(app, Store.EPIC, "abc", "Metalstorm", File(app.filesDir, "card/Games")).absolutePath)
        StoreInstalls.begin(half, Store.EPIC, "abc", "Metalstorm", null, null)
        assertTrue(StoreInstallRoot.unfinished(app).any { it.id == "abc" })
        StoreGameSidecar(Store.EPIC, "abc", "Metalstorm", "Metalstorm.exe").write(half)
        assertTrue(StoreInstallRoot.isFinished(half))
        assertTrue(StoreInstallRoot.unfinished(app).none { it.folder.absolutePath == half.absolutePath })
    }

    @Test fun theScratchCacheIsPrivateAndNamedSafely() {
        val dir = StoreInstallRoot.scratchDir(app, Store.EPIC, "a/b:c")
        assertTrue(dir.absolutePath.startsWith(app.cacheDir.absolutePath))
        assertEquals("a_b_c", dir.name)
    }

    @Test fun estimatedBytesNeededUsesKnownInstallSizeForAllStores() {
        val item = CatalogItem(Store.EPIC, "game1", "Game 1", imageUrl = null, owned = true, sizeBytes = 10_000_000L)
        assertEquals(10_000_000L, StoreInstallRoot.estimatedBytesNeeded(item))

        val gogItem = CatalogItem(Store.GOG, "game2", "Game 2", imageUrl = null, owned = true, sizeBytes = 10_000_000L)
        assertEquals(10_000_000L, StoreInstallRoot.estimatedBytesNeeded(gogItem))
    }

    @Test fun checkStorageReportsEnoughWhenSpaceIsUnknownOrAvailable() {
        val zeroItem = CatalogItem(Store.EPIC, "game1", "Game 1", imageUrl = null, owned = true, sizeBytes = 0L)
        val check = StoreInstallRoot.checkStorage(app, zeroItem, StoreInstallRoot.internalRoot(app))
        assertTrue(check.enough)
    }

    @Test fun availableBytesUsesNearestExistingAncestorWithoutCreatingDirectories() {
        val existing = File(app.filesDir, "storage-probe").apply { mkdirs() }
        val missing = File(existing, "not-created/another-level/game")
        ShadowStatFs.registerStats(existing.path, 1024, 100, 100)

        assertTrue(StoreInstallRoot.availableBytes(missing) > 0L)
        assertFalse(File(existing, "not-created").exists())
    }

    @Test fun epicDefersFullSizeChecksToItsManifestDeltaForResume() {
        val item = CatalogItem(Store.EPIC, "large", "Large", imageUrl = null, owned = true, sizeBytes = Long.MAX_VALUE)
        val check = StoreInstallRoot.checkStorage(app, item, StoreInstallRoot.internalRoot(app))
        assertTrue(check.enough)
        assertEquals(Long.MAX_VALUE, check.requiredBytes)
    }

    @Test fun gogAndAmazonResumeChecksUseTheExistingVolumeAndDeferRemainingWork() {
        val card = File(app.filesDir, "resume-card").apply { mkdirs() }
        SessionPrefs.setGameStorage(app, card.path, "Card")
        for (store in listOf(Store.GOG, Store.AMAZON)) {
            val item = CatalogItem(store, "resume-${store.id}", "Partial ${store.id}", imageUrl = null, sizeBytes = Long.MAX_VALUE)
            val folder = File(StoreInstallRoot.storeDir(File(card, "Games"), store), StoreInstallRoot.folderName(item.title, item.id)).apply { mkdirs() }
            StoreInstalls.begin(folder, store, item.id, item.title, null, null)
            val check = StoreInstallRoot.checkStorage(app, item, StoreInstallRoot.internalRoot(app))
            assertTrue(check.enough)
            assertEquals(StoreInstallRoot.availableBytes(folder), check.availableBytes)
            assertEquals(folder, StoreInstallRoot.folderFor(app, store, item.id, item.title))
        }
    }

    @Test fun freshGogAndAmazonStillRejectInsufficientCatalogSpace() {
        StoreInstallRoot.internalRoot(app).mkdirs()
        ShadowStatFs.registerStats(StoreInstallRoot.internalRoot(app).path, 1024, 100, 100)
        for (store in listOf(Store.GOG, Store.AMAZON)) {
            val item = CatalogItem(store, "too-large-${store.id}", "Too large ${store.id}", imageUrl = null, sizeBytes = Long.MAX_VALUE)
            assertFalse(StoreInstallRoot.checkStorage(app, item, StoreInstallRoot.internalRoot(app)).enough)
        }
    }

    @Test fun measuredZeroRejectsFreshGogAndAmazonRatherThanBeingUnknown() {
        val root = File(app.filesDir, "zero-space").apply { mkdirs() }
        ShadowStatFs.registerStats(root.path, 1024, 0, 0)
        val missing = File(root, "not-created/game")
        assertEquals(0L, StoreInstallRoot.availableBytes(missing))
        assertFalse(File(root, "not-created").exists())
        for (store in listOf(Store.GOG, Store.AMAZON)) {
            val item = CatalogItem(store, "zero-${store.id}", "Zero ${store.id}", imageUrl = null, sizeBytes = 1L)
            val check = StoreInstallRoot.checkStorage(app, item, missing)
            assertFalse(check.enough)
            assertEquals(0L, check.availableBytes)
        }
    }

    @Test fun failedProbesAreUnknownAndDoNotCreateOrWriteToTargets() {
        val root = File(app.filesDir, "read-only-probe").apply { mkdirs() }
        val missing = File(root, "not-created/game")
        val fail: (File) -> Long = { throw java.io.IOException("unavailable") }
        assertEquals(-1L, StoreInstallRoot.probeAvailableBytes(missing, fail, fail))
        assertEquals(-1L, StoreInstallRoot.probeAvailableBytes(missing, fail, { 0L }))
        assertEquals(42L, StoreInstallRoot.probeAvailableBytes(missing, fail, { 42L }))
        assertEquals(0L, StoreInstallRoot.probeAvailableBytes(missing, { 0L }, fail))
        assertFalse(File(root, "not-created").exists())
        assertTrue(root.listFiles().orEmpty().isEmpty())
        assertFalse(StoreInstallRoot.insufficientSpace(1L, -1L))
        assertTrue(StoreInstallRoot.insufficientSpace(1L, 0L))
        assertFalse(StoreInstallRoot.insufficientSpace(1L, 1L))
        assertFalse(StoreInstallRoot.insufficientSpace(0L, 0L))
    }
}
