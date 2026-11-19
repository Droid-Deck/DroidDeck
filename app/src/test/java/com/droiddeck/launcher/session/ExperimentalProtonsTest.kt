package com.droiddeck.launcher.session

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import android.app.Activity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before
import org.robolectric.Robolectric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ExperimentalProtonsTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var activity: Activity

    @Before
    fun setup() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    }

    @Test
    fun releaseRowsOnlyUsePublishedPrereleaseTarAssets() {
        val tool = ProtonExtras.tools.first { it.id == ProtonExtras.OWN }
        val releases = JSONArray("""
            [{"tag_name":"old","prerelease":false,"assets":[{"name":"aarch64.tar.gz","browser_download_url":"https://example/old"}]},
             {"tag_name":"pre","prerelease":true,"assets":[{"name":"aarch64.tar.gz","browser_download_url":"https://example/pre"}]},
             {"tag_name":"draft","draft":true,"prerelease":true,"assets":[{"name":"aarch64.tar.gz","browser_download_url":"https://example/draft"}]},
             {"tag_name":"no-tar","prerelease":true,"assets":[{"name":"checksums.sha512sum","browser_download_url":"https://example/sum"}]}]
        """.trimIndent())
        assertEquals(listOf("pre"), ProtonExtras.assets(tool, releases, true).map { it.tag })
    }

    @Test
    fun markerRoundTripKeepsReleaseTag() {
        val dir = File(com.droiddeck.launcher.runtime.LinuxRuntime.rootDir(activity), "root/.local/share/Steam/compatibilitytools.d/Experimental-pre").apply { mkdirs() }
        File(dir, ".droiddeck-experimental.json").writeText(JSONObject().put("label", "Experimental-pre").put("source", "pre").put("releaseTag", "pre").toString())
        assertEquals("pre", ExperimentalProtons.installed(activity).single().releaseTag)
    }

    @Test
    fun missingReleaseTagsAreAutoRemovedButFileBuildsAreNot() {
        assertTrue(ExperimentalProtons.shouldRemove("gone", setOf("kept")))
        assertFalse(ExperimentalProtons.shouldRemove("kept", setOf("kept")))
        assertFalse(ExperimentalProtons.shouldRemove(null, emptySet()))
    }

    @Test
    fun archiveTypeUsesMagicBytes() {
        val zip = temp.newFile("wrong-name.bin").apply { writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) }
        val seven = temp.newFile("seven.data").apply { writeBytes(byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)) }
        val tar = temp.newFile("tar.data").apply { RandomAccessFile(this, "rw").use { it.seek(257); it.write("ustar".toByteArray()) } }
        assertEquals(ExperimentalProtons.ArchiveType.ZIP, ExperimentalProtons.archiveType(zip))
        assertEquals(ExperimentalProtons.ArchiveType.SEVEN_Z, ExperimentalProtons.archiveType(seven))
        assertEquals(ExperimentalProtons.ArchiveType.TAR, ExperimentalProtons.archiveType(tar))
    }

    @Test
    fun namesNeverCollideWithReleasedBuilds() {
        val name = ExperimentalProtons.dirName("DroidDeck-Proton-11-12.8-aarch64", "pr34-build")
        assertEquals("Experimental-DroidDeck-Proton-11-12.8-aarch64-pr34-build", name)
        assertFalse(name.startsWith("DroidDeck-Proton"))
        assertEquals("Experimental-DroidDeck-Proton-11-12.8-aarch64",
            ExperimentalProtons.dirName("DroidDeck-Proton-11-12.8-aarch64", "DroidDeck-Proton-11-12.8-aarch64"))
    }

    @Test
    fun buildTreeSkipsLinksOutOfTheArchive() {
        val outside = protonTree(temp.newFolder("installed"))
        val unpacked = temp.newFolder("unpacked")
        Files.createSymbolicLink(File(unpacked, "a-link").toPath(), outside.toPath())
        assertNull(ExperimentalProtons.buildTree(unpacked))
        val real = protonTree(File(unpacked, "b-build"))
        assertEquals(real.canonicalFile, ExperimentalProtons.buildTree(unpacked)?.canonicalFile)
    }

    @Test
    fun buildTreeNeedsWhatTheCatalogNeeds() {
        val unpacked = temp.newFolder("unpacked")
        val tree = File(unpacked, "build").apply { mkdirs(); File(this, "toolmanifest.vdf").writeText("x") }
        assertNull(ExperimentalProtons.buildTree(unpacked))
        File(tree, "proton").writeText("x")
        assertNull(ExperimentalProtons.buildTree(unpacked))
        File(tree, "files/lib/wine").mkdirs()
        assertEquals(tree.canonicalFile, ExperimentalProtons.buildTree(unpacked)?.canonicalFile)
    }

    private fun protonTree(dir: File): File = dir.apply {
        File(this, "files/lib/wine").mkdirs()
        File(this, "toolmanifest.vdf").writeText("x")
        File(this, "proton").writeText("x")
    }

    @Test
    fun ownFileReplacesALinkWithoutTouchingItsTarget() {
        val victim = temp.newFile("compatibilitytool.vdf").apply { writeText("released") }
        val tree = temp.newFolder("tree")
        Files.createSymbolicLink(File(tree, "compatibilitytool.vdf").toPath(), victim.toPath())
        ExperimentalProtons.ownFile(tree, "compatibilitytool.vdf").writeText("experimental")
        assertEquals("released", victim.readText())
        assertFalse(Files.isSymbolicLink(File(tree, "compatibilitytool.vdf").toPath()))
    }

    @Test
    fun deleteTreeDoesNotFollowLinks() {
        val kept = temp.newFolder("kept").apply { File(this, "file").writeText("x") }
        val build = temp.newFolder("build")
        Files.createSymbolicLink(File(build, "lib").toPath(), kept.toPath())
        assertTrue(ExperimentalProtons.deleteTree(build))
        assertFalse(build.exists())
        assertTrue(File(kept, "file").isFile)
    }

    @Test
    fun checksumFileWithoutADigestIsRejected() {
        assertNull(ExperimentalProtons.checksumIn("not a checksum\n"))
        val digest = "a".repeat(128)
        assertEquals(digest, ExperimentalProtons.checksumIn("$digest  DroidDeck-Proton.tar.gz\n"))
    }

    @Test
    fun checksumLookupDoesNotSelectTheTarball() {
        assertNull(ExperimentalProtons.checksumEntry(listOf("Proton-sha512.tar.gz"), "Proton-sha512.tar.gz"))
        assertEquals("Proton-sha512.tar.gz.sha512sum", ExperimentalProtons.checksumEntry(
            listOf("Proton-sha512.tar.gz", "Proton-sha512.tar.gz.sha512sum"), "Proton-sha512.tar.gz"))
        assertNull(ExperimentalProtons.checksumEntry(listOf("Proton.tar.gz"), "Proton.tar.gz"))
        assertEquals("Proton.tar.gz.sha512sum", ExperimentalProtons.checksumEntry(
            listOf("Proton-debug.tar.gz.sha512sum", "Proton.tar.gz.sha512sum"), "Proton.tar.gz"))
        assertEquals("Proton.sha512sum", ExperimentalProtons.checksumEntry(listOf("Proton.sha512sum"), "Proton.tar.gz"))
        assertNull(ExperimentalProtons.checksumEntry(listOf("Proton-debug.tar.gz.sha512sum"), "Proton.tar.gz"))
    }

    private fun build(dir: File, marker: String): File = dir.apply {
        mkdirs()
        File(this, "marker").writeText(marker)
    }

    @Test
    fun failedPlacementRestoresTheExistingBuild() {
        val tools = temp.newFolder("tools")
        build(File(tools, "Experimental-A"), "old")
        assertFalse(ExperimentalProtons.placeTree(File(tools, "missing"), tools, "Experimental-A") { true })
        assertEquals("old", File(tools, "Experimental-A/marker").readText())
        assertFalse(ExperimentalProtons.placeTree(build(File(tools, "staged"), "new"), tools, "Experimental-A") { false })
        assertEquals("old", File(tools, "Experimental-A/marker").readText())
        assertTrue(tools.walkTopDown().none { it.isFile && it.readText() == "new" })
    }

    @Test
    fun aReplacedBuildIsForgottenWhileNeitherBuildIsInstalled() {
        val tools = temp.newFolder("tools")
        build(File(tools, "Experimental-A"), "old")
        val forgotten = ArrayList<String>()
        assertTrue(ExperimentalProtons.placeTree(build(File(tools, "staged"), "new"), tools, "Experimental-A") {
            assertFalse(File(tools, it).exists())
            forgotten.add(it)
        })
        assertEquals(listOf("Experimental-A"), forgotten)
        assertEquals("new", File(tools, "Experimental-A/marker").readText())
        assertTrue(ExperimentalProtons.placeTree(build(File(tools, "staged"), "first"), tools, "Experimental-B") { forgotten.add(it) })
        assertEquals(listOf("Experimental-A"), forgotten)
        assertEquals(listOf("Experimental-A", "Experimental-B"), tools.walkTopDown().filter { it.name == "marker" }.map { it.parentFile.name }.sorted().toList())
    }

    @Test
    fun anInterruptedReplacementIsFinishedWithItsOldStateForgotten() {
        val tools = temp.newFolder("tools")
        build(File(tools, "Experimental-A"), "old")
        build(File(tools, ".droiddeck-experimental-new/Experimental-A"), "new")
        build(File(tools, ".droiddeck-experimental-old/Experimental-B"), "old")
        build(File(tools, ".droiddeck-experimental-new/Experimental-B"), "new")
        build(File(tools, ".droiddeck-experimental-old/Experimental-C"), "old")
        build(File(tools, ".droiddeck-experimental-old/Experimental-D"), "old")
        build(File(tools, "Experimental-D"), "new")
        val forgotten = ArrayList<String>()
        ExperimentalProtons.restoreInterrupted(tools) { forgotten.add(it) }
        assertEquals(listOf("Experimental-A", "Experimental-B"), forgotten.sorted())
        assertEquals(listOf("new", "new", "old", "new"), listOf("A", "B", "C", "D").map { File(tools, "Experimental-$it/marker").readText() })
        assertTrue(File(tools, ".droiddeck-experimental-old").listFiles().isNullOrEmpty())
        assertTrue(File(tools, ".droiddeck-experimental-new").listFiles().isNullOrEmpty())
    }

    @Test
    fun anInterruptedReplacementKeepsTheOldBuildWhenItsStateCannotBeForgotten() {
        val tools = temp.newFolder("tools")
        build(File(tools, ".droiddeck-experimental-old/Experimental-A"), "old")
        build(File(tools, ".droiddeck-experimental-new/Experimental-A"), "new")
        ExperimentalProtons.restoreInterrupted(tools) { false }
        assertEquals("old", File(tools, "Experimental-A/marker").readText())
        assertTrue(File(tools, ".droiddeck-experimental-new").listFiles().isNullOrEmpty())
    }
}
