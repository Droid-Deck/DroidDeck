package com.droiddeck.launcher.files

import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.StorageVolumeBuilder
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class StorageRootsVolumeTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun addUsbVolume(): File {
        val mount = temporary.newFolder("usbstick")
        val context = RuntimeEnvironment.getApplication()
        val volume = StorageVolumeBuilder("public:8,1", mount, "USB drive", Process.myUserHandle(), Environment.MEDIA_MOUNTED)
            .setIsPrimary(false)
            .setIsEmulated(false)
            .setIsRemovable(true)
            .build()
        shadowOf(context.getSystemService(StorageManager::class.java)).addStorageVolume(volume)
        return mount
    }

    @Config(sdk = [29])
    @Test fun volumesWithoutDirectoryApiAreListedOnAndroid10() {
        addUsbVolume()

        val usb = StorageRoots.list(RuntimeEnvironment.getApplication()).single { it.label == "USB drive" }

        assertTrue(usb.removable)
    }

    @Config(sdk = [30])
    @Test fun volumeDirectoryIsStillUsedOnAndroid11() {
        val mount = addUsbVolume()

        val usb = StorageRoots.list(RuntimeEnvironment.getApplication()).single { it.label == "USB drive" }

        assertTrue(usb.removable)
        assertTrue(usb.readable)
        assertEquals(mount.absoluteFile, usb.dir.absoluteFile)
    }
}
