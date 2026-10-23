package com.droiddeck.launcher.usbdrive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UsbDriveJsonTest {
    private fun bytes(json: String) = json.toByteArray(Charsets.UTF_8)

    @Test fun disk() {
        val disk = UsbDriveJson.disk(bytes(
            """{"vendor":"JMicron","product":"Tech","blockSize":512,"blocks":1953525168,"partitions":[""" +
                """{"index":2,"start":32802,"blocks":1000000,"fs":"ntfs","label":"Basic data partition"},""" +
                """{"index":3,"start":1032802,"blocks":5000,"fs":"exfat","label":""}]}""",
        ))
        assertEquals("JMicron Tech", disk.name)
        assertEquals(1953525168L * 512, disk.sizeBytes)
        assertEquals(2, disk.partitions.size)
        assertTrue(disk.partitions[0].isNtfs)
        assertEquals(1000000L * 512, disk.bytesOf(disk.partitions[0]))
        assertFalse(disk.partitions[1].isNtfs)
        assertEquals("", disk.partitions[1].label)
    }

    @Test fun disk_without_inquiry_strings() {
        val disk = UsbDriveJson.disk(bytes("""{"vendor":"","product":"","blockSize":4096,"blocks":10,"partitions":[]}"""))
        assertEquals("", disk.name)
        assertEquals(40960L, disk.sizeBytes)
    }

    @Test fun volume() {
        val info = UsbDriveJson.volume(bytes("""{"label":"Games","size":1000204886016,"free":1234,"dirty":true}"""))
        assertEquals(NtfsVolumeInfo("Games", 1000204886016L, 1234L, dirty = true), info)
    }

    @Test fun entries_keep_names_outside_the_basic_plane() {
        val entries = UsbDriveJson.entries(bytes(
            """[{"name":"Saves 🎮","dir":true,"hidden":false,"size":0,"mtime":1700000000000},""" +
                """{"name":"quote \" and \\ slash","dir":false,"hidden":true,"size":42,"mtime":0}]""",
        ))
        assertEquals("Saves 🎮", entries[0].name)
        assertTrue(entries[0].isDirectory)
        assertEquals(1700000000000L, entries[0].modified)
        assertEquals("quote \" and \\ slash", entries[1].name)
        assertTrue(entries[1].hidden)
        assertEquals(42L, entries[1].size)
    }

    @Test fun paths() {
        assertEquals("/Games", NtfsPaths.child(NtfsPaths.ROOT, "Games"))
        assertEquals("/Games/Foo", NtfsPaths.child("/Games", "Foo"))
        assertEquals("/Games", NtfsPaths.parent("/Games/Foo"))
        assertEquals(NtfsPaths.ROOT, NtfsPaths.parent("/Games"))
        assertNull(NtfsPaths.parent(NtfsPaths.ROOT))
        assertEquals("Foo", NtfsPaths.name("/Games/Foo"))
        assertEquals(listOf("Games", "Foo"), NtfsPaths.segments("/Games/Foo"))
    }

    @Test fun directories_sort_first_then_names_ignoring_case() {
        val sorted = listOf(
            NtfsEntry("b.txt", false, false, 1, 0),
            NtfsEntry("Zeta", true, false, 0, 0),
            NtfsEntry("A.txt", false, false, 1, 0),
            NtfsEntry("alpha", true, false, 0, 0),
        ).sortedWith(ntfsEntryOrder).map { it.name }
        assertEquals(listOf("alpha", "Zeta", "A.txt", "b.txt"), sorted)
    }
}
