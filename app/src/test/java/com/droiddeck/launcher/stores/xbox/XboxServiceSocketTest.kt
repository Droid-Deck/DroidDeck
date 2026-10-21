package com.droiddeck.launcher.stores.xbox

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class XboxServiceSocketTest {
    @Test fun serviceSocketsFitUnixAddressesForEveryPackageAndRemainDistinct() {
        val variants = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, "tools/release/variants.txt") }.first { it.isFile }
        val packages = variants.readLines().filter { it.isNotBlank() && !it.startsWith('#') }
            .map { it.trim().split(Regex("\\s+"))[1] }
        for (pkg in packages) for (user in listOf(0, 99999)) {
            val files = File("/data/user/$user/$pkg/files")
            val first = XboxRuntime.newServiceSocket(files)
            val second = XboxRuntime.newServiceSocket(files)
            assertTrue(first.path, first.path.toByteArray(Charsets.UTF_8).size < 108)
            assertNotEquals(first, second)
        }
    }
}
