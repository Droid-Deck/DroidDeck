package com.droiddeck.launcher.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageRootsTest {
    @Test fun usbAndSdMountsAreFound() {
        val mounts = """
            /dev/fuse /mnt/user/0/emulated fuse rw,nosuid,nodev 0 0
            /dev/block/vold/public:8,1 /mnt/media_rw/A1B2-C3D4 vfat rw,dirsync 0 0
            /mnt/media_rw/A1B2-C3D4 /storage/A1B2-C3D4 fuse rw,nosuid,nodev 0 0
            /dev/block/vold/public:179,65 /mnt/media_rw/9C33-1A2B exfat rw,dirsync 0 0
            /mnt/media_rw/9C33-1A2B /storage/9C33-1A2B fuse rw,nosuid,nodev 0 0
            /dev/block/dm-5 /data ext4 rw,seclabel 0 0
        """.trimIndent()

        val points = StorageRoots.parseExternalMounts(mounts).map { it.path }

        assertTrue(points.contains(File("/storage/A1B2-C3D4")))
        assertTrue(points.contains(File("/storage/9C33-1A2B")))
        assertTrue(points.contains(File("/mnt/media_rw/A1B2-C3D4")))
    }

    @Test fun emulatedAndSystemMountsAreIgnored() {
        val mounts = """
            /dev/fuse /mnt/user/0/emulated fuse rw,nosuid,nodev 0 0
            /mnt/media_rw/emulated /storage/emulated sdcardfs rw,nosuid,nodev 0 0
            tmpfs /storage/self tmpfs rw,seclabel 0 0
            overlay /system ext4 ro,seclabel 0 0
            /dev/block/sda1 /data ext4 rw,seclabel 0 0
        """.trimIndent()

        assertEquals(emptyList<File>(), StorageRoots.parseExternalMounts(mounts))
    }

    @Test fun escapedSpacesAndNtfsAreHandled() {
        val mounts = "/dev/block/vold/public:8,1 /mnt/media_rw/My\\040USB ntfs rw,dirsync 0 0\n" +
            "/mnt/media_rw/My\\040USB /storage/My\\040USB fuse rw,nosuid,nodev 0 0\n"

        val points = StorageRoots.parseExternalMounts(mounts).map { it.path }

        assertTrue(points.contains(File("/mnt/media_rw/My USB")))
        assertTrue(points.contains(File("/storage/My USB")))
    }
}
