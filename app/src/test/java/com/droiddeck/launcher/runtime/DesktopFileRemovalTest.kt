package com.droiddeck.launcher.runtime

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DesktopFileRemovalTest {
    private lateinit var dir: File
    private lateinit var root: File
    @Before fun setup() { dir = Files.createTempDirectory("desktop-removal").toFile(); root = File(dir, "linuxfs").apply { mkdirs() } }
    @After fun cleanup() { RuntimeFileTree.delete(dir, null) }
    private fun file(path: String, data: String) = File(root, path).apply { parentFile.mkdirs(); writeText(data) }
    private fun entry(path: String, file: File) = DesktopFileRemoval.Entry("F", file.length(), DesktopFileRemoval.sha(file), path)

    @Test fun removesUnchangedFilesAndEmptyParentsButPreservesUserDataAndSharedFiles() {
        val desktop = file("usr/share/plasma/only/asset", "desktop")
        val shared = file("usr/lib/shared.so", "shared")
        val custom = file("usr/share/plasma/custom", "user")
        val home = file("root/.local/share/Steam/game", "game")
        val result = DesktopFileRemoval.remove(root, listOf(entry("usr/share/plasma/only/asset", desktop), entry("usr/lib/shared.so", shared), entry("root/.local/share/Steam/game", home)), setOf("usr/lib/shared.so"))
        assertEquals(1, result.removed)
        assertEquals(7L, result.bytes)
        assertFalse(desktop.parentFile.exists())
        assertEquals("shared", shared.readText())
        assertEquals("user", custom.readText())
        assertEquals("game", home.readText())
    }

    @Test fun preservesChangesIncludingSameLengthEditsAndSymlinkReplacement() {
        val file = file("usr/bin/desktop", "before")
        val expected = entry("usr/bin/desktop", file)
        file.writeText("edited")
        val target = file("root/private", "data")
        val link = File(root, "usr/bin/link")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        val result = DesktopFileRemoval.remove(root, listOf(expected, DesktopFileRemoval.Entry("L", 0, "/usr/bin/original", "usr/bin/link")), emptySet())
        assertEquals(0, result.removed)
        assertEquals("edited", file.readText())
        assertTrue(Files.isSymbolicLink(link.toPath()))
    }

    @Test fun neverFollowsParentsEvenWhenTheyPointInsideRootAndOnlyUnlinksRecordedLinks() {
        val keep = file("root/private/asset", "desktop")
        File(root, "usr/share").mkdirs()
        Files.createSymbolicLink(File(root, "usr/share/plasma").toPath(), File(root, "root/private").toPath())
        val dangling = File(root, "usr/share/dangling")
        Files.createSymbolicLink(dangling.toPath(), File("/missing").toPath())
        val result = DesktopFileRemoval.remove(root, listOf(entry("usr/share/plasma/asset", keep), DesktopFileRemoval.Entry("L", 0, "/missing", "usr/share/dangling")), emptySet())
        assertEquals(1, result.removed)
        assertEquals("desktop", keep.readText())
        assertFalse(Files.exists(dangling.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
    }

    @Test fun rejectsTraversalAbsolutePathsAndUserHome() {
        for (path in listOf("../etc/x", "/etc/x", "usr/../../x", "usr/./x", "usr//x", "root/x", "opt/appimages/x"))
            assertNull(path, DesktopFileRemoval.safeFile(root, path))
    }

    @Test fun interruptedRemovalIsIdempotentAndRetriesRemainingFiles() {
        val one = file("usr/bin/one", "one")
        val two = file("usr/bin/two", "two")
        val entries = listOf(entry("usr/bin/one", one), entry("usr/bin/two", two))
        try { DesktopFileRemoval.remove(root, entries.asSequence().constrainOnce().asIterable(), emptySet()) { throw IllegalStateException("process ended") }; fail() }
        catch (_: IllegalStateException) { }
        assertFalse(one.exists())
        assertTrue(two.exists())
        assertEquals(1, DesktopFileRemoval.remove(root, entries, emptySet()).removed)
        assertEquals(0, DesktopFileRemoval.remove(root, entries, emptySet()).removed)
    }

    @Test fun readsOnlyRuntimePackageFilesAndRefusesMissingOwnership() {
        try { DesktopFileRemoval.runtimeFiles(root); fail() } catch (_: java.io.IOException) { }
        file("var/lib/pacman/local/base-1/files", "%FILES%\nusr/\nusr/bin/\nusr/bin/gamescope\n\n%BACKUP%\netc/config\n")
        assertEquals(setOf("usr/bin/gamescope"), DesktopFileRemoval.runtimeFiles(root))
    }
}
