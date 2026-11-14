package com.droiddeck.launcher.runtime

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LegacyNamesTest {
    private lateinit var root: File

    @Before fun setUp() { root = Files.createTempDirectory("rootfs").toFile() }
    @After fun tearDown() { root.deleteRecursively() }

    private fun write(path: String, text: String) = File(root, path).apply { parentFile?.mkdirs(); writeText(text) }
    private fun link(path: String) = Files.readSymbolicLink(File(root, path).toPath()).toString()
    private fun isLink(path: String) = Files.isSymbolicLink(File(root, path).toPath())

    @Test fun theImageEtcMovesAndTheOldPathStillReads() {
        write("etc/bannerlator/proc/stat", "cpu 1")
        write("etc/bannerlator/proc/pci_devices", "image")
        File(root, "etc/bannerlator/empty").mkdirs()
        write("etc/droiddeck/proc/pci_devices", "app")
        LegacyNames.migrateEtc(root)
        assertEquals("cpu 1", File(root, "etc/droiddeck/proc/stat").readText())
        assertEquals("app", File(root, "etc/droiddeck/proc/pci_devices").readText())
        assertTrue(File(root, "etc/droiddeck/empty").isDirectory)
        assertEquals("droiddeck", link("etc/bannerlator"))
        assertEquals("cpu 1", File(root, "etc/bannerlator/proc/stat").readText())
        LegacyNames.migrateEtc(root)
        assertEquals("cpu 1", File(root, "etc/droiddeck/proc/stat").readText())
        assertEquals("droiddeck", link("etc/bannerlator"))
    }

    @Test fun aFreshRuntimeGetsNoOldNames() {
        write("usr/local/bin/droiddeck-session", "new")
        LegacyNames.migrateRootfs(root)
        assertTrue(File(root, "etc/droiddeck/empty").isDirectory)
        assertFalse(File(root, "etc/bannerlator").exists() || isLink("etc/bannerlator"))
        assertFalse(File(root, "usr/local/bin/bannerlator-session").exists() || isLink("usr/local/bin/bannerlator-session"))
    }

    @Test fun oldScriptsBecomeLinksToTheNewOnes() {
        write("usr/local/bin/bannerlator-session", "old")
        write("usr/local/bin/droiddeck-session", "new")
        write("usr/local/bin/bannerlator-appimage-run", "old")
        write("usr/local/bin/droiddeck-appimage-run", "new")
        write("usr/local/bin/droiddeck-bwrap", "new")
        LegacyNames.migrateRootfs(root)
        assertEquals("droiddeck-session", link("usr/local/bin/bannerlator-session"))
        assertEquals("new", File(root, "usr/local/bin/bannerlator-session").readText())
        assertEquals("droiddeck-appimage-run", link("usr/local/bin/bannerlator-appimage-run"))
        assertFalse(isLink("usr/local/bin/bannerlator-bwrap"))
        LegacyNames.migrateRootfs(root)
        assertEquals("new", File(root, "usr/local/bin/bannerlator-session").readText())
        assertFalse(File(root, "usr/local/bin/bannerlator-session.link").exists())
    }

    @Test fun generatedFilesMoveOnceAndMenuEntriesPointAtTheNewScripts() {
        write("etc/bannerlator-net", "wlan0")
        write("etc/bannerlator-network.json", "{}")
        write("etc/droiddeck-network.json", "{\"new\":1}")
        write("usr/local/share/bannerlator/system-bus.conf", "<busconfig/>")
        write("usr/local/share/applications/droiddeck-appimage-emulator.desktop",
            "[Desktop Entry]\nExec=/usr/local/bin/bannerlator-appimage-run /opt/appimages/user/emulator %U\n")
        write("usr/local/share/applications/firefox.desktop", "Exec=/usr/local/bin/bannerlator-steam-launch\n")
        LegacyNames.migrateRootfs(root)
        assertEquals("wlan0", File(root, "etc/droiddeck-net").readText())
        assertFalse(File(root, "etc/bannerlator-net").exists())
        assertEquals("{\"new\":1}", File(root, "etc/droiddeck-network.json").readText())
        assertFalse(File(root, "etc/bannerlator-network.json").exists())
        assertEquals("<busconfig/>", File(root, "usr/local/share/droiddeck/system-bus.conf").readText())
        assertEquals("droiddeck", link("usr/local/share/bannerlator"))
        assertEquals("[Desktop Entry]\nExec=/usr/local/bin/droiddeck-appimage-run /opt/appimages/user/emulator %U\n",
            File(root, "usr/local/share/applications/droiddeck-appimage-emulator.desktop").readText())
        assertEquals("Exec=/usr/local/bin/bannerlator-steam-launch\n",
            File(root, "usr/local/share/applications/firefox.desktop").readText())
    }

    @Test fun catalogAppImageMenusStartThroughTheSharedLauncher() {
        write("usr/share/applications/droiddeck-rpcs3.desktop",
            "[Desktop Entry]\nName=RPCS3\nExec=env APPIMAGE_EXTRACT_AND_RUN=1 /opt/appimages/rpcs3.AppImage\nCategories=Emulator;\n")
        write("usr/share/applications/droiddeck-steam-x64.desktop", "[Desktop Entry]\nExec=droiddeck-steam-x64\n")
        write("usr/share/applications/other.desktop", "Exec=env APPIMAGE_EXTRACT_AND_RUN=1 /opt/appimages/other.AppImage\n")
        LegacyNames.migrateRootfs(root)
        LegacyNames.migrateRootfs(root)
        assertEquals("[Desktop Entry]\nName=RPCS3\nExec=/usr/local/bin/droiddeck-appimage-exec /opt/appimages/rpcs3.AppImage\nCategories=Emulator;\n",
            File(root, "usr/share/applications/droiddeck-rpcs3.desktop").readText())
        assertEquals("[Desktop Entry]\nExec=droiddeck-steam-x64\n", File(root, "usr/share/applications/droiddeck-steam-x64.desktop").readText())
        assertEquals("Exec=env APPIMAGE_EXTRACT_AND_RUN=1 /opt/appimages/other.AppImage\n",
            File(root, "usr/share/applications/other.desktop").readText())
    }
}
