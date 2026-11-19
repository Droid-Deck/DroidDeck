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
        val outside = temp.newFolder("installed").apply { File(this, "toolmanifest.vdf").writeText("x") }
        val unpacked = temp.newFolder("unpacked")
        Files.createSymbolicLink(File(unpacked, "a-link").toPath(), outside.toPath())
        assertNull(ExperimentalProtons.buildTree(unpacked))
        val real = File(unpacked, "b-build").apply { mkdirs(); File(this, "toolmanifest.vdf").writeText("x") }
        assertEquals(real.canonicalFile, ExperimentalProtons.buildTree(unpacked)?.canonicalFile)
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
    }

    @Test
    fun failedPlacementRestoresTheExistingBuild() {
        val staging = temp.newFolder("staging")
        val final = temp.newFolder("final")
        File(final, "marker").writeText("old")
        assertFalse(ExperimentalProtons.placeTree(File(staging, "missing"), final, staging))
        assertEquals("old", File(final, "marker").readText())
    }
}
