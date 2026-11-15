package com.droiddeck.launcher.runtime

import com.droiddeck.launcher.core.FileUtils
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class L2sLinkTest {
    private val dir = Files.createTempDirectory("l2s").toFile()

    @After fun tearDown() { FileUtils.delete(dir) }

    @Test fun aHardLinkBecomesProotsLinkLayout() {
        val bin = File(dir, "usr/bin").apply { mkdirs() }
        val coreutils = File(bin, "coreutils").apply { writeText("multicall") }
        val tail = File(bin, "tail")
        assertTrue(LinuxRuntimeInstaller.l2sLink(coreutils, tail))
        val intermediate = File(bin, ".l2s.coreutils0001")
        assertEquals(intermediate.toPath(), Files.readSymbolicLink(coreutils.toPath()))
        assertEquals(intermediate.toPath(), Files.readSymbolicLink(tail.toPath()))
        assertEquals(File(bin, ".l2s.coreutils0001.0002").toPath(), Files.readSymbolicLink(intermediate.toPath()))
        assertEquals("multicall", tail.readText())
        assertEquals("multicall", coreutils.readText())
    }

    @Test fun eachFurtherLinkRaisesTheCount() {
        val bin = File(dir, "usr/bin").apply { mkdirs() }
        val coreutils = File(bin, "coreutils").apply { writeText("multicall") }
        assertTrue(LinuxRuntimeInstaller.l2sLink(coreutils, File(bin, "tail")))
        assertTrue(LinuxRuntimeInstaller.l2sLink(coreutils, File(bin, "head")))
        assertTrue(LinuxRuntimeInstaller.l2sLink(File(bin, "tail"), File(bin, "cat")))
        val final = File(bin, ".l2s.coreutils0001.0004")
        assertTrue(final.isFile)
        assertFalse(File(bin, ".l2s.coreutils0001.0002").exists())
        for (name in listOf("coreutils", "tail", "head", "cat")) assertEquals("multicall", File(bin, name).readText())
    }

    @Test fun aSecondFileWithTheSameNameGetsItsOwnChain() {
        val a = File(dir, "a").apply { mkdirs() }
        val existing = File(a, ".l2s.tool0001").apply { writeText("taken") }
        val tool = File(a, "tool").apply { writeText("tool") }
        assertTrue(LinuxRuntimeInstaller.l2sLink(tool, File(a, "alias")))
        assertEquals("taken", existing.readText())
        assertEquals(File(a, ".l2s.tool0002").toPath(), Files.readSymbolicLink(tool.toPath()))
        assertEquals("tool", File(a, "alias").readText())
    }

    @Test fun linksMadeInStagingStillResolveAfterTheTreeIsRenamed() {
        val staging = File(dir, "linuxfs.new")
        val live = File(dir, "linuxfs")
        val bin = File(staging, "usr/bin").apply { mkdirs() }
        val cargo = File(staging, "usr/lib/cargo/bin/coreutils").apply { mkdirs() }
        val coreutils = File(bin, "coreutils").apply { writeText("multicall") }
        assertTrue(LinuxRuntimeInstaller.l2sLink(coreutils, File(cargo, "echo"), staging, live))
        assertTrue(LinuxRuntimeInstaller.l2sLink(File(cargo, "echo"), File(cargo, "cat"), staging, live))
        assertEquals(File(live, "usr/bin/.l2s.coreutils0001").toPath(), Files.readSymbolicLink(File(cargo, "echo").toPath()))
        assertTrue(File(staging, "usr/bin/.l2s.coreutils0001.0003").isFile)

        assertTrue(staging.renameTo(live))

        for (rel in listOf("usr/bin/coreutils", "usr/lib/cargo/bin/coreutils/echo", "usr/lib/cargo/bin/coreutils/cat"))
            assertEquals(rel, "multicall", File(live, rel).readText())
        assertTrue(LinuxRuntimeInstaller.l2sLink(File(live, "usr/bin/coreutils"), File(live, "usr/bin/tail")))
        assertEquals("multicall", File(live, "usr/bin/tail").readText())
        assertTrue(File(live, "usr/bin/.l2s.coreutils0001.0004").isFile)
    }

    @Test fun aHardLinkTargetMayAlreadyBeAnL2sLinkToTheFinalTree() {
        val staging = File(dir, "linuxfs.new")
        val live = File(dir, "linuxfs")
        val bin = File(staging, "usr/bin").apply { mkdirs() }
        File(bin, "coreutils").writeText("multicall")
        assertTrue(LinuxRuntimeInstaller.l2sLink(File(bin, "coreutils"), File(bin, "["), staging, live))
        assertEquals(File(bin, "coreutils"), LinuxRuntimeInstaller.hardLinkTarget(staging, "./usr/bin/coreutils"))
        assertTrue(LinuxRuntimeInstaller.l2sLink(LinuxRuntimeInstaller.hardLinkTarget(staging, "./usr/bin/coreutils"), File(bin, "ls"), staging, live))
        assertTrue(staging.renameTo(live))
        assertEquals("multicall", File(live, "usr/bin/ls").readText())
    }

    @Test fun aHardLinkTargetCannotLeaveTheTree() {
        val tree = File(dir, "tree").apply { mkdirs() }
        val outside = File(dir, "outside").apply { mkdirs() }
        Files.createSymbolicLink(File(tree, "escape").toPath(), outside.toPath())
        assertEquals(null, LinuxRuntimeInstaller.hardLinkTarget(tree, "../outside/x"))
        assertEquals(null, LinuxRuntimeInstaller.hardLinkTarget(tree, "escape/x"))
        assertEquals(null, LinuxRuntimeInstaller.hardLinkTarget(tree, ".."))
        assertEquals(File(tree, "x"), LinuxRuntimeInstaller.hardLinkTarget(tree, "./x"))
    }

    @Test fun directoriesAndPlainSymlinksAreLeftAlone() {
        val d = File(dir, "d").apply { mkdirs() }
        assertFalse(LinuxRuntimeInstaller.l2sLink(d, File(dir, "e")))
        val target = File(dir, "t").apply { writeText("t") }
        val link = File(dir, "link")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        assertFalse(LinuxRuntimeInstaller.l2sLink(link, File(dir, "other")))
    }
}
