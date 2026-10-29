package com.droiddeck.launcher.session

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UsbLinkTest {
    private lateinit var sys: File

    @Before fun setUp() { sys = Files.createTempDirectory("usblink").toFile() }

    @After fun tearDown() {
        Files.walk(sys.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private val mounts = """
        /dev/block/dm-5 /data ext4 rw 0 0
        /dev/fuse /storage/8C5A-1F23 fuse rw 0 0
        /dev/block/vold/public:8,97 /mnt/media_rw/8C5A-1F23 fuseblk rw 0 0
    """.trimIndent()

    private fun drive(deviceSpeed: String, otherHub: String) {
        val controller = File(sys, "devices/platform/xhci")
        val hub = File(controller, "usb1").apply { mkdirs(); File(this, "speed").writeText("480\n"); File(this, "idVendor").writeText("1d6b\n") }
        File(controller, "usb2").apply { mkdirs(); File(this, "speed").writeText("$otherHub\n"); File(this, "idVendor").writeText("1d6b\n") }
        val device = File(hub, "1-1").apply { mkdirs() }
        File(device, "speed").writeText("$deviceSpeed\n")
        File(device, "idVendor").writeText("04e8\n")
        File(device, "busnum").writeText("1\n")
        File(device, "devnum").writeText("7\n")
        File(device, "manufacturer").writeText("Samsung\n")
        File(device, "product").writeText("PSSD T7\n")
        val part = File(device, "1-1:1.0/host1/target1:0:0/1:0:0:0/block/sdg/sdg1").apply { mkdirs() }
        File(sys, "dev/block").mkdirs()
        Files.createSymbolicLink(File(sys, "dev/block/8:97").toPath(), part.toPath())
        File(sys, "bus/usb/devices").mkdirs()
        Files.createSymbolicLink(File(sys, "bus/usb/devices/usb1").toPath(), hub.toPath())
    }

    @Test fun findsTheUsbSpeedOfADriveBehindItsMount() {
        drive("480", "10000")
        val link = UsbLink.forPath("/storage/8C5A-1F23/Games", mounts, sys)!!
        assertEquals("8:97", link.device)
        assertEquals(480.0, link.mbps, 0.0)
        assertEquals("Samsung PSSD T7", link.name)
        assertEquals(10000.0, link.portMbps!!, 0.0)
        assertEquals("1-7", link.plug)
        assertEquals("3/1-7/8:97@480.0", UsbLink.dismissKey(3, link))
        assertFalse(UsbLink.SlowDrive("k", link.name, link.mbps, link.portMbps).portLimited)
    }

    @Test fun aPortWithoutSuperSpeedIsReportedAsTheLimit() {
        drive("480", "480")
        val link = UsbLink.forPath("/storage/8C5A-1F23", mounts, sys)!!
        assertTrue(UsbLink.SlowDrive("k", link.name, link.mbps, link.portMbps).portLimited)
    }

    @Test fun anUnknownPortSpeedIsNotReportedAsTheLimit() {
        drive("480", "10000")
        Files.delete(File(sys, "bus/usb/devices/usb1").toPath())
        val link = UsbLink.forPath("/storage/8C5A-1F23", mounts, sys)!!
        assertNull(link.portMbps)
        assertFalse(UsbLink.SlowDrive("k", link.name, link.mbps, link.portMbps).portLimited)
    }

    @Test fun internalStorageAndUnknownMountsHaveNoLink() {
        drive("480", "10000")
        assertNull(UsbLink.forPath("/storage/emulated/0/Games", mounts, sys))
        assertNull(UsbLink.forPath("/storage/ABCD-0000", mounts, sys))
    }

    @Test fun mountsPickTheLongestMatchingMountPoint() {
        assertEquals("8:97", UsbLink.mountDevice("/mnt/media_rw/8C5A-1F23/Games/PC", mounts))
        assertNull(UsbLink.mountDevice("/mnt/media_rw/8C5A-1F230", mounts))
        assertEquals("name:sda1", UsbLink.mountDevice("/mnt/usb/a", "/dev/block/sda1 /mnt/usb vfat rw 0 0"))
    }

    @Test fun rawMountPathsAreTreatedAsTheirStorageVolume() {
        assertEquals("/storage/8C5A-1F23/Games", UsbLink.storagePath("/mnt/media_rw/8C5A-1F23/Games"))
        assertEquals("/storage/emulated/0/Games", UsbLink.storagePath("/storage/emulated/0/Games"))
        assertTrue(UsbLink.external(UsbLink.storagePath("/mnt/media_rw/8C5A-1F23/Games")))
    }

    @Test fun generationsFollowTheLinkSpeed() {
        assertEquals("USB 1.1", UsbLink.generation(12.0))
        assertEquals("USB 2.0", UsbLink.generation(480.0))
        assertEquals("USB 3.2 Gen 1", UsbLink.generation(5000.0))
        assertEquals("USB 3.2 Gen 2", UsbLink.generation(10000.0))
        assertEquals(42, UsbLink.megabytesPerSecond(480.0))
    }
}
