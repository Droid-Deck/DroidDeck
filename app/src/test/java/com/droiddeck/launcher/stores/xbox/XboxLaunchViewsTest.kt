package com.droiddeck.launcher.stores.xbox

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class XboxLaunchViewsTest {
    @Test fun cleanupRemovesLicensedCopiesWithoutFollowingGameLinks() {
        val root = Files.createTempDirectory("xbox-view-test").toFile()
        try {
            val game = root.resolve("installed").apply { mkdirs() }
            game.resolve("Game.exe").writeText("encrypted original")
            val view = XboxLaunchViews().create(root)
            view.resolve("Game.exe").writeText("licensed copy")
            Files.createSymbolicLink(view.resolve("originals").toPath(), game.toPath())
            XboxLaunchViews.remove(view)
            XboxLaunchViews.remove(view)
            assertFalse(view.exists())
            assertEquals("encrypted original", game.resolve("Game.exe").readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun recoveryRejectsARedirectedStorageRoot() {
        val root = Files.createTempDirectory("xbox-view-links").toFile()
        try {
            val outside = root.resolve("installed").apply { mkdirs() }
            val game = outside.resolve("launch-game").apply { mkdirs() }.resolve("Game.exe")
            game.writeText("preserve")
            val runtime = root.resolve("xbox-runtime").apply { mkdirs() }
            Files.createSymbolicLink(runtime.resolve("views").toPath(), outside.toPath())
            assertThrows(IllegalStateException::class.java) { XboxLaunchViews().create(root) }
            assertEquals("preserve", game.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun recoveryRemovesAbandonedViewsAndPreservesNewLiveViews() {
        val root = Files.createTempDirectory("xbox-view-recovery").toFile()
        try {
            val old = XboxLaunchViews().create(root)
            old.resolve("Game.exe").writeText("abandoned copy")
            val evidence = old.parentFile!!.resolve("diagnostics").apply { mkdirs() }.resolve("report")
            evidence.writeText("preserve")
            val fresh = XboxLaunchViews()
            val first = fresh.create(root)
            first.resolve("Game.exe").writeText("running copy")
            val second = fresh.create(root)
            assertFalse(old.exists())
            assertEquals("running copy", first.resolve("Game.exe").readText())
            assertTrue(second.isDirectory)
            assertEquals("preserve", evidence.readText())
        } finally { root.deleteRecursively() }
    }
}
