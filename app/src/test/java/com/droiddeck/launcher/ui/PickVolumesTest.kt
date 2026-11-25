package com.droiddeck.launcher.ui

import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import com.droiddeck.launcher.files.StorageRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
@Config(sdk = [33])
class PickVolumesTest {
    @get:Rule val temporary = TemporaryFolder()

    private val internal = File("/storage/emulated/0")

    private fun root(label: String, path: String, removable: Boolean = true, readable: Boolean = true) =
        StorageRoot(label, File(path), removable, readable)

    @Test fun aCardOnlyReachableThroughMediaRwIsListed() {
        val volumes = pickVolumes(internal, "Internal storage", listOf(
            root("Internal", "/storage/emulated/0", removable = false),
            root("4A1C-830B", "/mnt/media_rw/4A1C-830B"),
        ))

        assertEquals(listOf("/storage/emulated/0", "/mnt/media_rw/4A1C-830B"), volumes.map { it.root.path })
        assertEquals("Internal storage", volumes.first().label)
        assertFalse(volumes.first().removable)
        assertEquals("4A1C-830B", volumes[1].label)
        assertTrue(volumes[1].removable)
    }

    @Test fun aVisibleCardKeepsItsStoragePathAndLabel() {
        val volumes = pickVolumes(internal, "Internal storage", listOf(
            root("Internal", "/storage/emulated/0", removable = false),
            root("SanDisk SD card", "/storage/1234-ABCD"),
            root("USB drive", "/storage/5678-EF01"),
        ))

        assertEquals(listOf("Internal storage", "SanDisk SD card", "USB drive"), volumes.map { it.label })
        assertEquals(File("/storage/1234-ABCD"), volumes[1].root)
    }

    @Test fun unreadableVolumesAndInternalStorageAreNotListedTwice() {
        val volumes = pickVolumes(internal, "Internal storage", listOf(
            root("Internal", "/storage/emulated", removable = false),
            root("Internal", "/storage/emulated/0/Android/data/com.droiddeck.launcher/files", removable = false),
            root("SD card", "/storage/1234-ABCD", readable = false),
            root("USB drive", "/mnt/media_rw/5678-EF01"),
            root("USB drive", "/mnt/media_rw/5678-EF01"),
        ))

        assertEquals(listOf("/storage/emulated/0", "/mnt/media_rw/5678-EF01"), volumes.map { it.root.path })
    }

    @Test fun withNoExternalVolumesOnlyInternalStorageIsListed() {
        val volumes = pickVolumes(internal, "Internal storage", listOf(root("Internal", "/storage/emulated/0", removable = false)))

        assertEquals(listOf(internal), volumes.map { it.root })
    }

    @Test fun aMountedVolumeWithoutAnAppFolderReachesThePicker() {
        val mount = temporary.newFolder("4A1C-830B")
        val context = RuntimeEnvironment.getApplication()
        val volume = StorageVolumeBuilder("public:179,1", mount, "SD card", Process.myUserHandle(), Environment.MEDIA_MOUNTED)
            .setIsPrimary(false)
            .setIsEmulated(false)
            .setIsRemovable(true)
            .build()
        shadowOf(context.getSystemService(StorageManager::class.java)).addStorageVolume(volume)

        val card = pickVolumes(context).single { it.root.absoluteFile == mount.absoluteFile }

        assertEquals("SD card", card.label)
        assertTrue(card.removable)
    }
}
