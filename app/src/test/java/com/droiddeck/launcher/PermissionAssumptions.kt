package com.droiddeck.launcher

import java.nio.file.Files
import org.junit.Assume.assumeTrue

/** Root ignores file mode bits, so tests that rely on a denied read or write cannot run as root or without POSIX permissions. */
fun assumeFilePermissionsEnforced() {
    val probe = Files.createTempDirectory("permission-probe").toFile()
    try {
        assumeTrue("no POSIX file permissions", Files.getFileStore(probe.toPath()).supportsFileAttributeView("posix"))
        assumeTrue("file permissions are not enforced for this user", probe.setReadable(false, false) && !probe.canRead())
    } finally {
        probe.setReadable(true, false)
        probe.delete()
    }
}
