package com.droiddeck.launcher.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

class AgentStorageTest {
    @Test fun removingAFolderKeepsWhatItsLinksPointAt() {
        val base = Files.createTempDirectory("agent-storage")
        val outside = Files.createDirectories(base.resolve("sdcard"))
        Files.write(outside.resolve("save.dat"), ByteArray(100))
        val item = Files.createDirectories(base.resolve("rootfs/android-sdk"))
        Files.write(item.resolve("a.jar"), ByteArray(10))
        val linked = runCatching { Files.createSymbolicLink(item.resolve("saves"), outside) }.isSuccess
        assumeTrue("symbolic links need privileges on this OS", linked)

        assertTrue(AgentStorage.size(item) < 100)
        AgentStorage.delete(item)
        assertFalse(Files.exists(item))
        assertTrue(Files.exists(outside.resolve("save.dat")))
    }

    @Test fun aMissingPathHasNoSize() {
        assertEquals(0L, AgentStorage.size(Files.createTempDirectory("agent-storage").resolve("gone")))
    }
}
