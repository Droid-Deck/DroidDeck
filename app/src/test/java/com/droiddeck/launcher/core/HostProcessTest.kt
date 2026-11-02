package com.droiddeck.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import com.droiddeck.launcher.core.DroidDeckTestRunner as RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 37], manifest = Config.NONE)
class HostProcessTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun childPidIsAvailableWithoutReflectiveAccessToJdkInternals() {
        val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
        val child = if (windows) ProcessBuilder("cmd.exe", "/c", "exit 0").start()
            else ProcessBuilder("/bin/sh", "-c", "exit 0").start()
        try {
            assertTrue(HostProcess.pidOf(child) > 0)
            assertEquals(0, child.waitFor())
        } finally {
            if (child.isAlive) child.destroyForcibly()
        }
    }

    @Test fun appendsBothOutputStreamsToTheExistingLog() {
        val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
        val log = temporary.newFile("session.log").apply { writeText("guest output\n") }
        val script = temporary.newFile(if (windows) "tracer.cmd" else "tracer.sh").apply {
            writeText(if (windows) "@echo off\r\necho tracer stdout\r\necho tracer stderr>&2\r\n"
                else "printf 'tracer stdout\\n'\nprintf 'tracer stderr\\n' >&2\n")
        }
        val finished = CountDownLatch(1)
        var exitCode = -1
        val shell = if (windows) "cmd.exe /c" else "/bin/sh"
        HostProcess.start("$shell ${script.name}", null, temporary.root, {
            exitCode = it
            finished.countDown()
        }, null, log)
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertEquals(0, exitCode)
        assertEquals(listOf("guest output", "tracer stdout", "tracer stderr"), log.readLines())
    }
}
