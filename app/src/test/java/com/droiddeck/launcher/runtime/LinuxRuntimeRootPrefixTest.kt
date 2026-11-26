package com.droiddeck.launcher.runtime

import android.app.Application
import android.system.Os
import android.system.StructUtsname
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class, shadows = [LinuxRuntimeRootPrefixTest.Uname::class])
class LinuxRuntimeRootPrefixTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val root = File(context.filesDir, "rootfs")
    private val guest = listOf("/usr/bin/dpkg", "--configure", "-a")

    @Test fun fakeRootEmulatesHardLinksForPackageManagers() {
        val cmd = LinuxRuntime.commandWithBinds(context, root, emptyList(), guest, true)
        assertTrue("-0" in cmd)
        assertTrue("--link2symlink" in cmd)
        assertFalse("-i" in cmd)
        assertEquals(guest, cmd.takeLast(guest.size))
    }

    @Test fun rootSpawnerAndFakeRootCommandsShareOnePrefix() {
        assertEquals(LinuxRuntime.rootPrefix(context, root, "/root"),
            LinuxRuntime.commandWithBinds(context, root, emptyList(), emptyList(), true))
    }

    @Test fun userCommandsKeepTheAppUidAndRealLinks() {
        val cmd = LinuxRuntime.commandWithBinds(context, root, emptyList(), guest)
        val uid = android.os.Process.myUid()
        assertFalse("-0" in cmd)
        assertFalse("--link2symlink" in cmd)
        assertEquals("$uid:$uid", cmd[cmd.indexOf("-i") + 1])
        assertEquals(LinuxRuntime.prootPrefix(context, root, "/root"), cmd.dropLast(guest.size))
    }

    @Test fun aLinkProotMadeAfterTheNativeRelinkIsMadeRelativeAtTheNextNativeStart() {
        val home = File(root, "root/.steam").apply { mkdirs() }
        File(root, "root/.local/share/Steam/logs").mkdirs()
        val steam = File(home, "steam").toPath()
        val card = File(home, "card").toPath()
        Files.createSymbolicLink(card, Paths.get("/storage/emulated/0"))
        NativeGuest.relinkAbsoluteSymlinks(context, root)
        Files.createSymbolicLink(steam, Paths.get("/root/.local/share/Steam"))
        assertEquals(0, NativeGuest.relinkAbsoluteSymlinks(context, root))
        assertTrue(Files.readSymbolicLink(steam).isAbsolute)

        LinuxRuntime.prootPrefix(context, root, "/root")

        assertEquals(1, NativeGuest.relinkAbsoluteSymlinks(context, root))
        assertEquals(Paths.get("../.local/share/Steam"), Files.readSymbolicLink(steam))
        assertTrue(File(home, "steam/logs").isDirectory)
        assertEquals(Paths.get("/storage/emulated/0"), Files.readSymbolicLink(card))
    }

    @Test fun linksIntoSharedStorageKeepTheirGuestPath() {
        val home = File(root, "root").apply { mkdirs() }
        File(home, "Storage/Download").mkdirs()
        File(root, "root/.local/share/Steam").mkdirs()
        val downloads = File(home, "Downloads").toPath()
        val storage = File(home, "Storage").toPath()
        val steam = File(home, "steam").toPath()
        Files.createSymbolicLink(downloads, Paths.get("/root/Storage/Download"))
        Files.createSymbolicLink(File(home, "share").toPath(), Paths.get("/root/Storage"))
        Files.createSymbolicLink(steam, Paths.get("/root/.local/share/Steam"))
        Files.createSymbolicLink(File(home, "StorageX").toPath(), Paths.get("/root/StorageX"))

        assertEquals(2, NativeGuest.relinkAbsoluteSymlinks(context, root))

        assertEquals(Paths.get("/root/Storage/Download"), Files.readSymbolicLink(downloads))
        assertEquals(Paths.get("/root/Storage"), Files.readSymbolicLink(File(home, "share").toPath()))
        assertEquals(Paths.get(".local/share/Steam"), Files.readSymbolicLink(steam))
        assertEquals(Paths.get("StorageX"), Files.readSymbolicLink(File(home, "StorageX").toPath()))
        assertTrue(Files.isDirectory(storage))
    }

    @Test fun aWalkDuringALiveProotRunLeavesTheNextNativeStartToWalkAgain() {
        val home = File(root, "root").apply { mkdirs() }
        File(root, "root/.local/share/Steam").mkdirs()
        val proot = LinuxRuntime.prootBinary(context).path
        LinuxRuntime.prootPrefix(context, root, "/root")
        NativeGuest.relinkAbsoluteSymlinks(context, root) { sequenceOf(listOf("/system/bin/linker64", proot, "--kill-on-exit")) }
        val steam = File(home, "steam").toPath()
        Files.createSymbolicLink(steam, Paths.get("/root/.local/share/Steam"))

        assertEquals(1, NativeGuest.relinkAbsoluteSymlinks(context, root) { emptySequence() })
        assertEquals(Paths.get(".local/share/Steam"), Files.readSymbolicLink(steam))
        assertEquals(0, NativeGuest.relinkAbsoluteSymlinks(context, root) { emptySequence() })
    }

    @Test fun onlyTheProotTracerCountsAsALiveProotRun() {
        val proot = "/data/app/x/lib/arm64/libproot.so"
        assertTrue(NativeGuest.prootRunning(proot, sequenceOf(listOf("/system/bin/linker64", proot, "-r", "/rootfs"))))
        assertTrue(NativeGuest.prootRunning(proot, sequenceOf(listOf(proot, "-r", "/rootfs"))))
        assertFalse(NativeGuest.prootRunning(proot, sequenceOf(listOf("/usr/bin/bash", "-c", proot))))
        assertFalse(NativeGuest.prootRunning(proot, sequenceOf(listOf("$proot.old"), emptyList())))
    }

    @Test fun packageLinksAFakeRootProotRunMadeAreRelinkedToo() {
        val bin = File(root, "usr/bin").apply { mkdirs() }
        File(root, "usr/share/tool").mkdirs()
        NativeGuest.relinkAbsoluteSymlinks(context, root)
        val tool = File(bin, "tool").toPath()
        Files.createSymbolicLink(tool, Paths.get("/usr/share/tool"))

        LinuxRuntime.commandWithBinds(context, root, emptyList(), guest, true)

        assertEquals(1, NativeGuest.relinkAbsoluteSymlinks(context, root))
        assertEquals(Paths.get("../share/tool"), Files.readSymbolicLink(tool))
        assertEquals(0, NativeGuest.relinkAbsoluteSymlinks(context, root))
    }

    @Implements(Os::class)
    class Uname {
        companion object {
            @JvmStatic @Implementation
            fun uname() = StructUtsname("Linux", "localhost", "6.1.0", "#1 SMP", "aarch64")
        }
    }
}
