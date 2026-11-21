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
