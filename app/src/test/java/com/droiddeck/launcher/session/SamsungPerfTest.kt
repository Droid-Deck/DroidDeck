package com.droiddeck.launcher.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SamsungPerfTest {
    @get:Rule val temp = TemporaryFolder()

    private val sdkClasses = listOf("com/samsung/sdk/sperf/SPerf.class", "com/samsung/sdk/sperf/PerformanceManager.class")

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            entries.forEach { (name, data) -> out.putNextEntry(ZipEntry(name)); out.write(data); out.closeEntry() }
        }
        return bytes.toByteArray()
    }

    private fun file(name: String, data: ByteArray) = File(temp.root, name).apply { writeBytes(data) }

    @Test fun `the SDK jar is taken from Samsung's zip, not the sample's copy`() {
        val sdkJar = zip(sdkClasses.associateWith { byteArrayOf(1) })
        val sampleJar = zip(mapOf("com/samsung/sdk/sperf/SPerf.class" to byteArrayOf(2)))
        val download = file("PerformanceSDK_v1.0.0.zip", zip(mapOf(
            "Samples/Performance/lib/perfsdk.jar" to sampleJar,
            "Libs/perfsdk-v1.0.0.jar" to sdkJar,
        )))
        val target = File(temp.root, "out.jar")
        assertTrue(SamsungPerf.extractJar(download, target))
        assertArrayEquals(sdkJar, target.readBytes())
    }

    @Test fun `the jar itself is taken as it is`() {
        val jar = file("perfsdk-v1.0.0.jar", zip(sdkClasses.associateWith { byteArrayOf(1) }))
        val target = File(temp.root, "out.jar")
        assertTrue(SamsungPerf.extractJar(jar, target))
        assertArrayEquals(jar.readBytes(), target.readBytes())
    }

    @Test fun `other files are refused`() {
        val target = File(temp.root, "out.jar")
        assertFalse(SamsungPerf.extractJar(file("Samples.zip", zip(mapOf("a.txt" to byteArrayOf(1)))), target))
        assertFalse(SamsungPerf.extractJar(file("notes.zip", byteArrayOf(1, 2, 3)), target))
    }
}
