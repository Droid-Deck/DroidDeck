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
    @Test fun defaultNamespaceIsPrefixedAndStripped() {
        assertEquals("__default/savegame0.sav", gogCloudKey("savegame0.sav", "__default"))
        assertEquals("savegame0.sav", gogLocalKey("__default/savegame0.sav", "__default"))
        assertNull(gogLocalKey("saves/slot0.sav", "__default"))
        assertEquals("slot0.sav", gogCloudKey("slot0.sav", ""))
        assertEquals("slot0.sav", gogLocalKey("slot0.sav", ""))
        assertEquals("__default/savegame0.sav", gogLocalKey("__default/savegame0.sav", ""))
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
