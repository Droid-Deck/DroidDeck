package com.droiddeck.launcher.stores.epic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EpicJsonManifestTest {
    @Test
    fun boundedInstallPeakCountsOnlyOneLiveChunkForSequentialChunks() {
        val first = chunk(1)
        val second = chunk(2)
        val file = file(part(1, 0, 100), part(2, 0, 100))
        val chunks = mapOf(first.guidStr() to first, second.guidStr() to second)

        assertEquals(300L, EpicDownloadManager.estimatePeakAdditionalBytes(listOf(file), chunks, emptyMap(), true))
        assertEquals(100L, EpicDownloadManager.estimatePeakAdditionalBytes(listOf(file), chunks, emptyMap(), false))
    }

    @Test
    fun sharedCachedChunkIsReclaimedOnlyAfterItsLastPart() {
        val shared = chunk(1)
        val files = listOf(file(part(1, 0, 60)), file(part(1, 60, 40)))

        assertEquals(
            100L,
            EpicDownloadManager.estimatePeakAdditionalBytes(
                files, mapOf(shared.guidStr() to shared), mapOf(shared.guidStr() to 100L), true,
            ),
        )
        assertEquals(
            200L,
            EpicDownloadManager.estimatePeakAdditionalBytes(
                files, mapOf(shared.guidStr() to shared), emptyMap(), true,
            ),
        )
    }

    @Test
    fun distantSharedChunksAreEvictedAndRefetchedWithinTheCacheCeiling() {
        val first = chunk(1).apply { windowSize = 40 * 1024 * 1024 }
        val second = chunk(2).apply { windowSize = 40 * 1024 * 1024 }
        val files = listOf(
            file(part(1, 0, 40 * 1024 * 1024)),
            file(part(2, 0, 40 * 1024 * 1024)),
            file(part(1, 0, 40 * 1024 * 1024)),
            file(part(2, 0, 40 * 1024 * 1024)),
        )
        val chunks = mapOf(first.guidStr() to first, second.guidStr() to second)

        val gamePeak = EpicDownloadManager.estimatePeakAdditionalBytes(files, chunks, emptyMap(), true)
        val cachePeak = EpicDownloadManager.estimatePeakAdditionalBytes(files, chunks, emptyMap(), false)

        assertEquals(200L * 1024 * 1024, gamePeak)
        assertTrue(cachePeak <= EpicDownloadManager.MAX_RETAINED_CHUNK_CACHE_BYTES + 40L * 1024 * 1024)
    }

    @Test
    fun resumeCacheDropsCorruptAndUnneededEntriesButKeepsVerifiedChunks() {
        val dir = java.io.File(System.getProperty("user.dir"), "build/epic-cache-test-${System.nanoTime()}")
        assertTrue(dir.mkdirs())
        try {
            val bytes = ByteArray(100) { (it % 251).toByte() }
            val expectedSha = java.security.MessageDigest.getInstance("SHA-1").digest(bytes)
            val valid = chunk(1).apply { sha1 = expectedSha }
            val corrupt = chunk(2).apply { sha1 = expectedSha }
            java.io.File(dir, valid.guidStr()).writeBytes(bytes)
            java.io.File(dir, corrupt.guidStr()).writeBytes(ByteArray(100) { 1 })
            java.io.File(dir, "orphan").writeBytes(bytes)

            val retained = EpicDownloadManager.prepareBoundedCache(dir, listOf(valid, corrupt))

            assertEquals(mapOf(valid.guidStr() to 100L), retained)
            assertTrue(java.io.File(dir, valid.guidStr()).exists())
            assertFalse(java.io.File(dir, corrupt.guidStr()).exists())
            assertFalse(java.io.File(dir, "orphan").exists())
        } finally {
            EpicDownloadManager.deleteDir(dir)
        }
    }

    @Test
    fun referenceCountsIncludeRepeatedPartsSoCancelledFilesCanBeReplanned() {
        val pending = listOf(file(part(1, 0, 20), part(1, 20, 20)), file(part(1, 40, 60)))

        assertEquals(3, EpicDownloadManager.chunkReferences(pending)["00000001000000000000000000000000"])
    }

    private fun chunk(id: Int) = EpicDownloadManager.ChunkInfo().apply {
        guid = intArrayOf(id, 0, 0, 0)
        windowSize = 100
        fileSize = 100
    }

    private fun part(id: Int, offset: Int, size: Int) = EpicDownloadManager.ChunkPart().apply {
        guid = intArrayOf(id, 0, 0, 0)
        this.offset = offset
        this.size = size
    }

    private fun file(vararg parts: EpicDownloadManager.ChunkPart) = EpicDownloadManager.FileInfo().apply {
        filename = "test.bin"
        this.parts.addAll(parts)
    }

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
