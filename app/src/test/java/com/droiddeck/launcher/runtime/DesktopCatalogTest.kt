package com.droiddeck.launcher.runtime

import com.droiddeck.launcher.BuildConfig
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopCatalogTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun desktopNeedsTheCompleteCurrentPinnedPackage() {
        val root = temporary.root
        val kwin = File(root, "usr/bin/kwin_wayland").apply {
            parentFile.mkdirs()
            writeText("fixture")
        }
        val marker = File(root, ".droiddeck-pkg-${DesktopCatalog.DESKTOP_ID}")
        assertFalse(DesktopCatalog.desktopInstalled(root))
        marker.writeText("older-package")
        assertFalse(DesktopCatalog.desktopInstalled(root))
        marker.writeText("${BuildConfig.DESKTOP_KDE_TAG}\n")
        assertTrue(DesktopCatalog.desktopInstalled(root))
        kwin.delete()
        assertFalse(DesktopCatalog.desktopInstalled(root))
    }
}
