package com.droiddeck.launcher.files

import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.StorageVolumeBuilder
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28, 29, 31, 33], manifest = Config.NONE)
class StorageVolumeCompatibilityTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(StorageManager::class.java)

    @Before fun resetVolumes() { shadowOf(manager).resetStorageVolumeList() }

    private fun addVolume(uuid: String?, state: String): File {
        val directory = File(context.cacheDir, "usb-volume").apply { mkdirs() }
        val volume = StorageVolumeBuilder("usb", directory, "USB drive", Process.myUserHandle(), state)
            .setIsPrimary(false).setIsRemovable(true).setFsUuid(uuid).build()
        shadowOf(manager).addStorageVolume(volume)
        return directory
    }

    @Test fun mountedVolumeUsesPublicDirectoryOrTheUuidFallback() {
        val directory = addVolume("A1B2-C3D4", Environment.MEDIA_MOUNTED)
        val root = StorageRoots.list(context).single { it.label == "USB drive" }
        assertTrue(root.removable)
        assertEquals(if (Build.VERSION.SDK_INT >= 30) directory else File("/storage/A1B2-C3D4"), root.dir)
        assertEquals(Build.VERSION.SDK_INT >= 30, root.readable)
    }

    @Test fun aVolumeWithoutUuidStillAppears() {
        val directory = addVolume(null, Environment.MEDIA_MOUNTED)
        val root = StorageRoots.list(context).single { it.label == "USB drive" }
        assertTrue(root.removable)
        assertEquals(if (Build.VERSION.SDK_INT >= 30) directory else File("/storage/USB-drive"), root.dir)
    }

    @Test fun anUnmountedVolumeRemainsVisibleWithoutClaimingItIsReadable() {
        addVolume("A1B2-C3D4", Environment.MEDIA_UNMOUNTED)
        val root = StorageRoots.list(context).single { it.label == "USB drive" }
        assertTrue(root.removable)
        assertFalse(root.readable)
    }
}
