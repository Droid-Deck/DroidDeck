package com.droiddeck.launcher.stores

import android.app.Application
import com.droiddeck.launcher.frontend.AddedGames
import com.droiddeck.launcher.session.SessionPrefs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class StoreLaunchTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun install(state: String = StoreGameSidecar.STATE_INSTALLED): File {
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        val folder = File(StoreInstallRoot.storeDir(StoreInstallRoot.installRoot(app), Store.XBOX), "Deep Rock Galactic")
        File(folder, "FSD/Binaries/WinGDK/FSD.exe").apply { parentFile!!.mkdirs(); writeText("licensed image") }
        StoreGameSidecar(Store.XBOX, "9NHFVWX1V7QJ", "Deep Rock Galactic", "FSD/Binaries/WinGDK/FSD.exe", state = state).write(folder)
        return folder
    }

    @Test fun aCompletedInstallCanLaunchBeforeTheFrontendLibraryRefreshes() {
        val folder = install()
        val game = StoreLaunch.installedGame(app, Store.XBOX, "9NHFVWX1V7QJ")!!
        val added = AddedGames.single(app, folder.path)!!
        assertEquals("xbox", game.source)
        assertEquals("9NHFVWX1V7QJ", game.storeId)
        assertEquals(folder, game.gameFiles)
        assertEquals(added.gameId, game.gameId)
        assertTrue(added.guestExe.endsWith("/FSD/Binaries/WinGDK/FSD.exe"))
    }

    @Test fun unfinishedPackagesCannotBecomePlayableThroughTheFallback() {
        for (state in listOf(StoreGameSidecar.STATE_INSTALLING, StoreGameSidecar.STATE_UPDATING)) {
            install(state)
            assertNull(StoreLaunch.installedGame(app, Store.XBOX, "9NHFVWX1V7QJ"))
        }
    }

    @Test fun anotherStoreOrProductCannotUseTheXboxInstall() {
        install()
        assertNull(StoreLaunch.installedGame(app, Store.GOG, "9NHFVWX1V7QJ"))
        assertNull(StoreLaunch.installedGame(app, Store.XBOX, "OTHERPRODUCT"))
    }

    @Test fun aMissingExecutableDoesNotBecomeALaunchTarget() {
        val folder = install()
        File(folder, "FSD/Binaries/WinGDK/FSD.exe").delete()
        assertNull(StoreLaunch.installedGame(app, Store.XBOX, "9NHFVWX1V7QJ"))
    }
}
