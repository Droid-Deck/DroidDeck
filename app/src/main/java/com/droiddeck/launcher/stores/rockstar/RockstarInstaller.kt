package com.droiddeck.launcher.stores.rockstar

import android.content.Context
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreExe
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.StoreInstalls
import com.droiddeck.launcher.stores.download.DownloadQueue
import com.droiddeck.launcher.stores.download.DownloadStage
import org.w3c.dom.Element
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class RockstarChunk(val fileEntryId: String, val chunkId: String, val sha256: String, val offset: Long, val size: Long)

internal data class RockstarFile(val entryId: String, val versionId: String, val path: String, val size: Long, val sha256: String, val chunks: List<RockstarChunk>)

internal data class RockstarBuild(val buildId: String, val version: String, val preload: Boolean, val files: List<RockstarFile>) {
    val bytes: Long get() = files.sumOf { it.size }
}

internal object RockstarManifest {
    fun inside(root: File, relative: String): File {
        require(safePath(relative) == relative) { "Unsafe Rockstar install path" }
        val base = root.canonicalFile
        var target = base
        for (part in relative.split('/')) {
            target = File(target, part)
            require(!Files.isSymbolicLink(target.toPath())) { "Rockstar install path is a symbolic link" }
        }
        require(target.canonicalFile.toPath().startsWith(base.toPath())) { "Rockstar install path leaves its folder" }
        return target
    }

