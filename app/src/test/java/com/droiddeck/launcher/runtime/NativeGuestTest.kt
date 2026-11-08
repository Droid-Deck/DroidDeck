package com.droiddeck.launcher.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeGuestTest {
    private fun rootWithLoader(): File = Files.createTempDirectory("native-root").toFile().also {
        File(it, "usr/lib").mkdirs()
        File(it, "usr/lib/ld-linux-aarch64.so.1").writeText("")
    }

    @Test
    fun commandRunsTheProgramThroughTheRootfsLoader() {
        val root = rootWithLoader()
        val command = NativeGuest.command(root, "/usr/bin/env", listOf("-i", "A=1"))
        assertEquals(File(root, "usr/lib/ld-linux-aarch64.so.1").path, command[0])
        assertEquals(listOf("--argv0", "env", File(root, "usr/bin/env").path, "-i", "A=1"), command.drop(1))
    }

    @Test
    fun mountsWithoutADestinationMapToThemselves() {
        val root = rootWithLoader()
        val env = NativeGuest.sessionEnvironment(root, listOf("/dev", "/a:/b"))
        assertEquals("/dev:/dev|/a:/b", env["DDNATV_BINDS"])
        assertEquals(root.path, env["DDNATV_ROOT"])
        assertTrue(env.getValue("LD_PRELOAD").replace(File.separatorChar, '/').endsWith("usr/lib/libddnatv-hook.so"))
    }
}
