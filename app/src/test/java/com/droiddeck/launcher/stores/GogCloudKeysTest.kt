package com.droiddeck.launcher.stores

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GogCloudKeysTest {
    @Test fun defaultLocationIsStrippedLikeAnyNamedLocation() {
        val files = listOf(CloudFile("__default/savegame0.sav", 1L), CloudFile("saves/slot0.sav", 2L))
        val (chosen, relative) = gogRelativeNames(files, "__default")
        assertEquals("__default", chosen)
        assertEquals(listOf("savegame0.sav"), relative.map { it.name })
    }

    @Test fun gzipGogPutMatchesPythonGzipCompressLevel6Mtime0() {
        val raw = "savegame0".toByteArray()
        val expected = "1f8b08000000000000032b4e2c4b4d4fcc4d35000065fc333209000000".hex()
        assertArrayEquals(expected, gzipGogPut(raw))
        assertArrayEquals(raw, gunzip(expected))
    }

    @Test fun contentEncodingGzipIsPeeledOnce() {
        val raw = "savegame0".toByteArray()
        val gz = ByteArrayOutputStream().also { GZIPOutputStream(it).use { z -> z.write(raw) } }.toByteArray()
        assertArrayEquals(raw, gogDecodeCloudBody(gz, "gzip"))
        assertArrayEquals(raw, gogDecodeCloudBody(raw, "gzip"))
    }

    @Test fun corruptDeclaredGzipIsRejected() {
        val corrupt = gzipGogPut("savegame0".toByteArray()).dropLast(2).toByteArray()
        assertTrue(isGzip(corrupt))
        assertTrue(runCatching { gogDecodeCloudBody(corrupt, "gzip") }.isFailure)
    }

    @Test fun isoDateHonorsOffsetAndFractionalSeconds() {
        val expected = java.time.Instant.parse("2024-01-02T01:04:05.123Z").toEpochMilli()
        assertEquals(expected, isoDate("2024-01-02T03:04:05.123+02:00"))
        assertEquals(expected, isoDate("2024-01-02T01:04:05.123"))
    }

    @Test fun gogPutWrappedBodyWithoutEncodingIsPeeled() {
        val raw = "savegame0".toByteArray()
        assertArrayEquals(raw, gogDecodeCloudBody(gzipGogPut(raw), null))
        assertArrayEquals(raw, gogDecodeCloudBody(raw, null))
    }

    @Test fun gzipThatIsTheSaveIsLeftIntact() {
        val raw = "savegame0".toByteArray()
        val gameGz = ByteArrayOutputStream().also { GZIPOutputStream(it).use { z -> z.write(raw) } }.toByteArray()
        assertTrue(isGzip(gameGz))
        assertFalse(gzipGogPut(raw).contentEquals(gameGz))
        assertArrayEquals(gameGz, gogDecodeCloudBody(gameGz, null))
    }
}

private fun String.hex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
