package com.droiddeck.launcher.stores

import android.app.Application
import android.os.Looper
import com.droiddeck.launcher.frontend.AddedGames
import com.droiddeck.launcher.session.SessionPrefs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class StoreLaunchTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun install(state: String = StoreGameSidecar.STATE_INSTALLED): File {
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        val storeDir = StoreInstallRoot.storeDir(StoreInstallRoot.installRoot(app), Store.XBOX)
        storeDir.listFiles()?.filter { StoreGameSidecar.read(it)?.id == "9NHFVWX1V7QJ" }?.forEach { it.deleteRecursively() }
        val folder = File(storeDir, "Deep Rock Galactic")
        File(folder, "FSD/Binaries/WinGDK/FSD.exe").apply { parentFile!!.mkdirs(); writeText("licensed image") }
        StoreGameSidecar(Store.XBOX, "9NHFVWX1V7QJ", "Deep Rock Galactic", "FSD/Binaries/WinGDK/FSD.exe", state = state).write(folder)
        SessionPrefs.setAddedGameRemoved(app, folder.path, false)
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

    @Test fun maintenanceBlockedLaunchReportsTheBlockWithoutLaunching() {
        val folder = install()
        val operation = com.droiddeck.launcher.stores.maintenance.StoreMaintenance.operationFile(folder)
        operation.parentFile!!.mkdirs()
        operation.writeText(JSONObject().put("schema", 1).put("folder", folder.canonicalPath).put("phase", "applying").toString())
        val blocked = CountDownLatch(1)
        val ready = AtomicBoolean(false)

        try {
            StoreLaunch.prepare(app, Store.XBOX, "9NHFVWX1V7QJ", onBlocked = { blocked.countDown() }) {
                ready.set(true)
            }

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (blocked.count > 0 && System.nanoTime() < deadline) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                Thread.sleep(10)
            }
            assertTrue("the blocked callback was not posted", blocked.await(1, TimeUnit.SECONDS))
            assertFalse("an excluded install must not reach the launch callback", ready.get())
        } finally {
            operation.parentFile?.deleteRecursively()
        }
    }

    @Test fun installedTargetWinsOverAnEarlierUpdatingDuplicate() {
        val updating = install(StoreGameSidecar.STATE_UPDATING)
        val installed = File(updating.parentFile, "ZZZ installed copy")
        File(installed, "FSD/Binaries/WinGDK/FSD.exe").apply { parentFile!!.mkdirs(); writeText("installed image") }
        StoreGameSidecar(Store.XBOX, "9NHFVWX1V7QJ", "Deep Rock Galactic", "FSD/Binaries/WinGDK/FSD.exe").write(installed)

        try {
            assertEquals(installed, StoreLaunch.findInstalled(app, Store.XBOX, "9NHFVWX1V7QJ")!!.first)
        } finally {
            installed.deleteRecursively()
        }
    }

    @Test fun prepareScansOffTheCallerAndPostsReadyBackToMainLooper() {
        val updating = install(StoreGameSidecar.STATE_UPDATING)
        val installed = File(updating.parentFile, "ZZZ installed copy")
        File(installed, "FSD/Binaries/WinGDK/FSD.exe").apply { parentFile!!.mkdirs(); writeText("installed image") }
        StoreGameSidecar(Store.XBOX, "9NHFVWX1V7QJ", "Deep Rock Galactic", "FSD/Binaries/WinGDK/FSD.exe").write(installed)
        val callbackRan = AtomicBoolean(false)
        val callbackOnMain = AtomicBoolean(false)
        val callback = CountDownLatch(1)

        try {
            StoreLaunch.prepare(app, Store.XBOX, "9NHFVWX1V7QJ") {
                callbackOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                callbackRan.set(true)
                callback.countDown()
            }

            assertFalse("ready callback must not run inline on the caller", callbackRan.get())
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (callback.count > 0 && System.nanoTime() < deadline) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                Thread.sleep(10)
            }
            assertTrue("ready callback was not posted", callback.await(1, TimeUnit.SECONDS))
            assertTrue("ready callback must run on the main looper", callbackOnMain.get())
        } finally {
            installed.deleteRecursively()
        }
    }
}
