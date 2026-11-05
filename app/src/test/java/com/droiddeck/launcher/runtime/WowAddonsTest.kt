package com.droiddeck.launcher.runtime

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WowAddonsTest {
    @get:Rule val temp = TemporaryFolder()

    private fun zip(vararg files: Pair<String, String>): File {
        val file = temp.newFile("addon-${System.nanoTime()}.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            for ((name, text) in files) {
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    private fun target(): WowAddons.Target {
        val root = temp.newFolder("World of Warcraft")
        File(root, "_retail_").mkdirs()
        return WowAddons.targets(BattleNet.title("wow")!!, root).single()
    }

    private fun names(vararg files: String) = WowAddons.addons(files.toList()).map { it.name to it.root }

    @Test fun plainFoldersKeepTheirNames() {
        assertEquals(listOf("Foo" to "Foo/", "Bar" to "Bar/"), names("Foo/Foo.toc", "Foo/a.lua", "Bar/Bar.toc"))
    }

    @Test fun flavourTocsKeepTheFolderName() {
        assertEquals(listOf("Foo" to "Foo/"), names("Foo/Foo_Mainline.toc", "Foo/Foo_Vanilla.toc", "Foo/Foo-Classic.toc"))
    }

    @Test fun anAddonNamedLikeAFlavourStaysWhole() {
        assertEquals(listOf("Foo_Classic" to "Foo_Classic/"), names("Foo_Classic/Foo_Classic.toc"))
    }

    @Test fun sourceWrappersTakeTheTocName() {
        assertEquals(listOf("Foo" to "Foo-main/"), names("Foo-main/Foo.toc", "Foo-main/README.md"))
        assertEquals(listOf("Foo" to "Repo-1.2.3/"), names("Repo-1.2.3/Foo_Mainline.toc", "Repo-1.2.3/Foo_TBC.toc"))
    }

    @Test fun packsAndGameFolderWrappersFindEveryAddon() {
        assertEquals(
            listOf("Foo" to "Interface/AddOns/Foo/", "Bar" to "Interface/AddOns/Bar/"),
            names("Interface/AddOns/Foo/Foo.toc", "Interface/AddOns/Bar/Bar.toc"),
        )
    }

    @Test fun bareFilesGetAFolder() {
        assertEquals(listOf("Foo" to ""), names("Foo.toc", "core.lua"))
    }

    @Test fun nestedTocsBelongToTheirAddon() {
        assertEquals(listOf("Foo" to "Foo/"), names("Foo/Foo.toc", "Foo/Libs/Lib/Lib.toc"))
    }

    @Test fun anAddonInTheFolderOfASimilarNameIsNotNested() {
        assertEquals(listOf("Foo" to "Foo/", "FooOptions" to "FooOptions/"), names("Foo/Foo.toc", "FooOptions/FooOptions.toc"))
    }

    @Test fun importsIntoInterfaceAddOns() {
        val target = target()
        val zip = zip("Foo-main/Foo.toc" to "toc", "Foo-main/sub/a.lua" to "lua", "__MACOSX/Foo-main/._Foo.toc" to "x")
        assertEquals(listOf("Foo"), WowAddons.import(zip, target))
        assertEquals(File(target.addOns.parentFile, "AddOns"), target.addOns)
        assertEquals("lua", File(target.addOns, "Foo/sub/a.lua").readText())
        assertEquals(1, WowAddons.installed(target))
        assertEquals(listOf("AddOns"), target.addOns.parentFile!!.list()!!.toList())
    }

    @Test fun reimportReplacesTheFolderIgnoringCase() {
        val target = target()
        File(target.addOns, "foo").mkdirs()
        File(target.addOns, "foo/stale.lua").writeText("old")
        File(target.addOns, "Keep").mkdirs()
        WowAddons.import(zip("Foo/Foo.toc" to "new"), target)
        assertTrue(File(target.addOns, "Foo/Foo.toc").isFile)
        assertFalse(File(target.addOns, "foo").exists())
        assertTrue(File(target.addOns, "Keep").isDirectory)
    }

    @Test fun replacingALinkLeavesItsTargetAlone() {
        val target = target()
        val outside = temp.newFolder("outside")
        File(outside, "keep.lua").writeText("keep")
        target.addOns.mkdirs()
        Files.createSymbolicLink(File(target.addOns, "Foo").toPath(), outside.toPath())
        WowAddons.import(zip("Foo/Foo.toc" to "new"), target)
        assertEquals("keep", File(outside, "keep.lua").readText())
        assertFalse(Files.isSymbolicLink(File(target.addOns, "Foo").toPath()))
    }

    @Test fun aFailedReplacementRestoresEarlierAddons() {
        val base = temp.newFolder("swap")
        val addOns = File(base, "AddOns").apply { mkdirs() }
        File(addOns, "Foo").mkdirs()
        File(addOns, "Foo/old.lua").writeText("old")
        File(addOns, "Bar").mkdirs()
        File(addOns, "Bar/old.lua").writeText("old")
        val fresh = File(base, "fresh/Foo").apply { mkdirs() }
        File(fresh, "new.lua").writeText("new")
        val old = File(base, "old").apply { mkdirs() }
        assertThrows(IOException::class.java) {
            WowAddons.swap(listOf(fresh to "Foo", File(base, "fresh/Missing") to "Bar"), addOns, old)
        }
        assertEquals("old", File(addOns, "Foo/old.lua").readText())
        assertEquals("old", File(addOns, "Bar/old.lua").readText())
        assertFalse(File(addOns, "Foo/new.lua").exists())
    }

    @Test fun zipsWithoutAnAddonChangeNothing() {
        val target = target()
        assertEquals(emptyList<String>(), WowAddons.import(zip("readme.txt" to "x"), target))
        assertFalse(target.addOns.exists())
    }

    @Test fun linkEntriesDoNotCountAsAddons() {
        val target = target()
        val file = temp.newFile("links.zip")
        ZipArchiveOutputStream(file).use { out ->
            val link = ZipArchiveEntry("Foo/Foo.toc").apply { unixMode = 0xA1FF }
            out.putArchiveEntry(link)
            out.write("/etc/passwd".toByteArray())
            out.closeArchiveEntry()
        }
        assertEquals(emptyList<String>(), WowAddons.import(file, target))
        assertFalse(target.addOns.exists())
    }

    @Test fun escapingEntriesAreNeverWritten() {
        val target = target()
        WowAddons.import(zip("Foo/Foo.toc" to "a", "../../evil.lua" to "x", "Foo/../../evil2.lua" to "x"), target)
        val written = target.addOns.parentFile!!.parentFile!!.walkTopDown().map { it.name }.toList()
        assertFalse("evil.lua" in written)
        assertFalse("evil2.lua" in written)
        assertTrue("Foo.toc" in written)
    }

    @Test fun usesTheInstalledFlavourFolders() {
        val root = temp.newFolder("wow")
        listOf("_classic_", "_classic_era_", "_anniversary_", "_retail_").forEach { File(root, it).mkdirs() }
        val classic = BattleNet.title("wow_classic")!!
        assertEquals(setOf("_classic_", "_classic_era_", "_anniversary_"), WowAddons.targets(classic, root).map { it.flavor }.toSet())
        assertEquals(listOf("_retail_"), WowAddons.targets(BattleNet.title("wow")!!, File(root, "_retail_")).map { it.flavor })
    }

    @Test fun reusesAnExistingFolderSpelling() {
        val root = temp.newFolder("wow2")
        File(root, "_forever_/interface/addons").mkdirs()
        val target = WowAddons.targets(BattleNet.title("wow_forever")!!, root).single()
        assertEquals(File(root, "_forever_/interface/addons"), target.addOns)
    }

    @Test fun otherGamesHaveNoTargets() {
        val root = temp.newFolder("d4")
        File(root, "_retail_").mkdirs()
        assertEquals(emptyList<String>(), WowAddons.targets(BattleNet.title("fenris")!!, root).map { it.flavor })
    }
}
