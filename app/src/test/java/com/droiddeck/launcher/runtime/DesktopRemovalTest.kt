package com.droiddeck.launcher.runtime

import android.content.ContextWrapper
import com.droiddeck.launcher.BuildConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.GZIPInputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DesktopRemovalTest {
    private val dir = Files.createTempDirectory("desktop-removal").toFile()
    private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir() = File(dir, "files").apply { mkdirs() }
        override fun getCacheDir() = File(dir, "cache").apply { mkdirs() }
    }
    private val root = LinuxRuntime.rootDir(context).apply { mkdirs() }
    private val marker = File(root, ".droiddeck-pkg-${DesktopCatalog.DESKTOP_ID}")
    private val journal = File(root, marker.name + ".installing")
    private val pending = File(root, DesktopRemoval.PENDING)
    private val listener = LinuxRuntimeInstaller.ProgressListener { _, _ -> }

    @After fun cleanup() { dir.deleteRecursively() }

    @Test fun removesTheDesktopWhileKeepingRuntimeAppsGamesAndSettings() {
        val desktop = desktopFiles()
        marker.writeText(BuildConfig.DESKTOP_KDE_TAG)
        val keep = listOf("root/.local/share/Steam/steamapps/common/game/data", "root/.config/kdeglobals",
            "opt/appimages/custom.AppImage", "usr/lib/shared.so", "usr/share/plasma/custom")
            .map { file(it, "keep") }

        assertTrue(DesktopCatalog.desktopPresent(context))
        assertNull(DesktopCatalog.removeDesktop(context, listener))

        assertFalse(Files.exists(desktop.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        keep.forEach { assertEquals("keep", it.readText()) }
        assertFalse(DesktopCatalog.desktopPresent(context))
        assertFalse(DesktopCatalog.desktopInstalled(context))
    }

    @Test fun interruptedInstallCanBeRemovedWithoutBeingResumedAtTheNextLaunch() {
        desktopFiles()
        val entry = DesktopCatalog.desktopEntry(context)
        journal.writeText(DesktopCatalog.entryJson(entry).toString())
        val download = File(context.cacheDir, "pkg-${entry.id}-${entry.sha256.take(12)}.download").apply { writeText("download") }
        val resume = File(download.path + ".resume.properties").apply { writeText("resume") }
        val unrelated = file(".droiddeck-pkg-emulators.installing", DesktopCatalog.entryJson(DesktopCatalog.Entry(
            "emulators", "Emulators", 1, "1", "tar", "https://example.invalid/emulators", "a".repeat(64), 1L, "", "", "",
        )).toString())
        assertTrue(DesktopCatalog.desktopPresent(context))

        assertNotNull(DesktopCatalog.removeDesktop(context) { _, _ -> error("interrupted") })
        assertTrue(pending.exists())
        assertTrue(journal.exists())
        assertTrue(DesktopCatalog.desktopPresent(context))
        assertNull(DesktopCatalog.removeDesktop(context, listener))

        assertFalse(journal.exists())
        assertFalse(pending.exists())
        assertFalse(download.exists())
        assertFalse(resume.exists())
        assertFalse(DesktopCatalog.desktopPresent(context))
        assertEquals(listOf("emulators"), DesktopCatalog.interrupted(context).map { it.id })
        assertTrue(unrelated.exists())
    }

    @Test fun refusesUnknownDesktopVersionsAndMissingRuntimeOwnershipBeforeDeletingAnything() {
        val desktop = desktopFiles()
        marker.writeText("future-desktop")
        assertNotNull(DesktopCatalog.removeDesktop(context, listener))
        assertEquals("future-desktop", marker.readText())
        marker.writeText(BuildConfig.DESKTOP_KDE_TAG)
        journal.writeText(DesktopCatalog.entryJson(DesktopCatalog.desktopEntry(context)).put("version", "future-desktop").toString())
        assertNotNull(DesktopCatalog.removeDesktop(context, listener))
        assertTrue(journal.exists())
        journal.delete()
        File(root, "var/lib/pacman/local/base-1/files").delete()
        assertNotNull(DesktopCatalog.removeDesktop(context, listener))
        assertEquals(BuildConfig.DESKTOP_KDE_TAG, marker.readText())
        assertFalse(pending.exists())
        assertTrue(Files.isSymbolicLink(desktop.toPath()))
    }

    @Test fun failedDownloadCleanupKeepsTheInstallRecordSoRemovalCanRetry() {
        val entry = DesktopCatalog.desktopEntry(context)
        val download = File(context.cacheDir, "pkg-${entry.id}-${entry.sha256.take(12)}.download")
        for (blocked in listOf(download, File(download.path + ".resume.properties"))) {
            desktopFiles()
            journal.writeText(DesktopCatalog.entryJson(entry).toString())
            blocked.mkdirs()
            val blocker = File(blocked, "blocker").apply { writeText("fixture") }

            assertNotNull(DesktopCatalog.removeDesktop(context, listener))
            assertTrue(journal.exists())
            assertTrue(pending.exists())
            assertTrue(blocker.delete())
            assertNull(DesktopCatalog.removeDesktop(context, listener))
            assertFalse(blocked.exists())
            assertFalse(journal.exists())
            assertFalse(pending.exists())
        }
    }

    private fun file(path: String, text: String) = File(root, path).apply { parentFile!!.mkdirs(); writeText(text) }

    private fun desktopFiles(): File {
        file("var/lib/pacman/local/base-1/files", "%FILES%\nusr/lib/shared.so\n")
        val entry = GZIPInputStream(context.assets.open("desktop-removal/kde-remove.tsv.gzip")).bufferedReader().use { reader ->
            reader.lineSequence().map(DesktopFileRemoval.Entry::parse).first { it.kind == "L" && it.path.startsWith("usr/") }
        }
        return File(root, entry.path).apply {
            parentFile!!.mkdirs()
            Files.createSymbolicLink(toPath(), Paths.get(entry.identity))
        }
    }
}
