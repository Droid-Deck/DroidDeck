package com.droiddeck.launcher.stores.itch

import android.content.Context
import com.droiddeck.launcher.R
import com.droiddeck.launcher.runtime.GuestCommand
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreExe
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.StoreInstallRoot
import com.droiddeck.launcher.stores.StoreInstalls
import com.droiddeck.launcher.stores.StoreNet
import com.droiddeck.launcher.stores.download.DownloadQueue
import com.droiddeck.launcher.stores.download.DownloadStage
import org.apache.commons.compress.MemoryLimitException
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

internal class ItchInstaller(
    private val app: Context,
    private val item: CatalogItem,
    private val folder: File,
) : DownloadQueue.DownloadJob {
    private val cancelled = AtomicBoolean(false)
    private var work: File = folder
    private var updating = false
    private var previous: StoreGameSidecar? = null
    private val commit = Any()

    override fun run(handle: DownloadQueue.JobHandle): String? {
        val key = ItchAuth.key(app)
        if (key.isBlank()) throw IllegalStateException(app.getString(R.string.stores_sync_sign_in))
        val downloadKeyId = item.extra["downloadKeyId"].orEmpty()
        if (downloadKeyId.isBlank() && !item.isFree) throw IllegalStateException(app.getString(R.string.stores_itch_not_owned))
        val gameId = ItchGame.resolveId(item) ?: throw IllegalStateException(app.getString(R.string.stores_itch_no_game))
        val before = StoreGameSidecar.read(folder)
        previous = before
        updating = before != null && (before.isInstalled || before.state == StoreGameSidecar.STATE_UPDATING)
        val resuming = before?.let { it.state == StoreGameSidecar.STATE_INSTALLING && it.id == item.id } == true
        StoreInstalls.begin(folder, Store.ITCH, item.id, item.title, item.imageUrl, item.imageUrl)
        handle.stage(DownloadStage.MANIFEST)
        val uploads = ItchApi.uploads(key, gameId, downloadKeyId)
        val chosen = item.extra["uploadId"].orEmpty()
        val upload = (if (chosen.isBlank()) ItchUploads.windows(uploads) else ItchUploads.byId(uploads, chosen))
            ?: throw IllegalStateException(app.getString(R.string.stores_itch_no_upload))
        val uploadId = upload.optString("id")
        val filename = ItchUploads.filename(upload)
        val size = ItchUploads.size(upload)
        val scratch = StoreInstallRoot.scratchDir(app, Store.ITCH, item.id)
        scratch.mkdirs()
        val archive = File(scratch, filename.ifBlank { "download.bin" })
        val buildId = ItchBuilds.buildId(upload)
        val exe = try {
            var patched = updating && !resuming && previous?.isInstalled == true &&
                patch(key, downloadKeyId, uploadId, buildId, size, scratch, handle)
            if (cancelled.get()) {
                if (patched) dropCaches(scratch)
                return null
            }
            var picked: String? = null
            if (patched) {
                picked = install(archive, filename, handle, resuming, true)
                if (picked == null) {
                    if (cancelled.get()) return null
                    handle.log("itch.io: the patched copy of ${item.title} has no exe, doing a full update")
                    patched = false
                }
            }
            if (!patched) {
                handle.stage(DownloadStage.DOWNLOAD)
                download(key, downloadKeyId, uploadId, upload.optString("updated_at", upload.optString("updatedAt")), archive, size, handle)
                if (cancelled.get()) return null
                handle.stage(DownloadStage.INSTALL)
                picked = install(archive, filename, handle, resuming, false)
            }
            picked
        } catch (t: Throwable) {
            if (!cancelled.get()) dropCaches(scratch)
            throw t
        }
        if (exe == null) {
            if (cancelled.get()) return null
            dropCaches(scratch)
            throw IllegalStateException(app.getString(R.string.stores_itch_no_exe))
        }
        archive.delete()
        StoreInstalls.deleteTree(scratch)
        val sidecar = StoreGameSidecar(
            store = Store.ITCH,
            id = item.id,
            title = item.title,
            exe = exe,
            args = previous?.args.orEmpty(),
            env = previous?.env.orEmpty(),
            installVersion = upload.optString("updated_at", upload.optString("updatedAt")),
            installedAt = System.currentTimeMillis(),
            cover = item.imageUrl,
            hero = item.imageUrl,
            extra = mapOf("gameId" to gameId, "downloadKeyId" to downloadKeyId, "uploadId" to uploadId, "buildId" to buildId),
            cloud = false,
        )
        StoreInstalls.complete(app, folder, sidecar) { done, steps -> handle.stageProgress(done.toLong(), steps.toLong()) }
        ItchUpdates.applied(app, item.key)
        return folder.path
    }

    override fun cancel(deleteFiles: Boolean) {
        cancelled.set(true)
        if (deleteFiles) dropCaches(StoreInstallRoot.scratchDir(app, Store.ITCH, item.id))
    }

    private fun dropCaches(scratch: File) {
        if (!updating) {
            StoreInstalls.discard(app, folder, listOf(scratch))
            return
        }
        synchronized(commit) {
            StoreInstalls.deleteTree(scratch)
            ItchStaging.delete(File(folder, ItchStaging.DIR))
        }
    }

    private fun download(key: String, downloadKeyId: String, uploadId: String, version: String, target: File, expected: Long,
                         handle: DownloadQueue.JobHandle) {
        // A partial file is only resumed when it came from this exact upload build.
        val origin = File(target.path + ".from")
        val stamp = "$uploadId $version $expected"
        if (!target.isFile || runCatching { origin.readText() }.getOrNull() != stamp) {
            target.delete()
            origin.writeText(stamp)
        }
        if (expected > 0 && target.length() == expected) {
            handle.progress(expected, expected)
            return
        }
        for (attempt in 0..1) {
            val have = target.length()
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(ItchApi.downloadUrl(uploadId, downloadKeyId)).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", StoreNet.BROWSER_UA)
                    setRequestProperty("Authorization", "Bearer $key")
                    setRequestProperty("Accept", "*/*")
                    if (have > 0) setRequestProperty("Range", "bytes=$have-")
                }
                val code = conn.responseCode
                if (code == 416 && attempt == 0) {
                    target.delete()
                    continue
                }
                if (code !in 200..299) throw IllegalStateException(app.getString(R.string.stores_itch_download_failed, code))
                val append = code == 206 && have > 0
                if (have > 0) handle.log(if (append) "itch.io: resuming at ${have} bytes" else "itch.io: server ignored the resume, starting over")
                var done = if (append) have else 0L
                val total = conn.contentLengthLong.takeIf { it > 0 }?.let { if (append) it + have else it } ?: expected
                // The first report is the queue's speed baseline, so resumed bytes don't read as instant throughput.
                handle.progress(done, total)
                val buffer = ByteArray(128 * 1024)
                conn.inputStream.use { input ->
                    FileOutputStream(target, append).use { out ->
                        while (true) {
                            if (cancelled.get()) return
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            done += read
                            handle.progress(done, total)
                        }
                    }
                }
                if (total > 0 && done < total) throw IllegalStateException(app.getString(R.string.stores_itch_download_failed, code))
                return
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
    }

    private fun clearPartialInstall() {
        folder.listFiles()?.forEach {
            if (it.name == StoreGameSidecar.FILE_NAME) return@forEach
            if (it.isDirectory) StoreInstalls.deleteTree(it) else it.delete()
        }
    }

    private fun patch(key: String, downloadKeyId: String, uploadId: String, buildId: String, fullSize: Long, scratch: File, handle: DownloadQueue.JobHandle): Boolean {
        val installed = previous?.extra.orEmpty()
        val from = installed["buildId"].orEmpty()
        if (installed["uploadId"] != uploadId || from.isBlank() || buildId.isBlank() || from == buildId) return false
        val out = File(folder, ItchStaging.DIR)
        ItchStaging.delete(out)
        scratch.mkdirs()
        val outcome = try {
            ItchPatch.run(app, key, downloadKeyId, from, buildId, fullSize, folder, out, scratch, handle, cancelled)
        } catch (t: Exception) {
            ItchPatch.Outcome.Failed("${t.javaClass.simpleName} ${t.message}")
        }
        when (outcome) {
            ItchPatch.Outcome.Applied -> {
                handle.log("itch.io: patched ${item.title} from build $from to $buildId")
                return true
            }
            ItchPatch.Outcome.Cancelled -> Unit
            is ItchPatch.Outcome.Failed -> handle.log("itch.io: no patch for ${item.title}, doing a full update (${outcome.reason})")
        }
        ItchStaging.delete(out)
        return false
    }

    private fun install(archive: File, filename: String, handle: DownloadQueue.JobHandle, resuming: Boolean, patched: Boolean = false): String? {
        work = if (updating) File(folder, ItchStaging.DIR) else folder
        if (updating && !patched) ItchStaging.delete(work)
        work.mkdirs()
        if (resuming) clearPartialInstall()
        val exe = try {
            if (!patched) unpack(archive, filename, handle)
            val hint = filename.substringBeforeLast('.')
            var picked = StoreExe.pick(work, hint.ifBlank { null }, item.title)
            var passes = 0
            while (picked.isBlank() && passes < 2 && !cancelled.get()) {
                val inner = ItchArchives.inner(work) ?: break
                handle.log("itch.io: no exe yet, unpacking ${inner.name}")
                unpack(inner, inner.name, handle)
                if (cancelled.get()) break
                inner.delete()
                picked = StoreExe.pick(work, inner.name.substringBeforeLast('.').takeIf { it.endsWith(".exe", ignoreCase = true) }, item.title)
                passes++
            }
            picked
        } catch (t: Throwable) {
            if (updating) ItchStaging.delete(work)
            throw t
        }
        if (exe.isBlank()) {
            handle.log("itch.io: no exe found in ${folder.name}")
            if (updating) ItchStaging.delete(work)
            return null
        }
        if (updating) synchronized(commit) {
            if (cancelled.get()) {
                ItchStaging.delete(work)
                return null
            }
            try {
                StoreInstalls.markUpdating(folder)
            } catch (t: Throwable) {
                ItchStaging.delete(work)
                throw t
            }
            ItchStaging.merge(work, folder)
            ItchStaging.delete(work)
        }
        return exe
    }

    private fun unpack(archive: File, filename: String, handle: DownloadQueue.JobHandle) {
        when (val kind = ItchArchives.kind(archive, filename)) {
            "zip" -> unzip(archive, handle)
            "7z" -> try {
                sevenZip(archive, handle)
            } catch (e: MemoryLimitException) {
                handle.log("itch.io: ${archive.name} needs more memory than the app has, unpacking it in the runtime")
                unrar(archive, handle, kind)
            }
            "rar" -> unrar(archive, handle, kind)
            "exe" -> copy(archive, File(work, filename.ifBlank { "game.exe" }))
            "tar" -> untar(archive, null, handle)
            "gz", "bz2", "xz" -> untar(archive, kind, handle) ?: single(archive, kind, handle)
            else -> copy(archive, File(work, filename.ifBlank { "game" }))
        }
    }

    private fun target(name: String): File? {
        val file = File(work, name.replace('\\', '/'))
        val root = work.canonicalPath
        val path = file.canonicalPath
        return if (path == root || path.startsWith(root + File.separator)) file else null
    }

    // No JVM library reads RAR5; the runtime's bsdtar (libarchive) does.
    private fun unrar(archive: File, handle: DownloadQueue.JobHandle, kind: String) {
        if (!LinuxRuntime.isInstalled(app)) throw IllegalStateException(app.getString(R.string.stores_itch_archive, kind))
        val binds = if (listOf(app.filesDir, app.cacheDir).any { work.absolutePath.startsWith(it.absolutePath + "/") }) null
            else listOf(work.absolutePath)
        fun bsdtar(args: List<String>, onLine: (String) -> Unit) =
            GuestCommand.run(app, listOf("/usr/bin/bsdtar") + args, logName = "itch-unrar", extraBinds = binds, fastPath = true, cancelled = cancelled) { line ->
                if (!line.startsWith("proot ")) onLine(line)
            }
        var entries = 0L
        bsdtar(listOf("-tf", archive.absolutePath)) { entries++ }
        var done = 0L
        val tail = ArrayDeque<String>()
        val code = bsdtar(listOf("-xvf", archive.absolutePath, "-C", work.absolutePath)) { line ->
            if (line.startsWith("x ")) handle.stageProgress(++done, entries) else {
                if (tail.size == 5) tail.removeFirst()
                tail.addLast(line)
            }
        }
        if (cancelled.get()) return
        if (code != 0) {
            handle.log("itch.io: bsdtar exited $code: ${tail.joinToString(" | ")}")
            throw IllegalStateException(app.getString(R.string.stores_itch_extract_failed, code))
        }
    }

    private fun sevenZip(archive: File, handle: DownloadQueue.JobHandle) {
        val total = archive.length()
        var written = 0L
        val buffer = ByteArray(64 * 1024)
        val limitKb = (Runtime.getRuntime().maxMemory() / 4 / 1024).coerceIn(32L * 1024, Int.MAX_VALUE.toLong()).toInt()
        Files.newByteChannel(archive.toPath()).use { channel ->
            SevenZFile.builder().setSeekableByteChannel(channel).setMaxMemoryLimitKb(limitKb).get().use { seven ->
                while (true) {
                    if (cancelled.get()) return
                    val entry = seven.nextEntry ?: break
                    val file = target(entry.name)
                    if (file == null) {
                        handle.log("itch.io: skipped an entry outside the game folder")
                        continue
                    }
                    if (entry.isDirectory) {
                        file.mkdirs()
                        continue
                    }
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { out ->
                        while (true) {
                            if (cancelled.get()) return
                            val read = seven.read(buffer, 0, buffer.size)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            written += read
                            handle.stageProgress(written, total)
                        }
                    }
                }
            }
        }
    }

    private fun untar(archive: File, codec: String?, handle: DownloadQueue.JobHandle): Unit? {
        val total = archive.length()
        var written = 0L
        var entries = 0
        val buffer = ByteArray(64 * 1024)
        val opened = runCatching {
            FileInputStream(archive).use { raw ->
                val stream = when (codec) {
                    "gz" -> GzipCompressorInputStream(BufferedInputStream(raw))
                    "bz2" -> BZip2CompressorInputStream(BufferedInputStream(raw))
                    "xz" -> XZCompressorInputStream(BufferedInputStream(raw))
                    else -> BufferedInputStream(raw)
                }
                stream.use { input ->
                    TarArchiveInputStream(input).use { tar ->
                        while (true) {
                            if (cancelled.get()) return null
                            val entry = tar.nextEntry ?: break
                            entries++
                            val file = target(entry.name)
                            if (file == null) {
                                handle.log("itch.io: skipped an entry outside the game folder")
                                continue
                            }
                            if (entry.isDirectory) {
                                file.mkdirs()
                                continue
                            }
                            file.parentFile?.mkdirs()
                            FileOutputStream(file).use { out ->
                                while (true) {
                                    if (cancelled.get()) return null
                                    val read = tar.read(buffer, 0, buffer.size)
                                    if (read < 0) break
                                    out.write(buffer, 0, read)
                                    written += read
                                    handle.stageProgress(written, total)
                                }
                            }
                        }
                    }
                }
            }
            true
        }
        if (opened.isSuccess && entries > 0) return Unit
        if (opened.isFailure && codec == null) throw opened.exceptionOrNull() ?: IllegalStateException("tar")
        return null
    }

    private fun single(archive: File, codec: String, handle: DownloadQueue.JobHandle) {
        val name = archive.name.substringBeforeLast('.')
        val file = target(name.ifBlank { "game" }) ?: throw IllegalStateException("archive")
        file.parentFile?.mkdirs()
        val total = archive.length()
        var written = 0L
        val buffer = ByteArray(64 * 1024)
        FileInputStream(archive).use { raw ->
            val stream = when (codec) {
                "bz2" -> BZip2CompressorInputStream(BufferedInputStream(raw))
                "xz" -> XZCompressorInputStream(BufferedInputStream(raw))
                else -> GzipCompressorInputStream(BufferedInputStream(raw))
            }
            stream.use { input ->
                FileOutputStream(file).use { out ->
                    while (true) {
                        if (cancelled.get()) return
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        handle.stageProgress(written, total)
                    }
                }
            }
        }
    }

    private fun copy(source: File, target: File) {
        target.parentFile?.mkdirs()
        FileInputStream(source).use { input -> FileOutputStream(target).use { out -> input.copyTo(out, 128 * 1024) } }
    }

    // Streaming readers stop silently on zips whose data descriptors disagree with the local headers;
    // the central directory is authoritative.
    private fun unzip(archive: File, handle: DownloadQueue.JobHandle) {
        val total = archive.length()
        var written = 0L
        val buffer = ByteArray(64 * 1024)
        ZipFile.builder().setFile(archive).get().use { zip ->
            for (entry in zip.entries) {
                if (cancelled.get()) return
                val target = target(entry.name)
                if (target == null) {
                    handle.log("itch.io: skipped an entry outside the game folder")
                    continue
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(target).use { out ->
                        while (true) {
                            if (cancelled.get()) return
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            written += read
                            handle.stageProgress(written, total)
                        }
                    }
                }
            }
        }
    }

    private companion object
}
