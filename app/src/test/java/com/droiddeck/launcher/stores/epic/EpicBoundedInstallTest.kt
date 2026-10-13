package com.droiddeck.launcher.stores.epic

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EpicBoundedInstallTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var server: ServerSocket
    private lateinit var worker: Thread
    private lateinit var manifest: EpicDownloadManager.Manifest
    private lateinit var root: File
    private val requests = AtomicInteger()
    private val reject = AtomicBoolean()
    private var bytes = ByteArray(256 * 1024) { (it % 251).toByte() }

    @Before fun setUp() {
        root = temp.newFolder("game")
        server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        worker = Thread {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: SocketException) {
                    if (server.isClosed) break else throw e
                }
                socket.use {
                    it.soTimeout = 5000
                    val reader = it.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    requests.incrementAndGet()
                    val out = it.getOutputStream()
                    if (reject.get()) {
                        out.write("HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    } else {
                        val header = ByteBuffer.allocate(41).order(ByteOrder.LITTLE_ENDIAN)
                            .putInt(0xB1FE3AA2.toInt()).putInt(1).putInt(41).putInt(bytes.size)
                            .put(ByteArray(24)).put(0).array()
                        out.write("HTTP/1.1 200 OK\r\nContent-Length: ${header.size + bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(header)
                        out.write(bytes)
                    }
                    out.flush()
                }
            }
        }.apply { start() }
        val chunk = EpicDownloadManager.ChunkInfo().apply {
            guid = intArrayOf(1, 0, 0, 0)
            windowSize = bytes.size
            fileSize = bytes.size.toLong()
            sha1 = sha(bytes)
        }
        manifest = EpicDownloadManager.Manifest().apply {
            chunkDir = "chunks"
            uniqueChunks = arrayListOf(chunk)
            cdnUrls = listOf(EpicDownloadManager.CdnUrl("http://127.0.0.1:${server.localPort}", "", ""))
            files = arrayListOf(gameFile("game.bin", 0, bytes.size / 2), gameFile("game.bin.part", bytes.size / 2, bytes.size / 2))
        }
    }

    @After fun tearDown() { server.close(); worker.join(6000); assertFalse(worker.isAlive) }

    private fun sha(data: ByteArray) = MessageDigest.getInstance("SHA-1").digest(data)

    private fun gameFile(name: String, offset: Int, size: Int) = EpicDownloadManager.FileInfo().apply {
        filename = name
        sha1 = sha(bytes.copyOfRange(offset, offset + size))
        parts.add(EpicDownloadManager.ChunkPart().apply {
            guid = intArrayOf(1, 0, 0, 0)
            this.offset = offset
            this.size = size
        })
    }

    private fun install(cancel: AtomicBoolean = AtomicBoolean(), callback: EpicDownloadManager.Callback =
        EpicDownloadManager.Callback { _, _ -> }, cache: String = "") =
        EpicDownloadManager.installManifest(manifest, root.path, cache, null, cancel, callback)

    @Test fun installsSharedChunksAndPreservesLegitimatePartFilename() {
        assertNotNull(install())
        assertArrayEquals(bytes.copyOfRange(0, bytes.size / 2), File(root, "game.bin").readBytes())
        assertArrayEquals(bytes.copyOfRange(bytes.size / 2, bytes.size), File(root, "game.bin.part").readBytes())
        assertEquals(1, requests.get())
        assertFalse(File(root, ".chunks").exists())
        assertNotNull(install())
        assertEquals("Verified files must not be downloaded again", 1, requests.get())
    }

    private fun oversizedUpdate() {
        val file = manifest.files[0]
        val part = file.parts[0]
        file.parts.clear()
        repeat(100_000) {
            file.parts.add(EpicDownloadManager.ChunkPart().apply {
                guid = part.guid
                size = Int.MAX_VALUE
            })
        }
    }

    @Test fun insufficientInstallSpacePreservesExistingFilesOnBothCacheLayouts() {
        val oldGame = byteArrayOf(9, 8, 7)
        val oldPart = byteArrayOf(6, 5, 4)
        File(root, "game.bin").writeBytes(oldGame)
        File(root, "game.bin.part").writeBytes(oldPart)
        oversizedUpdate()
        assertTrue(manifest.files[0].fileSize() > root.usableSpace)

        for (cache in listOf("", temp.newFolder("scratch-rejected").path)) {
            val error = assertThrows(EpicDownloadManager.InstallException::class.java) { install(cache = cache) }
            assertTrue(error.message.orEmpty().contains("Not enough free space"))
            assertArrayEquals(oldGame, File(root, "game.bin").readBytes())
            assertArrayEquals(oldPart, File(root, "game.bin.part").readBytes())
        }
        assertEquals(0, requests.get())
    }

    @Test fun acceptedUpdateReplacesOutdatedFilesWithVerifiedOutputs() {
        File(root, "game.bin").writeBytes(byteArrayOf(9, 8, 7))
        File(root, "game.bin.part").writeBytes(byteArrayOf(6, 5, 4))
        assertNotNull(install())
        assertArrayEquals(bytes.copyOfRange(0, bytes.size / 2), File(root, "game.bin").readBytes())
        assertArrayEquals(bytes.copyOfRange(bytes.size / 2, bytes.size), File(root, "game.bin.part").readBytes())
    }

    @Test fun pauseKeepsCompletedFilesAndResumeReusesSharedCache() {
        val cancel = AtomicBoolean()
        val callback = object : EpicDownloadManager.Callback {
            override fun onProgress(message: String, pct: Int) {}
            override fun onStage(stage: String, done: Long, total: Long, items: Int, itemsTotal: Int) {
                if (stage == "stream" && items == 1) cancel.set(true)
            }
        }
        assertNull(install(cancel, callback))
        assertTrue(File(root, "game.bin").exists())
        assertFalse(File(root, "game.bin.part").exists())
        assertEquals(1, requests.get())
        assertNotNull(install())
        assertEquals(1, requests.get())
    }

    @Test fun cancellationDuringChunkDeliveryRemovesIncompleteOutput() {
        val cancel = AtomicBoolean()
        val callback = object : EpicDownloadManager.Callback {
            override fun onProgress(message: String, pct: Int) {
                if (message.startsWith("Downloading chunks (")) cancel.set(true)
            }
        }
        assertNull(install(cancel, callback))
        assertFalse(File(root, "game.bin").exists())
        assertNotNull(install())
        assertArrayEquals(bytes.copyOfRange(0, bytes.size / 2), File(root, "game.bin").readBytes())
    }

    @Test fun fileHashFailureIsExplicitAndRetryRepairsIt() {
        val expected = manifest.files[0].sha1
        manifest.files[0].sha1 = ByteArray(20) { 1 }
        val error = assertThrows(EpicDownloadManager.InstallException::class.java) { install() }
        assertTrue(error.message.orEmpty().contains("SHA-1 mismatch"))
        assertFalse(File(root, "game.bin").exists())
        manifest.files[0].sha1 = expected
        assertNotNull(install())
    }

    @Test fun corruptCacheIsRefetchedAndScratchCacheIsRemovedOnSuccess() {
        val cache = temp.newFolder("scratch")
        File(cache, manifest.uniqueChunks[0].guidStr()).writeBytes(ByteArray(bytes.size))
        assertNotNull(install(cache = cache.path))
        assertEquals(1, requests.get())
        assertFalse(cache.exists())
    }

    @Test fun networkFailureLeavesNoCompletedFileAndCanBeRetried() {
        reject.set(true)
        assertThrows(EpicDownloadManager.InstallException::class.java) { install() }
        assertFalse(File(root, "game.bin").exists())
        reject.set(false)
        assertNotNull(install())
    }

    @Test fun boundedProgressNeverReentersDownloadAfterEnteringInstall() {
        val events = mutableListOf<String>()
        val callback = object : EpicDownloadManager.Callback {
            override fun onProgress(message: String, pct: Int) {
                if (message.startsWith("Downloading chunks")) events.add("download")
                if (message.startsWith("Writing")) events.add("install")
            }
            override fun onBytes(done: Long, total: Long, speedBps: Long) { events.add("download") }
            override fun onStage(stage: String, done: Long, total: Long, items: Int, itemsTotal: Int) {
                if (stage == "install") {
                    assertEquals(total, done)
                    assertEquals(itemsTotal, items)
                    events.add("install")
                }
            }
        }
        assertNotNull(install(callback = callback))
        val firstInstall = events.indexOf("install")
        assertTrue(firstInstall > 0)
        assertFalse(events.drop(firstInstall).contains("download"))
    }

    @Test fun sharedChunksDoNotCompleteProgressBeforeTheRemainingFilesAreWritten() {
        val samples = mutableListOf<Pair<Long, Long>>()
        val callback = object : EpicDownloadManager.Callback {
            override fun onProgress(message: String, pct: Int) {}
            override fun onBytes(done: Long, total: Long, speedBps: Long) { samples.add(done to total) }
            override fun onStage(stage: String, done: Long, total: Long, items: Int, itemsTotal: Int) {
                if (stage == "stream" && items < itemsTotal) assertTrue(samples.last().first < samples.last().second)
            }
        }
        assertNotNull(install(callback = callback))
        assertEquals(listOf(0L, bytes.size / 2L, bytes.size.toLong()), samples.map { it.first })
        assertTrue(samples.all { it.second == bytes.size.toLong() })
    }

    @Test fun evictedSharedChunksAdvanceWriteProgressOnEveryRefetchWithoutChangingStages() {
        bytes = ByteArray((EpicDownloadManager.MAX_RETAINED_CHUNK_CACHE_BYTES + 1024).toInt()) { (it % 251).toByte() }
        manifest.uniqueChunks.single().apply {
            windowSize = bytes.size
            fileSize = bytes.size.toLong()
            sha1 = sha(bytes)
        }
        manifest.files = (0..2).map { gameFile("part-$it.bin", it * 1024, 1024) }
        val chunk = manifest.uniqueChunks.single()
        val estimatedPeak = EpicDownloadManager.estimatePeakAdditionalBytes(
            manifest.files,
            mapOf(chunk.guidStr() to chunk),
            emptyMap(),
            true,
        )
        assertEquals(EpicDownloadManager.MAX_RETAINED_CHUNK_CACHE_BYTES + 4 * 1024L, estimatedPeak)
        val samples = mutableListOf<Long>()
        val stages = mutableListOf<String>()
        val callback = object : EpicDownloadManager.Callback {
            override fun onProgress(message: String, pct: Int) {}
            override fun onBytes(done: Long, total: Long, speedBps: Long) {
                assertEquals(3072L, total)
                assertFalse(stages.contains("install"))
                samples.add(done)
            }
            override fun onStage(stage: String, done: Long, total: Long, items: Int, itemsTotal: Int) {
                stages.add(stage)
                if (stage == "stream" && items < itemsTotal) assertTrue(samples.last() < total)
            }
        }
        assertNotNull(install(callback = callback))
        assertEquals(3, requests.get())
        assertEquals(listOf(0L, 1024L, 2048L, 3072L), samples)
        assertEquals("install", stages.last())
        repeat(3) { assertArrayEquals(bytes.copyOfRange(it * 1024, (it + 1) * 1024), File(root, "part-$it.bin").readBytes()) }
    }

    @Test fun hogwartsSizedSequentialPlanFitsAvailableSpaceWithoutFullChunkCache() {
        val gib = 1024L * 1024 * 1024
        val file = EpicDownloadManager.FileInfo()
        val chunks = LinkedHashMap<String, EpicDownloadManager.ChunkInfo>()
        repeat(72) { index ->
            val chunk = EpicDownloadManager.ChunkInfo().apply {
                guid = intArrayOf(index + 1, 0, 0, 0)
                windowSize = gib.toInt()
            }
            chunks[chunk.guidStr()] = chunk
            file.parts.add(EpicDownloadManager.ChunkPart().apply {
                guid = chunk.guid
                size = chunk.windowSize
            })
        }
        val peak = EpicDownloadManager.estimatePeakAdditionalBytes(listOf(file), chunks, emptyMap(), true)
        assertEquals(73 * gib, peak)
        assertTrue(peak < 96 * gib)
    }
}
