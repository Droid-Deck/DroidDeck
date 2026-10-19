package com.droiddeck.launcher.stores.itch

import android.content.Context
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreExe
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.StoreInstallRoot
import com.droiddeck.launcher.stores.StoreInstalls
import com.droiddeck.launcher.stores.StoreNet
import com.droiddeck.launcher.stores.download.DownloadQueue
import com.droiddeck.launcher.stores.download.DownloadStage
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
import java.util.concurrent.atomic.AtomicBoolean

internal class ItchInstaller(
    private val app: Context,
    private val item: CatalogItem,
    private val folder: File,
) : DownloadQueue.DownloadJob {
    private val cancelled = AtomicBoolean(false)

    override fun run(handle: DownloadQueue.JobHandle): String? {
        val key = ItchAuth.key(app)
        if (key.isBlank()) throw IllegalStateException(app.getString(R.string.stores_sync_sign_in))
        val downloadKeyId = item.extra["downloadKeyId"].orEmpty()
        if (downloadKeyId.isBlank()) throw IllegalStateException(app.getString(R.string.stores_itch_not_owned))
        val gameId = item.extra["gameId"].orEmpty().ifBlank { item.id.substringAfterLast('/') }
        StoreInstalls.begin(folder, Store.ITCH, item.id, item.title, item.imageUrl, item.imageUrl)
        handle.stage(DownloadStage.MANIFEST)
        val upload = ItchUploads.windows(ItchApi.uploads(key, gameId, downloadKeyId))
            ?: throw IllegalStateException(app.getString(R.string.stores_itch_no_upload))
        val uploadId = upload.optString("id")
        val filename = ItchUploads.filename(upload)
        val size = ItchUploads.size(upload)
        val scratch = StoreInstallRoot.scratchDir(app, Store.ITCH, item.id)
        scratch.mkdirs()
        val archive = File(scratch, filename.ifBlank { "download.bin" })
        handle.stage(DownloadStage.DOWNLOAD)
        handle.progress(0, size)
        download(key, downloadKeyId, uploadId, archive, size, handle)
        if (cancelled.get()) return null
        handle.stage(DownloadStage.INSTALL)
        val exe = install(archive, filename, handle)
        if (exe == null) {
            if (cancelled.get()) return null
            StoreInstalls.discard(app, folder, listOf(scratch))
            throw IllegalStateException(app.getString(R.string.stores_itch_no_exe))
        }
        archive.delete()
        StoreInstalls.deleteTree(scratch)
        val sidecar = StoreGameSidecar(
            store = Store.ITCH,
            id = item.id,
            title = item.title,
            exe = exe,
            installVersion = upload.optString("updated_at", upload.optString("updatedAt")),
            installedAt = System.currentTimeMillis(),
            cover = item.imageUrl,
            hero = item.imageUrl,
            extra = mapOf("gameId" to gameId, "downloadKeyId" to downloadKeyId, "uploadId" to uploadId),
            cloud = false,
        )
        StoreInstalls.complete(app, folder, sidecar) { done, steps -> handle.stageProgress(done.toLong(), steps.toLong()) }
        return folder.path
    }

    override fun cancel(deleteFiles: Boolean) {
        cancelled.set(true)
        if (deleteFiles) StoreInstalls.discard(app, folder, listOf(StoreInstallRoot.scratchDir(app, Store.ITCH, item.id)))
    }

    private fun download(key: String, downloadKeyId: String, uploadId: String, target: File, expected: Long, handle: DownloadQueue.JobHandle) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(ItchApi.downloadUrl(uploadId, downloadKeyId)).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", StoreNet.BROWSER_UA)
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Accept", "*/*")
            }
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException(app.getString(R.string.stores_itch_download_failed, code))
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: expected
            var done = 0L
            val buffer = ByteArray(128 * 1024)
            conn.inputStream.use { input ->
                FileOutputStream(target).use { out ->
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
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun install(archive: File, filename: String, handle: DownloadQueue.JobHandle): String? {
        folder.mkdirs()
        when (val kind = sniff(archive, filename)) {
            "zip" -> unzip(archive, handle)
            "7z" -> sevenZip(archive, handle)
            "rar" -> throw IllegalStateException(app.getString(R.string.stores_itch_archive, kind))
            "exe" -> copy(archive, File(folder, filename.ifBlank { "game.exe" }))
            "tar" -> untar(archive, null, handle)
            "gz", "bz2", "xz" -> untar(archive, kind, handle) ?: single(archive, kind, handle)
            else -> copy(archive, File(folder, filename.ifBlank { "game" }))
        }
        val hint = filename.substringBeforeLast('.')
        val exe = StoreExe.pick(folder, hint.ifBlank { null }, item.title)
        if (exe.isBlank()) {
            handle.log("itch.io: no exe found in ${folder.name}")
            return null
        }
        return exe
    }

    private fun sniff(file: File, filename: String): String {
        val extension = filename.substringAfterLast('.', "").lowercase()
        if (extension == "exe") return "exe"
        val head = runCatching { FileInputStream(file).use { input -> ByteArray(300).also { input.read(it) } } }.getOrNull()
            ?: return extension
        fun at(i: Int, vararg bytes: Int) = bytes.indices.all { head.getOrNull(i + it) == bytes[it].toByte() }
        return when {
            at(0, 0x50, 0x4B) -> "zip"
            at(0, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> "7z"
            at(0, 0x52, 0x61, 0x72, 0x21) -> "rar"
            at(0, 0x1F, 0x8B) -> "gz"
            at(0, 0x42, 0x5A, 0x68) -> "bz2"
            at(0, 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) -> "xz"
            at(257, 0x75, 0x73, 0x74, 0x61, 0x72) -> "tar"
            else -> extension
        }
    }

    private fun target(name: String): File? {
        val file = File(folder, name.replace('\\', '/'))
        val root = folder.canonicalPath
        val path = file.canonicalPath
        return if (path == root || path.startsWith(root + File.separator)) file else null
    }

    private fun sevenZip(archive: File, handle: DownloadQueue.JobHandle) {
        val total = archive.length()
        var written = 0L
        val buffer = ByteArray(64 * 1024)
        SevenZFile.builder().setFile(archive).get().use { seven ->
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
