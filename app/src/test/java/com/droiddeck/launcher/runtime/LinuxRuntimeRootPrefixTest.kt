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

    @Implements(Os::class)
    class Uname {
        companion object {
            @JvmStatic @Implementation
            fun uname() = StructUtsname("Linux", "localhost", "6.1.0", "#1 SMP", "aarch64")
        }
    }
}
