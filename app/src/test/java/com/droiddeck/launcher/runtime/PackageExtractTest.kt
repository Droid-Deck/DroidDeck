package com.droiddeck.launcher.runtime

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PackageExtractTest {
    private val dir = Files.createTempDirectory("package-extract").toFile()
    private val root = File(dir, "linuxfs").apply { mkdirs() }
    private val context = RuntimeEnvironment.getApplication()

    @After fun tearDown() { dir.deleteRecursively() }

    private sealed class Item(val name: String) {
        class Link(name: String, val target: String) : Item(name)
        class Data(name: String, val text: String) : Item(name)
    }

    private fun archive(vararg items: Item): File {
        val tar = ByteArrayOutputStream()
        TarArchiveOutputStream(tar).use { out ->
            for (item in items) {
                when (item) {
                    is Item.Link -> out.putArchiveEntry(TarArchiveEntry(item.name, TarArchiveEntry.LF_SYMLINK).apply { linkName = item.target })
                    is Item.Data -> {
                        val bytes = item.text.toByteArray()
                        out.putArchiveEntry(TarArchiveEntry(item.name).apply { size = bytes.size.toLong(); mode = 0b111_101_101 })
                        out.write(bytes)
                    }
                }
                out.closeArchiveEntry()
            }
        }
        return File(dir, "pkg-${System.nanoTime()}.tar.zst").apply {
            ZstdCompressorOutputStream(outputStream()).use { it.write(tar.toByteArray()) }
        }
    }

    private fun lib(name: String) = File(root, "usr/lib/$name")

    private fun installOld() {
        lib("").mkdirs()
        lib("libvulkan.so.1.4.357").writeText("old")
        Files.createSymbolicLink(lib("libvulkan.so.1").toPath(), Paths.get("libvulkan.so.1.4.357"))
    }

    @Test fun linksListedBeforeTheirTargetsResolveAfterExtraction() {
        installOld()
        val pkg = archive(
            Item.Link("usr/lib/libvulkan.so.1", "libvulkan.so.1.4.363"),
            Item.Data("usr/lib/libvulkan.so.1.4.363", "new"),
        )

        assertTrue(LinuxRuntimeInstaller.extract(context, pkg, root, null))

        assertEquals("new", lib("libvulkan.so.1").readText())
        assertEquals(emptyList<String>(), RuntimeHealth.brokenLibraryLinks(root))
        assertFalse(lib("").list()!!.any { it.startsWith(".dd-part-") })
    }

    @Test fun anExtractionStoppedBetweenALinkAndItsTargetLeavesTheOldLinkWorking() {
        installOld()
        val pkg = archive(
            Item.Link("usr/lib/libvulkan.so.1", "libvulkan.so.1.4.363"),
            Item.Data("usr/lib/libharfbuzz.so.0", "harfbuzz"),
            Item.Data("usr/lib/libharfbuzz.so.0/cannot-be-written", "stops here"),
            Item.Data("usr/lib/libvulkan.so.1.4.363", "new"),
        )

        assertFalse(LinuxRuntimeInstaller.extract(context, pkg, root, null))

        assertEquals("old", lib("libvulkan.so.1").readText())
        assertFalse(lib("libvulkan.so.1.4.363").exists())
        assertEquals(emptyList<String>(), RuntimeHealth.brokenLibraryLinks(root))
    }

    @Test fun aFileReplacesAnExistingLinkInsteadOfWritingThroughIt() {
        installOld()
        val pkg = archive(Item.Data("usr/lib/libvulkan.so.1", "now a file"))

        assertTrue(LinuxRuntimeInstaller.extract(context, pkg, root, null))

        assertFalse(Files.isSymbolicLink(lib("libvulkan.so.1").toPath()))
        assertEquals("now a file", lib("libvulkan.so.1").readText())
        assertEquals("old", lib("libvulkan.so.1.4.357").readText())
        assertTrue(lib("libvulkan.so.1").canExecute())
    }

    @Test fun theLastEntryForAPathWins() {
        lib("").mkdirs()
        lib("target").writeText("t")
        val pkg = archive(
            Item.Link("usr/lib/libdup.so", "target"),
            Item.Data("usr/lib/libdup.so", "file"),
            Item.Data("usr/lib/libother.so", "file"),
            Item.Link("usr/lib/libother.so", "target"),
        )

        assertTrue(LinuxRuntimeInstaller.extract(context, pkg, root, null))

        assertFalse(Files.isSymbolicLink(lib("libdup.so").toPath()))
        assertEquals("file", lib("libdup.so").readText())
        assertTrue(Files.isSymbolicLink(lib("libother.so").toPath()))
    }
}
