package com.droiddeck.launcher.runtime

import android.content.Context
import android.system.StructUtsname
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Robolectric leaves Os.uname() null; prootPrefix() reads it for the guest's kernel identity. */
@Implements(android.system.Os::class)
class ShadowOsUname {
    companion object {
        @Implementation
        @JvmStatic
        protected fun uname(): StructUtsname =
            StructUtsname("Linux", "localhost", "6.1.0", "#1 SMP", "aarch64")
    }
}

/**
 * The two ways into the guest - proot's `-b` and the rooted runner's `--bind` - take the same list,
 * and the runner's command line has to carry it through unchanged. A rooted session that mounted
 * less than an unrooted one would be a session that half works, which is the failure this pins.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [ShadowOsUname::class])
class RootRunnerCommandTest {
    private lateinit var context: Context
    private lateinit var sessionRoot: File
    private lateinit var runtimeDir: File

    @Test fun everyBindIsPassedToTheRunner() {
        context = RuntimeEnvironment.getApplication()
        sessionRoot = File(context.cacheDir, "session").apply { mkdirs() }
        runtimeDir = File(context.cacheDir, "wayland-rt").apply { mkdirs() }
        val specs = LinuxRuntime.bindSpecs(context, sessionRoot, runtimeDir, null, listOf("/host/games:/games"))
        assertTrue("the bind list is empty", specs.isNotEmpty())

        val command = LinuxRuntime.rootRunnerCommand(
            context, File("/native/librootrun.so"), File(sessionRoot, "guest.pid"),
            sessionRoot, runtimeDir, null, listOf("/host/games:/games"),
            listOf("HOME=/root", "BL_WIDTH=1920"), listOf("/usr/local/bin/bannerlator-session", "steam"),
        )
        val binds = argumentsOf(command, "--bind")

        // /proc, /sys and /dev are mounted by the runner itself, so they are not binds.
        val expected = specs.filter { it != "/proc" && it != "/sys" && it != "/dev" }
        assertEquals(expected, binds)
        assertEquals(specs.size, binds.size + 3)

        // The app's own directories are bound at their own paths, so a spec without a colon means
        // exactly that; one with a colon names the guest side, which must be absolute - the runner
        // joins it to the root, and a relative one would land beside the root instead of inside it.
        for (spec in binds) {
            val colon = spec.indexOf(':')
            val guestSide = if (colon < 0) spec else spec.substring(colon + 1)
            if (colon >= 0) assertTrue("empty guest side in $spec", colon < spec.length - 1)
            assertTrue("guest side of $spec is not absolute", guestSide.startsWith("/"))
        }

        assertEquals(listOf("HOME=/root", "BL_WIDTH=1920"), argumentsOf(command, "--env"))
        assertEquals(
            listOf("/usr/local/bin/bannerlator-session", "steam"),
            command.drop(command.indexOf("--") + 1),
        )
        assertEquals("/root", argumentsOf(command, "--dir").single())
        assertEquals(sessionRoot.path + "/guest.pid", argumentsOf(command, "--pidfile").single())
        assertFalse("the runner must be the program", command.first().isEmpty())
    }

    /**
     * The proot command line starts with proot, and the guest's program follows the binds.
     *
     * A merge once left this builder returning its own option list instead of the command, so the
     * argument vector began at `--kill-on-exit` with no proot in front. ProcessBuilder then failed
     * with "Cannot run program \"--kill-on-exit\": error=2" and every proot session ended at
     * "session pid -1" before the guest started - while chroot, which never goes through this
     * method, kept working.
     */
    @Test fun theProotCommandStartsWithProotAndEndsWithTheGuest() {
        context = RuntimeEnvironment.getApplication()
        sessionRoot = File(context.cacheDir, "session").apply { mkdirs() }
        runtimeDir = File(context.cacheDir, "wayland-rt").apply { mkdirs() }
        // prootPrefix() reads uname for the guest's kernel identity, which Robolectric leaves null.
        val guest = listOf("/usr/local/bin/bannerlator-session", "steam")
        val command = LinuxRuntime.command(
            context, sessionRoot, runtimeDir, null, listOf("/host/games:/games"), guest,
        )

        assertEquals(
            "the first argument must be the program to run",
            LinuxRuntime.prootBinary(context).path,
            command.first(),
        )
        assertEquals(guest, command.takeLast(guest.size))
        assertTrue("no binds were passed", argumentsOf(command, "-b").isNotEmpty())
        // Every -b takes its spec, so the list never ends on a dangling flag.
        val lastFlag = command.indexOfLast { it.startsWith("-") }
        assertTrue("-b is missing its spec", lastFlag < command.size - 1)
        assertFalse("the binds must not be returned as the command", command.contains("--kill-on-exit") && command.first() == "--kill-on-exit")
    }

    private fun argumentsOf(command: List<String>, flag: String): List<String> {
        val values = ArrayList<String>()
        for (index in command.indices) {
            if (command[index] == flag && index + 1 < command.size) values.add(command[index + 1])
        }
        return values
    }
}
