package com.droiddeck.launcher.session

import org.junit.Assert.*
import org.junit.Test

class UsbRootMountTest {
    @Test fun parsesBlkidLines() {
        val exfat = UsbRootMount.parseBlkid("/dev/block/vold/public:8,1: LABEL=\"Games\" UUID=\"1234-ABCD\" TYPE=\"exfat\" ")!!
        assertEquals("/dev/block/vold/public:8,1", exfat.device)
        assertEquals("public:8,1", exfat.volume)
        assertEquals("1234-ABCD", exfat.uuid)
        assertEquals("Games", exfat.label)
        assertEquals(0L, exfat.size)
        assertTrue(exfat.fat)

        val ext4 = UsbRootMount.parseBlkid("/dev/block/vold/public:8,1: UUID=\"00000000-0000-0000-0000-000000000001\" TYPE=\"ext4\" SIZE=\"64000000000\"")!!
        assertEquals("", ext4.label)
        assertEquals(64000000000L, ext4.size)
        assertFalse(ext4.fat)
    }

    @Test fun rejectsUnsupportedAndUnsafe() {
        assertNull(UsbRootMount.parseBlkid("/dev/block/vold/public:8,1: UUID=\"x\" TYPE=\"ntfs\""))
        assertNull(UsbRootMount.parseBlkid("/dev/block/sda1: UUID=\"x\" TYPE=\"ext4\""))
        assertNull(UsbRootMount.parseBlkid("/dev/block/vold/public:8,1;reboot: UUID=\"x\" TYPE=\"ext4\""))
        assertNull(UsbRootMount.parseBlkid("/dev/block/vold/public:8,1: TYPE=\"ext4\""))
    }
}
