package com.droiddeck.launcher.core

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.update.SelfInstaller
import com.droiddeck.launcher.session.ProtonExtras
import com.droiddeck.launcher.runtime.DesktopCatalog
import com.droiddeck.launcher.runtime.LinuxRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.io.File
import java.util.function.BooleanSupplier

@RunWith(DroidDeckTestRunner::class)
class PlayDistributionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun mergedManifestRestrictsPackageInstallationToStandaloneDistribution() {
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
        assertEquals(!BuildConfig.PLAY_DISTRIBUTION, "android.permission.REQUEST_INSTALL_PACKAGES" in permissions)
    }

    @Test fun missingPlayComponentCannotContactAnExternalServerOrReplaceExistingData() {
        if (!BuildConfig.PLAY_DISTRIBUTION) return
        ComponentDownloads.init(context)
        val destination = temporary.newFile("component")
        destination.writeText("existing")
        ServerSocket(0).use { server ->
            server.soTimeout = 100
            assertFalse(ComponentDownloads.downloadFile("http://127.0.0.1:${server.localPort}/missing", destination, true, null))
            assertThrows(SocketTimeoutException::class.java) { server.accept() }
        }
        assertEquals("existing", destination.readText())
    }

    @Test fun playDistributionRejectsSelfInstallationBeforeOpeningPackageInstaller() {
        if (!BuildConfig.PLAY_DISTRIBUTION) return
        assertFalse(SelfInstaller.canInstall(context))
        assertThrows(IllegalStateException::class.java) { SelfInstaller.install(context, temporary.newFile("update.apk")) }
    }

    @Test fun playOffersOnlyDroidDeckProtonWhileStandaloneKeepsItsChoices() {
        assertEquals(if (BuildConfig.PLAY_DISTRIBUTION) listOf("droiddeck") else listOf("ge", "cachyos", "droiddeck"),
            ProtonExtras.tools.map { it.id })
    }

    @Test fun freshPlayInstallationRequiresDroidDeckProtonInsteadOfValveSeed() {
        if (!BuildConfig.PLAY_DISTRIBUTION) return
        assertEquals(true, DesktopCatalog.protonSeedNeeded(context))
        val directory = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/compatibilitytools.d/DroidDeck-Proton-test")
        directory.mkdirs()
        File(directory, "toolmanifest.vdf").writeText("fixture")
        assertFalse(DesktopCatalog.protonSeedNeeded(context))
    }

    @Test fun cancelledProtonBootstrapStopsBeforeRuntimeChanges() {
        val root = LinuxRuntime.rootDir(context)
        val before = root.exists()
        val tool = ProtonExtras.tools.first { it.id == ProtonExtras.OWN }
        val result = ProtonExtras.install(context, tool, BooleanSupplier { true }) { _, _ ->
            throw AssertionError("Cancelled bootstrap reported installation progress")
        }
        assertEquals(context.getString(com.droiddeck.launcher.R.string.pextra_interrupted), result)
        assertEquals(before, root.exists())
        assertFalse(ProtonExtras.installInProgress)
    }

    @Test fun standaloneCatalogCannotOfferAnUnbundledFexVersionAfterPlayUpgrade() {
        if (!BuildConfig.PLAY_DISTRIBUTION) return
        val old = File(context.filesDir, "fex-catalog.json")
        val body = """{"release":"fex-2601-r1","url":"https://github.com/Droid-Deck/DroidDeck-Components/releases/download/fex-2601-r1/fex.tzst","sha256":"${"a".repeat(64)}"}"""
        old.writeText(body)
        assertEquals("fex-2601-r1", com.droiddeck.launcher.session.FexCatalog.parse(body)?.tag)
        assertEquals(null, com.droiddeck.launcher.session.FexCatalog.cached(context))
        assertEquals(body, old.readText())
    }

    @Test fun extraDesktopEmulatorsAreOnlyOfferedByTheStandaloneDistribution() {
        val entry = DesktopCatalog.Entry("emulators", "Emulators", 1, "r1", "tar", "https://example.test/emulators.tar.zst",
            "a".repeat(64), 100, "", "", "Game")
        assertEquals(!BuildConfig.PLAY_DISTRIBUTION, DesktopCatalog.offered(entry))
        val desktop = DesktopCatalog.Entry(DesktopCatalog.DESKTOP_ID, "Desktop", 1, "r1", "tar",
            "https://example.test/desktop.tar.zst", "b".repeat(64), 100, "", "", "")
        assertEquals(true, DesktopCatalog.offered(desktop))
    }

    @Test fun playDoesNotResumeOrDiscardAnOldExtraEmulatorInstallation() {
        if (!BuildConfig.PLAY_DISTRIBUTION) return
        val root = LinuxRuntime.rootDir(context).apply { mkdirs() }
        val entry = DesktopCatalog.Entry("emulators", "Emulators", 1, "r1", "tar", "https://example.test/emulators.tar.zst",
            "a".repeat(64), 100, "", "", "Game")
        val journal = File(root, ".droiddeck-pkg-emulators.installing")
        val original = DesktopCatalog.entryJson(entry).toString()
        journal.writeText(original)
        assertEquals(emptyList<DesktopCatalog.Entry>(), DesktopCatalog.interrupted(context))
        assertEquals(null, DesktopCatalog.resumeInterrupted(context, null))
        assertEquals(original, journal.readText())
        assertEquals(listOf("emulators"), DesktopCatalog.interrupted(root).map { it.id })
    }
}
