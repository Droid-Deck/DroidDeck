package com.droiddeck.launcher.runtime

import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WowAddonsArchiveTest {
    @get:Rule val temp = TemporaryFolder()

    private fun target(): WowAddons.Target {
        val root = temp.newFolder("World of Warcraft")
        File(root, "_retail_").mkdirs()
        return WowAddons.targets(BattleNet.title("wow")!!, root).single()
    }

    private fun sevenZip(vararg files: Pair<String, String>): File {
        val file = temp.newFile("addon-${System.nanoTime()}.bin")
        SevenZOutputFile(file).use { out ->
            for ((name, text) in files) {
                val entry = out.createArchiveEntry(File(name), name)
                out.putArchiveEntry(entry)
                out.write(text.toByteArray())
                out.closeArchiveEntry()
            }
        }
        return file
    }

    private fun unpacked() = File(RuntimeEnvironment.getApplication().cacheDir, "wowaddons").listFiles().orEmpty().filter { it.name.startsWith("unpack-") }

    @Test fun sevenZipArchivesImportLikeZips() {
        val target = target()
        val names = WowAddons.import(sevenZip("Pack/Foo/Foo.toc" to "toc", "Pack/Foo/core.lua" to "lua", "Pack/Bar/Bar.toc" to "toc"), target, context = RuntimeEnvironment.getApplication())
        assertEquals(listOf("Bar", "Foo"), names.sorted())
        assertEquals("lua", File(target.addOns, "Foo/core.lua").readText())
        assertTrue(File(target.addOns, "Bar/Bar.toc").isFile)
        assertTrue(unpacked().isEmpty())
    }

    @Test fun sevenZipArchivesWithoutAnAddonChangeNothing() {
        val target = target()
        assertEquals(emptyList<String>(), WowAddons.import(sevenZip("readme.txt" to "x"), target, context = RuntimeEnvironment.getApplication()))
        assertFalse(target.addOns.exists())
        assertTrue(unpacked().isEmpty())
    }

    @Test fun aFolderWithAnyLinkIsRefusedWhole() {
        val target = target()
        val folder = temp.newFolder("unpacked")
        File(folder, "Foo").mkdirs()
        File(folder, "Foo/Foo.toc").writeText("toc")
        Files.createSymbolicLink(File(folder, "Foo/link.lua").toPath(), File(folder, "Foo/Foo.toc").toPath())
        assertEquals(emptyList<String>(), WowAddons.importFolder(folder, target, null))
        assertFalse(target.addOns.exists())
    }

    @Test fun aFolderImportKeepsEveryFile() {
        val target = target()
        val folder = temp.newFolder("unpacked")
        File(folder, "Foo/Libs").mkdirs()
        File(folder, "Foo/Foo.toc").writeText("toc")
        File(folder, "Foo/Libs/lib.lua").writeText("lib")
        assertEquals(listOf("Foo"), WowAddons.importFolder(folder, target, null))
        assertEquals("lib", File(target.addOns, "Foo/Libs/lib.lua").readText())
    }
}
