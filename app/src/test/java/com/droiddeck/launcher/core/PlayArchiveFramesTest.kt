package com.droiddeck.launcher.core

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PlayArchiveFramesTest {
    private fun tar(name: String, bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        TarArchiveOutputStream(output).use { archive ->
            archive.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            val entry = TarArchiveEntry(name).apply { size = bytes.size.toLong(); mode = 493 }
            archive.putArchiveEntry(entry)
            archive.write(bytes)
            archive.closeArchiveEntry()
        }
        return output.toByteArray()
    }

    @Test fun runtimeDecoderReadsCodeAndDataFramesAsOneCompleteArchive() {
        val code = byteArrayOf(127, 69, 76, 70)
        val data = byteArrayOf(0, 1, 0, 0, 4, 5, 6)
        val dataName = "usr/share/fonts/" + "directory/".repeat(12) + "font.ttf"
        val joined = Zstd.compress(tar("usr/bin/program", code).copyOf(1024), 19) +
            Zstd.compress(tar(dataName, data), 19)
        val contents = linkedMapOf<String, ByteArray>()
        ZstdInputStream(ByteArrayInputStream(joined)).use { decoded ->
            TarArchiveInputStream(decoded).use { archive ->
                while (true) {
                    val entry = archive.nextEntry ?: break
                    assertEquals(493, entry.mode)
                    contents[entry.name] = archive.readBytes()
                }
            }
        }
        assertEquals(listOf("usr/bin/program", dataName), contents.keys.toList())
        assertArrayEquals(code, contents["usr/bin/program"])
        assertArrayEquals(data, contents[dataName])
    }
}
