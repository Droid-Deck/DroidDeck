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

    @Test fun theScratchCacheIsPrivateAndNamedSafely() {
        val dir = StoreInstallRoot.scratchDir(app, Store.EPIC, "a/b:c")
        assertTrue(dir.absolutePath.startsWith(app.cacheDir.absolutePath))
        assertEquals("a_b_c", dir.name)
    }
}
