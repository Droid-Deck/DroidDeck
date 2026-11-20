package com.droiddeck.launcher.storage

import com.droiddeck.launcher.stores.InstalledStoreGame
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreGameSidecar
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class KeptPrefixRemovalTest {
    private val compat = File("/rootfs/root/.local/share/Steam/steamapps/compatdata")

    private fun kept(id: String, owner: PrefixOwner) = KeptPrefix(File(compat, id), owner, null, 1L shl 30, "Internal", false)

    private fun report(kept: List<KeptPrefix>, storeFolders: List<File> = emptyList()) = StorageReport(
        0, 0, 0, emptyMap(), 0, 0, 0, 0, emptyList(), emptyList(), emptyList(), emptyList(), 0, emptyList(), emptyList(), 0,
        kept, true, storeFolders.map { StoreGameUsage(InstalledStoreGame(StoreGameSidecar(Store.BATTLENET, "pro", "Game", "Game.exe"), it), -1, false) },
    )

    @Test fun launcherAndUnknownPrefixesCanBeRemoved() {
        val launcher = kept("2929927072", PrefixOwner.LAUNCHER)
        val unknown = kept("1234", PrefixOwner.UNKNOWN)
        val r = report(listOf(launcher, unknown))
        assertTrue(StorageState.keptRemovable(r, launcher))
        assertTrue(StorageState.keptRemovable(r, unknown))
        assertTrue(StorageState.keptRemovable(r, kept("0", PrefixOwner.SHARED)))
    }

    @Test fun aLauncherPrefixHoldingAnInstalledGameStays() {
        val bnet = kept("2611429436", PrefixOwner.LAUNCHER)
        val inside = File(bnet.dir, "pfx/drive_c/Program Files (x86)/Overwatch")
        assertFalse(StorageState.keptRemovable(report(listOf(bnet), listOf(inside)), bnet))
        val elsewhere = File(compat, "2611429436-other/Overwatch")
        assertTrue(StorageState.keptRemovable(report(listOf(bnet), listOf(elsewhere)), bnet))
    }
}