    fun verified(target: File, chunk: RockstarChunk, cancelled: () -> Boolean = { false }): Boolean {
        if (cancelled()) return false
        if (!target.isFile || chunk.offset < 0 || chunk.size < 0 || chunk.offset > target.length() || chunk.size > target.length() - chunk.offset) return false
        return RandomAccessFile(target, "r").use { input ->
            input.seek(chunk.offset)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(256 * 1024)
            var remaining = chunk.size
            while (remaining > 0) {
                if (cancelled()) return false
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count <= 0) return false
                digest.update(buffer, 0, count)
                remaining -= count
            }
            digest.digest().joinToString("") { "%02x".format(it) }.equals(chunk.sha256, true)
        }
    }

    fun safePath(raw: String): String? {
        val parts = raw.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." || it.contains(':') }) return null
        return parts.joinToString("/")
    }

    fun parse(root: Element): RockstarBuild {
        val result = RockstarRos.first(root, "Result") ?: throw IllegalStateException("manifest without a result")
        val files = RockstarRos.all(result, "FileDetails").map { f ->
            val entry = f.getAttribute("FileEntryId")
            val path = safePath(RockstarRos.text(f, "RelativePath").orEmpty()) ?: throw IllegalStateException("unsafe path in the manifest")
            val chunks = RockstarRos.first(f, "FileChunks")?.let { RockstarRos.children(it, "Chunk") }.orEmpty().map { c ->
                RockstarChunk(entry, c.getAttribute("FileChunkId"), c.getAttribute("SHA256Hash").lowercase(),
                    c.getAttribute("StartByteOffset").toLong(), c.getAttribute("Size").toLong())
            }
            val whole = RockstarRos.children(f, "SHA256Hash").firstOrNull()?.textContent?.trim()?.lowercase().orEmpty()
            RockstarFile(entry, f.getAttribute("FileEntryVersionId"), path, f.getAttribute("FileSize").toLong(), whole, chunks)
        }
        return RockstarBuild(result.getAttribute("BuildId"), result.getAttribute("VersionNumber"),
            RockstarRos.text(result, "IsPreload").equals("true", true), files)
    }

    fun batches(chunks: List<RockstarChunk>, maxBytes: Long = 48L * 1024 * 1024, maxCount: Int = 16): List<List<RockstarChunk>> {
        val out = ArrayList<List<RockstarChunk>>()
        var cur = ArrayList<RockstarChunk>()
        var bytes = 0L
        for (c in chunks) {
            if (cur.isNotEmpty() && (bytes + c.size > maxBytes || cur.size >= maxCount)) { out += cur; cur = ArrayList(); bytes = 0 }
            cur += c
            bytes += c.size
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    fun key(file: RockstarFile, chunk: RockstarChunk) = "${file.versionId}:${chunk.chunkId}"
}

internal class RockstarInstaller(
    private val app: Context,
    private val item: CatalogItem,
    private val folder: File,
) : DownloadQueue.DownloadJob {
    private val cancelled = AtomicBoolean(false)

    override fun run(handle: DownloadQueue.JobHandle): String? {
        handle.stage(DownloadStage.MANIFEST, app.getString(R.string.stores_sync_sign_in))
        val ticket = RockstarAuth.ticket(app)?.ticket ?: throw IllegalStateException("Not signed in to Rockstar Games")
        val title = RockstarTitles.byId(item.id) ?: throw IllegalStateException("Rockstar has no PC build of ${item.title}")
        val account = RockstarApps.fetch(ticket) { handle.log(it) }
        if (title !in RockstarLibrary.owned(account)) throw IllegalStateException("${item.title} is not in this Rockstar account's library")
        val owned = RockstarApps.deliverer(account.apps, title)
            ?: throw IllegalStateException("${item.title} is owned through another store and installs from there")
        val branch = RockstarApps.branch(owned)!!
        handle.log("rockstar: ${owned.name} branch ${branch.name} build ${branch.buildId}")
        val build = RockstarManifest.parse(RockstarRos.result("app.asmx/GetBuildManifestFull", listOf("ticket" to ticket, "branchAccessToken" to branch.accessToken)))
        if (build.preload) throw IllegalStateException("${item.title} is a preload Rockstar has not unlocked yet")
        handle.log("rockstar: build ${build.buildId} ${build.version}, ${build.files.size} files, ${build.bytes} bytes")
        handle.diskSize(build.bytes)
        val cover = item.tallImageUrl ?: item.imageUrl
        StoreInstalls.begin(folder, Store.ROCKSTAR, item.id, item.title, cover, item.imageUrl)
        if (cancelled.get()) return null

        val ledger = RockstarManifest.inside(folder, LEDGER)
        val done = HashSet<String>().apply { if (ledger.isFile) ledger.readLines().filterTo(this) { it.isNotBlank() } }
        val pending = ArrayList<Pair<RockstarFile, RockstarChunk>>()
        var have = 0L
        for (f in build.files) {
            if (cancelled.get()) return null
            val target = RockstarManifest.inside(folder, f.path)
            target.parentFile?.mkdirs()
            for (c in f.chunks) {
                if (cancelled.get()) return null
                if (RockstarManifest.key(f, c) in done && RockstarManifest.verified(target, c, cancelled::get)) have += c.size else pending += f to c
            }
            if (cancelled.get()) return null
            RandomAccessFile(target, "rw").use { if (it.length() != f.size) it.setLength(f.size) }
        }
        val fileOf = pending.associate { (f, c) -> "${c.fileEntryId}:${c.chunkId}" to f }
        val total = build.bytes
        val progress = AtomicLong(have)
        handle.stage(DownloadStage.DOWNLOAD)
        handle.progress(have, total, "Downloading (${build.files.size} files)")

        val queue = ConcurrentLinkedQueue(RockstarManifest.batches(pending.map { it.second }))
        val failure = AtomicReference<Throwable?>(null)
        val pool = Executors.newFixedThreadPool(WORKERS, DownloadQueue.workerFactory("rockstar-dl"))
        val ledgerLock = Any()
        repeat(WORKERS) {
            pool.execute {
                while (!cancelled.get() && failure.get() == null) {
                    val batch = queue.poll() ?: break
                    try {
                        val urls = chunkUrls(ticket, branch.accessToken, batch)
                        for (c in batch) {
                            if (cancelled.get()) break
                            val f = fileOf.getValue("${c.fileEntryId}:${c.chunkId}")
                            fetch({ retry -> (if (retry) null else urls[c.chunkId]) ?: chunkUrls(ticket, branch.accessToken, listOf(c)).getValue(c.chunkId) }, RockstarManifest.inside(folder, f.path), c, progress) {
                                handle.progress(progress.get(), total, "Downloading (${build.files.size} files)")
                            }
                            synchronized(ledgerLock) { ledger.appendText(RockstarManifest.key(f, c) + "\n") }
                        }
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    }
                }
            }
        }
        pool.shutdown()
        while (!pool.awaitTermination(250, TimeUnit.MILLISECONDS)) Unit
        if (cancelled.get()) return null
        failure.get()?.let { throw it }

        handle.interruptible(false)
        handle.stage(DownloadStage.INSTALL, app.getString(R.string.stores_registering))
        val exe = resolve(folder, title.exe) ?: StoreExe.pick(folder, null, item.title).ifEmpty { title.exe.replace('\\', '/') }
        handle.log("rockstar: exe=$exe")
        val sidecar = StoreGameSidecar(
            Store.ROCKSTAR, item.id, item.title, exe = exe,
            installVersion = build.version.ifEmpty { build.buildId }, installedAt = System.currentTimeMillis(),
            cover = cover, hero = item.imageUrl, cloud = false,
            extra = mapOf("rosTitleId" to title.rosTitleId.toString(), "app" to owned.name, "buildId" to build.buildId, "platform" to title.platform),
        )
        StoreInstalls.complete(app, folder, sidecar, onStep = { d, steps -> handle.stageProgress(d.toLong(), steps.toLong()) })
        ledger.delete()
        return folder.path
    }

    private fun chunkUrls(ticket: String, branchToken: String, batch: List<RockstarChunk>): Map<String, String> {
        val csv = batch.joinToString(",") { "${it.fileEntryId}:${it.chunkId}" }
        val root = RockstarRos.result("app.asmx/RetrieveFileChunkMulti", listOf("ticket" to ticket, "branchAccessToken" to branchToken, "fileEntryAndChunkIdCsv" to csv))
        return RockstarRos.all(root, "Chunk").mapNotNull { c -> RockstarRos.text(c, "RedirectUrl")?.let { c.getAttribute("FileChunkId") to it } }.toMap()
    }

    private fun fetch(url: (Boolean) -> String, target: File, chunk: RockstarChunk, progress: AtomicLong, report: () -> Unit) {
        var lastError: Exception? = null
        for (attempt in 0 until 3) {
            if (cancelled.get()) return
            val written = AtomicLong(0)
            try {
                val c = URL(url(attempt > 0)).openConnection() as HttpURLConnection
                c.connectTimeout = 20_000
                c.readTimeout = 60_000
                try {
                    if (c.responseCode != 200) throw java.io.IOException("chunk ${chunk.chunkId}: HTTP ${c.responseCode}")
                    val digest = MessageDigest.getInstance("SHA-256")
                    RandomAccessFile(target, "rw").use { out ->
                        out.seek(chunk.offset)
                        c.inputStream.use { input ->
                            val buf = ByteArray(256 * 1024)
                            var last = 0L
                            while (true) {
                                if (cancelled.get()) { progress.addAndGet(-written.get()); return }
                                val n = input.read(buf)
                                if (n < 0) break
                                if (written.get() + n > chunk.size) throw java.io.IOException("chunk ${chunk.chunkId} is longer than the manifest says")
                                out.write(buf, 0, n)
                                digest.update(buf, 0, n)
                                written.addAndGet(n.toLong())
                                progress.addAndGet(n.toLong())
                                val now = System.currentTimeMillis()
                                if (now - last > 400) { last = now; report() }
                            }
                        }
                    }
                    val hex = digest.digest().joinToString("") { "%02x".format(it) }
                    if (written.get() != chunk.size || hex != chunk.sha256) throw java.io.IOException("chunk ${chunk.chunkId} failed its check")
                    report()
                    return
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
                progress.addAndGet(-written.get())
                lastError = e
            }
        }
        throw lastError ?: java.io.IOException("chunk ${chunk.chunkId} failed")
    }

    override fun cancel(deleteFiles: Boolean) {
        cancelled.set(true)
        if (deleteFiles) StoreInstalls.discard(app, folder, emptyList())
    }

    internal companion object {
        const val LEDGER = ".droiddeck-rockstar-chunks"
        private const val WORKERS = 4

        fun resolve(folder: File, configured: String): String? {
            var at = folder
            for (part in configured.replace('\\', '/').split('/').filter { it.isNotEmpty() }) {
                at = at.listFiles()?.firstOrNull { it.name.equals(part, ignoreCase = true) } ?: return null
            }
            return if (at.isFile) at.relativeTo(folder).invariantSeparatorsPath else null
        }
    }
}
