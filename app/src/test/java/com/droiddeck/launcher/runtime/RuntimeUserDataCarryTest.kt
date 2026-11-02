package com.droiddeck.launcher.runtime

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeUserDataCarryTest {
    @get:Rule val tmp = TemporaryFolder()

    @After fun resetFaults() { RuntimeFileTree.refuseForTest = null }

    private fun put(root: File, rel: String, text: String = rel) = File(root, rel).apply { parentFile.mkdirs(); writeText(text) }

    private fun userTree(): File = tmp.newFolder("old").also { old ->
        put(old, "root/save.dat", "mine")
        put(old, "opt/appimages/user/app1/app.AppImage")
        put(old, "opt/droiddeck-apps/game/run.sh")
        put(old, "opt/droiddeck/wincomponents/d3dx9/component.json")
        put(old, "usr/local/share/applications/droiddeck-app-game.desktop")
        put(old, "usr/local/share/applications/droiddeck-appimage-app1.desktop")
        put(old, "usr/local/share/applications/other.desktop")
        put(old, "opt/appimages/dolphin.AppImage")
        put(old, "usr/share/applications/droiddeck-dolphin.desktop")
        put(old, ".droiddeck-pkg-dolphin", "1.0")
        put(old, "usr/bin/tool", "old system file")
    }

    @Test fun anUpdateKeepsImportedAppsCatalogAppImagesAndHome() {
        val old = userTree()
        val staging = tmp.newFolder("new").also { put(it, "root/.bashrc", "shipped"); put(it, "usr/bin/tool", "new system file") }

        val moved = RuntimeFileTree.carryUserData(old, staging, false)

        assertEquals("mine", File(staging, "root/save.dat").readText())
        assertFalse("the shipped home is replaced, not merged", File(staging, "root/.bashrc").exists())
        for (rel in listOf("opt/appimages/user/app1/app.AppImage", "opt/droiddeck-apps/game/run.sh",
                "opt/droiddeck/wincomponents/d3dx9/component.json",
                "usr/local/share/applications/droiddeck-app-game.desktop", "usr/local/share/applications/droiddeck-appimage-app1.desktop",
                "opt/appimages/dolphin.AppImage", "usr/share/applications/droiddeck-dolphin.desktop", ".droiddeck-pkg-dolphin")) {
            assertTrue(rel, File(staging, rel).exists())
            assertTrue(rel, rel in moved || moved.any { rel.startsWith("$it/") })
        }
        assertEquals("system files come from the new runtime", "new system file", File(staging, "usr/bin/tool").readText())
        assertFalse("only DroidDeck's own menu entries are user data", File(staging, "usr/local/share/applications/other.desktop").exists())
    }

    @Test fun aMenuEntryTheNewRuntimeShipsIsNotCarried() {
        val old = tmp.newFolder("old").also {
            put(it, "root/save.dat", "mine")
            put(it, "usr/share/applications/droiddeck-return-gaming.desktop", "old runtime")
            put(it, "usr/share/applications/droiddeck-steam-x64.desktop", "made by the old desktop")
        }
        val staging = tmp.newFolder("new").also {
            put(it, "root/.bashrc", "shipped")
            put(it, "usr/share/applications/droiddeck-return-gaming.desktop", "new runtime")
        }

        val paths = RuntimeFileTree.existingUserPaths(old, staging)

        assertTrue("root" in paths)
        assertTrue("usr/share/applications/droiddeck-steam-x64.desktop" in paths)
        assertFalse("usr/share/applications/droiddeck-return-gaming.desktop" in paths)
        RuntimeFileTree.carryUserData(old, staging, false, paths)
        assertEquals("new runtime", File(staging, "usr/share/applications/droiddeck-return-gaming.desktop").readText())
    }

    @Test fun cachesTiedToThePreviousRuntimeAreDropped() {
        val root = tmp.newFolder("rt")
        val keep = listOf("root/.cache/mozilla/cache2/entry", "root/.cache/droiddeck/session.log",
            "root/.local/share/droiddeck/apt/packages.txt", "root/.local/share/Steam/steam.sh", "root/.config/kwinrc")
        val drop = listOf("root/.local/share/droiddeck/pacman/packages.txt", "root/.cache/droiddeck/pacman-restore.log",
            "root/.cache/mesa_shader_cache_db/index", "root/.cache/mesa_shader_cache/a/b", "root/.cache/appstream/x.xb",
            "root/.cache/discover/c", "root/.cache/ksycoca6_en-POSIX_abc", "root/.cache/plasma_theme_Vapor.kcache",
            "root/.cache/kwinsession.kcache", "root/.cache/qtshadercache-arm64-little_endian-lp64/s",
            "root/.cache/event-sound-cache.tdb.1.aarch64-unknown-linux-gnu", "root/.cache/fontconfig/x.cache-9")
        (keep + drop).forEach { put(root, it) }

        RuntimeFileTree.dropStaleState(root)

        keep.forEach { assertTrue(it, File(root, it).exists()) }
        drop.forEach { assertFalse(it, File(root, it).exists()) }
    }

    @Test fun droppingStateWithoutAHomeDoesNothing() {
        RuntimeFileTree.dropStaleState(tmp.newFolder("empty"))
    }

    @Test fun aFailureHalfwayPutsEverythingBack() {
        val old = userTree()
        val staging = tmp.newFolder("new")
        put(staging, "usr/local", "a file where a folder is needed")

        try {
            RuntimeFileTree.carryUserData(old, staging, false)
            fail("expected the carry to fail")
        } catch (expected: IOException) {
        }

        assertEquals("mine", File(old, "root/save.dat").readText())
        assertTrue(File(old, "opt/appimages/user/app1/app.AppImage").exists())
        assertTrue(File(old, "opt/droiddeck-apps/game/run.sh").exists())
        assertFalse(File(staging, "root").exists())
    }

    @Test fun recoveryOnlyFillsWhatIsMissing() {
        val target = tmp.newFolder("target").also { put(it, "opt/droiddeck-apps/game/run.sh", "kept") }
        val staging = tmp.newFolder("staging").also {
            put(it, "opt/droiddeck-apps/game/run.sh", "stale")
            put(it, "opt/appimages/user/app1/app.AppImage")
        }

        val moved = RuntimeFileTree.carryUserData(staging, target, true)

        assertEquals(listOf("opt/appimages/user"), moved)
        assertEquals("kept", File(target, "opt/droiddeck-apps/game/run.sh").readText())
        assertTrue(File(target, "opt/appimages/user/app1/app.AppImage").exists())
    }
    @Test fun recoveryFindsCompanionsWhoseAppImageWasAlreadyPutBack() {
        val root = tmp.newFolder("runtime")
        val staging = tmp.newFolder("runtime.new")
        val old = File(tmp.root, "runtime.old")
        put(root, "opt/appimages/dolphin.AppImage", "user app")
        put(staging, LinuxRuntimeInstaller.CARRY_MARKER, "1")
        put(staging, "usr/share/applications/droiddeck-dolphin.desktop", "menu")
        put(staging, ".droiddeck-pkg-dolphin", "version")
        put(staging, ".droiddeck-pkg-desktop-kde", "system package marker")

        LinuxRuntimeInstaller.recoverInterruptedSwap(root, staging, old)
        assertEquals("menu", File(root, "usr/share/applications/droiddeck-dolphin.desktop").readText())
        assertEquals("version", File(root, ".droiddeck-pkg-dolphin").readText())
        assertFalse(File(root, ".droiddeck-pkg-desktop-kde").exists())
        assertEquals("user app", File(root, "opt/appimages/dolphin.AppImage").readText())
    }

    @Test fun unmarkedExtractedStagingDoesNotBlockRecovery() {
        val root = tmp.newFolder("runtime")
        val staging = tmp.newFolder("runtime.new")
        val old = File(tmp.root, "runtime.old")
        put(root, "root/save.dat", "current user data")
        put(staging, "root/.bashrc", "shipped home from interrupted extract")

        LinuxRuntimeInstaller.recoverInterruptedSwap(root, staging, old)
        assertEquals("current user data", File(root, "root/save.dat").readText())
        assertEquals("shipped home from interrupted extract", File(staging, "root/.bashrc").readText())
    }

    @Test fun anUnreadableAppImageDirectoryAbortsBeforeMovingHome() {
        assumeTrue(Files.getFileStore(tmp.root.toPath()).supportsFileAttributeView("posix"))
        val old = userTree()
        val images = File(old, "opt/appimages")
        val staging = tmp.newFolder("staging")
        assertTrue(images.setReadable(false, false))
        assertTrue(images.setExecutable(false, false))
        try {
            assertThrows(IOException::class.java) { RuntimeFileTree.carryUserData(old, staging, false) }
            assertEquals("mine", File(old, "root/save.dat").readText())
            assertFalse(File(staging, "root/save.dat").exists())
        } finally {
            images.setReadable(true, true)
            images.setExecutable(true, true)
        }
    }

    @Test fun recoveryRestoresTheRuntimeBeforeTryingAFallibleHomeMove() {
        val root = File(tmp.root, "runtime")
        val old = tmp.newFolder("runtime.old")
        val staging = tmp.newFolder("runtime.new")
        put(old, "usr/bin/usable", "previous runtime")
        File(old, "root").mkdirs()
        put(staging, LinuxRuntimeInstaller.CARRY_MARKER, "1")
        put(staging, "root/save.dat", "staged user data")
        RuntimeFileTree.refuseForTest = java.util.function.Predicate { it.fileName.toString() == "root" }

        assertThrows(IOException::class.java) { LinuxRuntimeInstaller.recoverInterruptedSwap(root, staging, old) }
        assertTrue("the previous runtime was not restored", root.isDirectory)
        assertEquals("previous runtime", File(root, "usr/bin/usable").readText())
        assertEquals("staged user data", File(staging, "root/save.dat").readText())
    }

    @Test fun conflictingRecoveryDataIsRetainedOnEveryRetry() {
        val root = tmp.newFolder("runtime")
        val staging = tmp.newFolder("runtime.new")
        val old = File(tmp.root, "runtime.old")
        put(root, "root/current.dat", "current user data")
        put(staging, LinuxRuntimeInstaller.CARRY_MARKER, "1")
        put(staging, "root/older.dat", "unrecovered user data")

        repeat(2) {
            assertThrows(IOException::class.java) { LinuxRuntimeInstaller.recoverInterruptedSwap(root, staging, old) }
            assertEquals("current user data", File(root, "root/current.dat").readText())
            assertEquals("unrecovered user data", File(staging, "root/older.dat").readText())
        }
    }

    @Test fun stagedDataWithoutASurvivingRuntimeIsKeptForRecovery() {
        val root = File(tmp.root, "runtime")
        val old = File(tmp.root, "runtime.old")
        val staging = tmp.newFolder("runtime.new")
        put(staging, LinuxRuntimeInstaller.CARRY_MARKER, "1")
        put(staging, "root/save.dat", "only surviving user data")
        assertThrows(IOException::class.java) { LinuxRuntimeInstaller.recoverInterruptedSwap(root, staging, old) }
        assertEquals("only surviving user data", File(staging, "root/save.dat").readText())
    }

    @Test fun conflictingIntendedHomesAreRetainedWithoutProofOfTheirContents() {
        val root = File(tmp.root, "runtime")
        val old = tmp.newFolder("runtime.old")
        val staging = tmp.newFolder("runtime.new")
        put(old, "root/save.dat", "user data")
        put(staging, LinuxRuntimeInstaller.CARRY_MARKER, "root")
        put(staging, "root/.bashrc", "shipped default")

        assertThrows(IOException::class.java) { LinuxRuntimeInstaller.recoverInterruptedSwap(root, staging, old) }

        assertEquals("user data", File(root, "root/save.dat").readText())
        assertEquals("shipped default", File(staging, "root/.bashrc").readText())
        assertTrue(File(staging, LinuxRuntimeInstaller.CARRY_MARKER).exists())
    }

    @Test fun anUnreadableCarryMarkerStopsRecovery() {
        val root = tmp.newFolder("runtime")
        val staging = tmp.newFolder("runtime.new")
        val old = File(tmp.root, "runtime.old")
        val marker = File(staging, LinuxRuntimeInstaller.CARRY_MARKER).apply { mkdirs() }

        assertThrows(IOException::class.java) { LinuxRuntimeInstaller.recoverInterruptedSwap(root, staging, old) }
        assertTrue(marker.isDirectory)
    }

    @Test fun recoveryMovesOnlyAnIntendedPathThatWasAlreadyCarried() {
        val root = File(tmp.root, "runtime")
        val old = tmp.newFolder("runtime.old")
        val staging = tmp.newFolder("runtime.new")
        put(staging, LinuxRuntimeInstaller.CARRY_MARKER, "root")
        put(staging, "root/save.dat", "moved user data")

        LinuxRuntimeInstaller.recoverInterruptedSwap(root, staging, old)

        assertEquals("moved user data", File(root, "root/save.dat").readText())
        assertFalse(File(staging, LinuxRuntimeInstaller.CARRY_MARKER).exists())
    }

    @Test fun aSymlinkedParentCannotRedirectAUserDataMoveOutsideTheRuntime() {
        val old = tmp.newFolder("old")
        val staging = tmp.newFolder("staging")
        val outside = tmp.newFolder("outside")
        put(outside, "droiddeck-apps/game/run.sh", "outside data")
        Files.createSymbolicLink(File(old, "opt").toPath(), outside.toPath())

        assertThrows(IOException::class.java) { RuntimeFileTree.carryUserData(old, staging, false) }

        assertEquals("outside data", File(outside, "droiddeck-apps/game/run.sh").readText())
        assertFalse(File(staging, "opt/droiddeck-apps").exists())
    }

    @Test fun anInaccessibleLiveHomeIsNeverTreatedAsMissing() {
        assumeTrue(Files.getFileStore(tmp.root.toPath()).supportsFileAttributeView("posix"))
        val root = tmp.newFolder("runtime")
        val staging = tmp.newFolder("runtime.new")
        val old = File(tmp.root, "runtime.old")
        put(root, "root/keep.dat", "current user data")
        put(staging, LinuxRuntimeInstaller.CARRY_MARKER, "1")
        put(staging, "root/staged.dat", "staged user data")
        assertTrue(root.setExecutable(false, false))
        val failure = try {
            runCatching { LinuxRuntimeInstaller.recoverInterruptedSwap(root, staging, old) }.exceptionOrNull()
        } finally {
            root.setExecutable(true, true)
        }

        assertTrue("the existing live home was deleted", File(root, "root/keep.dat").isFile)
        assertEquals("current user data", File(root, "root/keep.dat").readText())
        assertEquals("staged user data", File(staging, "root/staged.dat").readText())
        assertTrue("inaccessible data must abort recovery", failure is IOException)
    }
}
