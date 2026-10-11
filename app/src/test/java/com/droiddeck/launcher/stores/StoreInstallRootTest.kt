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

/** Where a store game goes: internal storage unless the user picks a card, and back to its own folder on a rerun. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StoreInstallRootTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun theDefaultRootIsInternalWhateverTheSteamLibrarySettingSays() {
        val card = File(app.filesDir, "fake-card/steam").apply { mkdirs() }
        SessionPrefs.setGameStorage(app, card.path, "Card")
        assertEquals(File(LinuxRuntime.rootDir(app), "root/Games/Stores").absolutePath, StoreInstallRoot.installRoot(app).absolutePath)
        // The library's Games folder is still scanned, for installs an earlier build put there.
        assertTrue(StoreInstallRoot.roots(app).any { it.absolutePath == File(card, "Games").absolutePath })
        assertFalse(StoreInstallRoot.targets(app).first().removable)
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
        // Install again, even with another root picked: the same folder, no duplicate.
        assertEquals(half.absolutePath, StoreInstallRoot.folderFor(app, Store.EPIC, "abc", "Metalstorm", File(app.filesDir, "card/Games")).absolutePath)
        // Begun and finished: the folder becomes a game.
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
}
