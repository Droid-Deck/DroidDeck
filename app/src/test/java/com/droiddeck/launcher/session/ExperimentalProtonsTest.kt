package com.droiddeck.launcher.session

import java.io.File
import java.nio.file.Files
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

    @Test
    fun failedPlacementRestoresTheExistingBuild() {
        val staging = temp.newFolder("staging")
        val backups = temp.newFolder("backups")
        val final = temp.newFolder("final")
        File(final, "marker").writeText("old")
        assertFalse(ExperimentalProtons.placeTree(File(staging, "missing"), final, backups))
        assertEquals("old", File(final, "marker").readText())
        assertFalse(File(backups, "final").exists())
    }

    @Test
    fun anInterruptedReplacementIsRestoredAndAPlacedBuildIsKept() {
        val tools = temp.newFolder("tools")
        val backups = File(tools, "backups")
        File(backups, "Experimental-A").mkdirs()
        File(backups, "Experimental-A/marker").writeText("old")
        File(backups, "Experimental-B").mkdirs()
        File(tools, "Experimental-B").mkdirs()
        File(tools, "Experimental-B/marker").writeText("new")
        ExperimentalProtons.restoreInterrupted(tools, backups)
        assertEquals("old", File(tools, "Experimental-A/marker").readText())
        assertEquals("new", File(tools, "Experimental-B/marker").readText())
        assertFalse(File(backups, "Experimental-B").exists())
    }
}
