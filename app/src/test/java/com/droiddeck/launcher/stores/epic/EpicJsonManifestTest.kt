package com.droiddeck.launcher.stores.epic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EpicJsonManifestTest {
    @Test
    fun blobNumbersAreDecimalBytesLeastSignificantFirst() {
        assertEquals(1L, EpicDownloadManager.blobToNum("001000000000"))
        assertEquals(0x100L, EpicDownloadManager.blobToNum("000001000000"))
        assertEquals(0x0807_0605_0403_0201L, EpicDownloadManager.blobToNum("001002003004005006007008"))
        assertEquals(-1L, EpicDownloadManager.blobToNum("255255255255255255255255"))
        assertEquals(7L, EpicDownloadManager.blobToNum("7"))
        assertEquals(0L, EpicDownloadManager.blobToNum(""))
    }

    @Test
    fun aBlobIsDigitsOnly() {
        assertThrows(NumberFormatException::class.java) { EpicDownloadManager.blobToNum("0x1") }
        assertThrows(NumberFormatException::class.java) { EpicDownloadManager.blobToNum("-05") }
    }

    @Test
    fun jsonManifestUsesBlobs() {
        val json = """{
          "ManifestFileVersion": "006000000000",
          "LaunchExeString": "Game\\Binaries\\Game.exe",
          "ChunkHashList": { "0000000100000002000000030000000F": "001002003004005006007008" },
          "DataGroupList": { "0000000100000002000000030000000F": "007" },
          "ChunkFilesizeList": { "0000000100000002000000030000000F": "001002000000000000000000" },
          "FileManifestList": [
            { "Filename": "a.bin",
              "FileChunkParts": [ { "Guid": "0000000100000002000000030000000F", "Offset": "001000000000", "Size": "000000016000" } ] }
          ]
        }"""
        val m = EpicDownloadManager.parseManifest(json.toByteArray())
        assertNotNull(m)
        assertEquals("ChunksV3", m.chunkDir)
        assertEquals("Game/Binaries/Game.exe", m.launchExe)
        val c = m.uniqueChunks.single()
        assertEquals(0x0201L, c.fileSize)
        assertEquals("ChunksV3/07/0807060504030201_0000000100000002000000030000000F.chunk", c.getPath(m.chunkDir))
        val part = m.files.single().parts.single()
        assertEquals(1, part.offset)
        assertEquals(1_048_576, part.size)
    }
}
