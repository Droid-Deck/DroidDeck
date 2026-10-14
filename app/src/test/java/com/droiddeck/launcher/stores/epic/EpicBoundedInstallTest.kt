package com.droiddeck.launcher.stores.epic

import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.StoreInstalls
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.net.Socket
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs

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
    private val rejectGuid = AtomicInteger()
    private val handlers = Executors.newCachedThreadPool()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private var requestBarrier: CountDownLatch? = null
    private var deliveryGate: CountDownLatch? = null
    private var bytes = ByteArray(256 * 1024) { (it % 251).toByte() }

    @Before fun setUp() {
        root = temp.newFolder("game")
        ShadowStatFs.registerStats(root.path, 1_000_000_000, 1_000_000_000, 1_000_000_000)
        server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        worker = Thread {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: SocketException) {
                    if (server.isClosed) break else throw e
                }
                sockets.add(socket)
                handlers.submit {
                    try {
                        socket.use {
                            it.soTimeout = 5000
                            val reader = it.getInputStream().bufferedReader()
                            val request = reader.readLine()
                            while (!reader.readLine().isNullOrEmpty()) { }
                            requests.incrementAndGet()
                            requestBarrier?.let { barrier ->
                                barrier.countDown()
                                check(barrier.await(5, TimeUnit.SECONDS)) { "Requests did not overlap" }
                            }
                            val out = it.getOutputStream()
                            val rejectedGuid = rejectGuid.get()
                            if (reject.get() || (rejectedGuid != 0 && request.contains("_%08X".format(rejectedGuid)))) {
                                out.write("HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            } else {
                                val header = ByteBuffer.allocate(41).order(ByteOrder.LITTLE_ENDIAN)
                                    .putInt(0xB1FE3AA2.toInt()).putInt(1).putInt(41).putInt(bytes.size)
                                    .put(ByteArray(24)).put(0).array()
                                out.write("HTTP/1.1 200 OK\r\nContent-Length: ${header.size + bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                out.write(header)
                                out.flush()
                                deliveryGate?.await(5, TimeUnit.SECONDS)
                                out.write(bytes)
                            }
                            out.flush()
                        }
                    } catch (_: java.io.IOException) {
                        // Cancellation disconnects an owned request while this fixture serves it.
                    } finally { sockets.remove(socket) }
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

    @After fun tearDown() {
        deliveryGate?.countDown()
        server.close()
        worker.join(6000)
        sockets.forEach { it.close() }
        handlers.shutdownNow()
        assertTrue(handlers.awaitTermination(6, TimeUnit.SECONDS))
        assertFalse(worker.isAlive)
    }

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

    private fun oldInstall() {
        File(root, "game.bin").writeBytes(byteArrayOf(9, 8, 7))
        File(root, "game.bin.part").writeBytes(byteArrayOf(6, 5, 4))
        StoreGameSidecar(Store.EPIC, "game", "Game", "game.bin", installVersion = "old").write(root)
    }

    private fun assertOldInstallIntact() {
        assertArrayEquals(byteArrayOf(9, 8, 7), File(root, "game.bin").readBytes())
        assertArrayEquals(byteArrayOf(6, 5, 4), File(root, "game.bin.part").readBytes())
        assertEquals("old", StoreGameSidecar.read(root)?.installVersion)
        assertTrue(StoreGameSidecar.read(root)?.isInstalled == true)
        assertTrue(root.walk().none { it.name.endsWith(".stage") })
    }

    @Test fun networkFailureAfterAcceptedPreflightPreservesInstalledFilesAndRecord() {
        oldInstall()
        reject.set(true)
        assertThrows(EpicDownloadManager.InstallException::class.java) { install() }
        assertTrue(requests.get() > 0)
        assertOldInstallIntact()
    }

    @Test fun hashFailureAfterAcceptedPreflightPreservesInstalledFilesAndRecord() {
        oldInstall()
        manifest.files.first().sha1 = ByteArray(20) { 1 }
        assertThrows(EpicDownloadManager.InstallException::class.java) { install() }
        assertOldInstallIntact()
    }

    @Test fun cancellationWhileStagingPreservesInstalledFilesAndRecord() {
        oldInstall()
        val cancel = AtomicBoolean()
        assertNull(install(cancel, EpicDownloadManager.Callback { message, _ ->
            if (message.startsWith("Downloading chunks (")) cancel.set(true)
        }))
        assertOldInstallIntact()
    }

    @Test fun laterFailureRetainsVerifiedSwapsAndMarksMixedUpdateResumable() {
        oldInstall()
        manifest.files.last().sha1 = ByteArray(20) { 1 }
        assertThrows(EpicDownloadManager.InstallException::class.java) { install() }
        assertArrayEquals(bytes.copyOfRange(0, bytes.size / 2), File(root, "game.bin").readBytes())
        assertArrayEquals(byteArrayOf(6, 5, 4), File(root, "game.bin.part").readBytes())
        val record = StoreGameSidecar.read(root)!!
        assertEquals(StoreGameSidecar.STATE_UPDATING, record.state)
        assertFalse(record.isInstalled)
        assertEquals("", record.installVersion)
        StoreInstalls.begin(root, Store.EPIC, "game", "Game", null, null)
        assertEquals("game.bin", StoreGameSidecar.read(root)?.exe)
        assertEquals(StoreGameSidecar.STATE_UPDATING, StoreGameSidecar.read(root)?.state)
    }

    @Test fun laterNetworkFailurePreservesTheUnreplacedOldFile() {
        oldInstall()
        val secondChunk = EpicDownloadManager.ChunkInfo().apply {
            guid = intArrayOf(2, 0, 0, 0)
            windowSize = bytes.size
            fileSize = bytes.size.toLong()
            sha1 = sha(bytes)
        }
        manifest.uniqueChunks.add(secondChunk)
        manifest.files.last().parts.single().guid = secondChunk.guid
        // Prefetch may fail early, but replacement is still ordered by the writer.
        rejectGuid.set(2)
        assertThrows(EpicDownloadManager.InstallException::class.java) { install() }
        assertArrayEquals(bytes.copyOfRange(0, bytes.size / 2), File(root, "game.bin").readBytes())
        assertArrayEquals(byteArrayOf(6, 5, 4), File(root, "game.bin.part").readBytes())
        assertEquals(StoreGameSidecar.STATE_UPDATING, StoreGameSidecar.read(root)?.state)
        assertTrue(root.walk().none { it.name.endsWith(".stage") })
    }

    @Test fun cancellationAfterFirstSwapLeavesAResumableMixedUpdate() {
        oldInstall()
        val cancel = AtomicBoolean()
        val callback = object : EpicDownloadManager.Callback {
            override fun onProgress(message: String, pct: Int) {}
            override fun onStage(stage: String, done: Long, total: Long, items: Int, itemsTotal: Int) {
                if (stage == "stream" && items == 1) cancel.set(true)
            }
        }
        assertNull(install(cancel, callback))
        assertArrayEquals(bytes.copyOfRange(0, bytes.size / 2), File(root, "game.bin").readBytes())
        assertArrayEquals(byteArrayOf(6, 5, 4), File(root, "game.bin.part").readBytes())
        assertEquals(StoreGameSidecar.STATE_UPDATING, StoreGameSidecar.read(root)?.state)
        assertNotNull(install())
        assertArrayEquals(bytes.copyOfRange(bytes.size / 2, bytes.size), File(root, "game.bin.part").readBytes())
        // Registration, not an individual file swap, restores the installed state.
        assertFalse(StoreGameSidecar.read(root)!!.isInstalled)
    }

    @Test fun atomicRenameFailurePreservesDestinationAndCleansStagingWithoutDeleteFallback() {
        val destination = File(root, "game.bin").apply { mkdir() }
        File(destination, "old-content").writeText("keep")
        val error = assertThrows(EpicDownloadManager.InstallException::class.java) { install() }
        assertTrue(error.message.orEmpty().contains("atomically replace"))
        assertEquals("keep", File(destination, "old-content").readText())
        assertTrue(root.walk().none { it.name.endsWith(".stage") })
    }

    @Test fun safeStagingSpaceBoundaryRejectsWithoutReclaimingAllOldOutputs() {
        // The first staged half-file must coexist with the shared chunk and BOTH old outputs.
        val old = ByteArray(bytes.size / 2) { 9 }
        File(root, "game.bin").writeBytes(old)
        File(root, "game.bin.part").writeBytes(old)
        val peak = bytes.size + old.size
        ShadowStatFs.registerStats(root.path, 1024, peak / 4096 - 1, peak / 4096 - 1)
        assertThrows(EpicDownloadManager.InstallException::class.java) { install() }
        assertEquals(0, requests.get())
        assertArrayEquals(old, File(root, "game.bin").readBytes())
        assertArrayEquals(old, File(root, "game.bin.part").readBytes())
        ShadowStatFs.registerStats(root.path, 1024, peak / 4096, peak / 4096)
        assertNotNull(install())
    }

    @Test fun zeroSpaceRejectsPendingFilesWhileUnknownSpaceIsNotTreatedAsZero() {
        oldInstall()
        ShadowStatFs.registerStats(root.path, 1024, 0, 0)
        assertThrows(EpicDownloadManager.InstallException::class.java) { install() }
        assertOldInstallIntact()
        assertEquals(0, requests.get())
        assertTrue(com.droiddeck.launcher.stores.StoreInstallRoot.insufficientSpace(1, 0))
        assertFalse(com.droiddeck.launcher.stores.StoreInstallRoot.insufficientSpace(1, -1))
    }

    @Test fun zeroScratchSpaceRejectsBeforeFetchWithoutTouchingInstalledOutputs() {
        oldInstall()
        val cache = temp.newFolder("full-scratch")
        ShadowStatFs.registerStats(cache.path, 1024, 0, 0)
        val error = assertThrows(EpicDownloadManager.InstallException::class.java) { install(cache = cache.path) }
        assertTrue(error.message.orEmpty().contains("Not enough internal space"))
        assertOldInstallIntact()
        assertEquals(0, requests.get())
    }

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
            if (cache.isNotEmpty()) ShadowStatFs.registerStats(cache, 1_000_000_000, 1_000_000_000, 1_000_000_000)
            val error = assertThrows(EpicDownloadManager.InstallException::class.java) { install(cache = cache) }
            assertTrue(error.message.orEmpty().contains("Not enough free space"))
            assertArrayEquals(oldGame, File(root, "game.bin").readBytes())
            assertArrayEquals(oldPart, File(root, "game.bin.part").readBytes())
        }
        assertEquals(0, requests.get())
    }

    @Test fun acceptedUpdateReplacesOutdatedFilesWithVerifiedOutputs() {
        oldInstall()
        assertNotNull(install())
        assertArrayEquals(bytes.copyOfRange(0, bytes.size / 2), File(root, "game.bin").readBytes())
        assertArrayEquals(bytes.copyOfRange(bytes.size / 2, bytes.size), File(root, "game.bin.part").readBytes())
        assertEquals(StoreGameSidecar.STATE_UPDATING, StoreGameSidecar.read(root)?.state)
        assertTrue(root.walk().none { it.name.endsWith(".stage") })
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
        ShadowStatFs.registerStats(cache.path, 1_000_000_000, 1_000_000_000, 1_000_000_000)
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

    @Test fun oversizedSharedChunkStaysPinnedAcrossPrefetchedFilesWithoutChangingStages() {
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
        assertEquals(1, requests.get())
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

    @Test fun plannersReclaimEachOldFileOnlyAfterItsOwnVerifiedSwap() {
        val chunks = manifest.uniqueChunks.associateBy { it.guidStr() }
        val half = bytes.size / 2L
        val old = mapOf("game.bin" to half, "game.bin.part" to 100 * half)
        assertEquals(bytes.size + half, EpicDownloadManager.estimatePeakAdditionalBytes(manifest.files, chunks, emptyMap(), true, old))
        assertEquals(half, EpicDownloadManager.estimateStagedFilePeak(manifest.files, old))
        assertEquals(2 * half, EpicDownloadManager.estimateStagedFilePeak(manifest.files, emptyMap()))
        assertEquals(bytes.size.toLong(), EpicDownloadManager.estimatePeakAdditionalBytes(manifest.files, chunks, emptyMap(), false, old))
    }

    private fun distinctFiles(count: Int) {
        val template = manifest.uniqueChunks.single()
        manifest.uniqueChunks = (1..count).map { id ->
            EpicDownloadManager.ChunkInfo().apply {
                guid = intArrayOf(id, 0, 0, 0)
                windowSize = template.windowSize
                fileSize = template.fileSize
                sha1 = template.sha1
            }
        }
        manifest.files = manifest.uniqueChunks.mapIndexed { index, chunk ->
            gameFile("file-$index.bin", 0, bytes.size).apply { parts.single().guid = chunk.guid }
        }
    }

    @Test fun prefetchOverlapsEightSinglePartFilesButNeverFetchesTheWholeManifest() {
        distinctFiles(20)
        requestBarrier = CountDownLatch(8)
        val cancel = AtomicBoolean()
        var sawWindow = false
        assertNull(install(cancel, EpicDownloadManager.Callback { message, _ ->
            if (message.startsWith("Downloading chunks (")) {
                assertEquals(8, requests.get())
                val cacheBytes = File(root, ".chunks").listFiles()!!.sumOf { it.length() }
                assertTrue(cacheBytes <= 8L * bytes.size)
                sawWindow = true
                cancel.set(true)
            }
        }))
        assertTrue("An eight-request barrier cannot pass with a sequential downloader", sawWindow)
        assertEquals(8, requests.get())
        assertTrue(root.walk().none { it.name.endsWith(".stage") || it.name.endsWith(".part") })
    }

    @Test fun parallelWindowSpaceThresholdIsCheckedBeforeStartingAnyRequest() {
        distinctFiles(20)
        val cache = temp.newFolder("parallel-scratch")
        val peak = EpicDownloadManager.estimatePeakAdditionalBytes(
            manifest.files, manifest.uniqueChunks.associateBy { it.guidStr() }, emptyMap(), false,
        )
        assertEquals(8L * bytes.size, peak)
        ShadowStatFs.registerStats(cache.path, 1024, peak.toInt() / 4096 - 1, peak.toInt() / 4096 - 1)
        assertThrows(EpicDownloadManager.InstallException::class.java) { install(cache = cache.path) }
        assertEquals(0, requests.get())
        ShadowStatFs.registerStats(cache.path, 1024, peak.toInt() / 4096, peak.toInt() / 4096)
        requestBarrier = CountDownLatch(8)
        assertNotNull(install(cache = cache.path))
        assertEquals(20, requests.get())
        manifest.files.forEach { assertArrayEquals(bytes, File(root, it.filename).readBytes()) }
    }

    @Test fun cancellationDuringParallelNetworkReadsJoinsWorkersBeforeReturning() {
        distinctFiles(20)
        requestBarrier = CountDownLatch(8)
        deliveryGate = CountDownLatch(1)
        val cancel = AtomicBoolean()
        val outcome = java.util.concurrent.atomic.AtomicReference<Any?>()
        val installer = Thread {
            try { outcome.set(install(cancel)) } catch (e: Throwable) { outcome.set(e) }
        }.apply { start() }
        try {
            assertTrue(requestBarrier!!.await(5, TimeUnit.SECONDS))
            cancel.set(true)
            installer.join(10_000)
            assertFalse("Installer must disconnect and join all download workers", installer.isAlive)
            assertNull(outcome.get())
            val cache = File(root, ".chunks")
            val snapshot = cache.listFiles()?.associate { it.name to it.length() }.orEmpty()
            deliveryGate!!.countDown()
            Thread.sleep(150)
            assertEquals(snapshot, cache.listFiles()?.associate { it.name to it.length() }.orEmpty())
            assertTrue(snapshot.keys.none { it.endsWith(".part") })
            assertTrue(root.walk().none { it.name.endsWith(".stage") })
            assertEquals(8, requests.get())
        } finally {
            cancel.set(true)
            deliveryGate!!.countDown()
            installer.join(10_000)
        }
    }

    @Test fun failedParallelFetchJoinsOtherNetworkReadersBeforeReturning() {
        distinctFiles(20)
        requestBarrier = CountDownLatch(8)
        deliveryGate = CountDownLatch(1)
        rejectGuid.set(1)
        assertThrows(EpicDownloadManager.InstallException::class.java) { install() }
        assertEquals(8, requests.get())
        val cache = File(root, ".chunks")
        val snapshot = cache.listFiles()?.associate { it.name to it.length() }.orEmpty()
        deliveryGate!!.countDown()
        Thread.sleep(150)
        assertEquals(snapshot, cache.listFiles()?.associate { it.name to it.length() }.orEmpty())
        assertTrue(snapshot.keys.none { it.endsWith(".part") })
        assertTrue(root.walk().none { it.name.endsWith(".stage") })
    }

    @Test fun ownedOrphanStagingIsRecoveredBeforeTheSpaceProbeWithoutTouchingManifestFiles() {
        val staging = EpicDownloadManager.recoverOwnedStaging(root, manifest.files)
        File(staging, "native-123-0.stage").writeBytes(ByteArray(bytes.size))
        val legitimate = File(root, ".droiddeck-epic-old.stage").apply { writeText("keep") }
        oldInstall()
        val peak = 2 * bytes.size
        ShadowStatFs.registerStats(root.path, 1024, peak / 4096, peak / 4096)
        assertNotNull(install())
        assertEquals("keep", legitimate.readText())
        // The orphan was recovered, and a finished install removes the then-empty owned namespace.
        assertFalse(staging.exists())
    }

    @Test fun stagingRecoveryRefusesUnownedAndManifestOwnedNamespaces() {
        val staging = File(root, EpicDownloadManager.STAGING_DIRECTORY).apply { mkdir() }
        val untouched = File(staging, "legitimate.stage").apply { writeText("keep") }
        assertThrows(EpicDownloadManager.InstallException::class.java) { install() }
        assertEquals("keep", untouched.readText())
        File(staging, "owner").writeText("DroidDeck Epic staging v1\n")
        manifest.files.first().filename = "${EpicDownloadManager.STAGING_DIRECTORY}/legitimate.stage"
        assertThrows(EpicDownloadManager.InstallException::class.java) { install() }
        assertEquals("keep", untouched.readText())
        assertEquals(0, requests.get())
    }

    @Test fun loadedNativeAbiFailureIsExplicitAndNeverFallsBackToJavaDownloads() {
        val field = com.droiddeck.launcher.stores.StoresNative::class.java.getDeclaredField("loaded")
        field.isAccessible = true
        field.set(null, true)
        try {
            oldInstall()
            val error = assertThrows(EpicDownloadManager.InstallException::class.java) { install() }
            assertTrue(error.message.orEmpty().contains("Native Epic install failed"))
            assertEquals(0, requests.get())
            assertOldInstallIntact()
        } finally {
            com.droiddeck.launcher.stores.StoresNative.forget()
        }
    }

    @Test fun stagingRecoveryRejectsSymlinkNamespaceOwnerAndEntriesWithoutDeletingOutsideData() {
        val outside = temp.newFolder("outside-staging")
        val externalStage = File(outside, "keep.stage").apply { writeText("keep") }
        val externalOwner = File(outside, "owner").apply { writeText("DroidDeck Epic staging v1\n") }
        val staging = File(root, EpicDownloadManager.STAGING_DIRECTORY)
        try {
        java.nio.file.Files.createSymbolicLink(staging.toPath(), outside.toPath())
        } catch (e: java.nio.file.FileSystemException) {
        org.junit.Assume.assumeNoException("Host does not permit symbolic links", e)
        }
        try {
        val error = assertThrows(EpicDownloadManager.InstallException::class.java) { install() }
        assertTrue(error.message.orEmpty().contains("Unsafe Epic staging"))
        assertEquals("keep", externalStage.readText())
        java.nio.file.Files.delete(staging.toPath())
        staging.mkdir()
        java.nio.file.Files.createSymbolicLink(File(staging, "owner").toPath(), externalOwner.toPath())
        assertThrows(EpicDownloadManager.InstallException::class.java) { install() }
        java.nio.file.Files.delete(File(staging, "owner").toPath())
        File(staging, "owner").writeText("DroidDeck Epic staging v1\n")
        val localStage = File(staging, "local.stage").apply { writeText("local") }
        java.nio.file.Files.createSymbolicLink(File(staging, "linked.stage").toPath(), externalStage.toPath())
        assertThrows(EpicDownloadManager.InstallException::class.java) { install() }
        assertEquals("local", localStage.readText())
        assertEquals("keep", externalStage.readText())
        assertEquals(0, requests.get())
        } finally {
        if (java.nio.file.Files.isSymbolicLink(staging.toPath())) java.nio.file.Files.delete(staging.toPath())
        for (name in listOf("owner", "linked.stage")) {
            val entry = File(staging, name).toPath()
            if (java.nio.file.Files.isSymbolicLink(entry)) java.nio.file.Files.delete(entry)
        }
        }
    }
}
