package com.droiddeck.launcher.runtime

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The two ways into the guest - proot's `-b` and the rooted runner's `--bind` - take the same list,
 * and the runner's command line has to carry it through unchanged. A rooted session that mounted
 * less than an unrooted one would be a session that half works, which is the failure this pins.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
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

    private fun argumentsOf(command: List<String>, flag: String): List<String> {
        val values = ArrayList<String>()
        for (index in command.indices) {
            if (command[index] == flag && index + 1 < command.size) values.add(command[index + 1])
        }
        return values
    }
}
