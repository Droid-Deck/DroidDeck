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
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

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

    private fun install(archive: File, filename: String, handle: DownloadQueue.JobHandle): String {
        folder.mkdirs()
        val extension = filename.substringAfterLast('.', "").lowercase()
        when {
            extension == "zip" -> unzip(archive, handle)
            extension == "exe" -> copy(archive, File(folder, filename.ifBlank { "game.exe" }))
            extension in ARCHIVES -> throw IllegalStateException(app.getString(R.string.stores_itch_archive, extension))
            isZip(archive) -> unzip(archive, handle)
            else -> copy(archive, File(folder, filename.ifBlank { "game" }))
        }
        val hint = filename.substringBeforeLast('.')
        val exe = StoreExe.pick(folder, hint.ifBlank { null }, item.title)
        if (exe.isBlank()) {
            handle.log("itch.io: no exe found in ${folder.name}")
            return "game.exe"
        }
        return exe
    }

    private fun isZip(file: File): Boolean = runCatching {
        FileInputStream(file).use { input ->
            val head = ByteArray(4)
            input.read(head) == 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        }
    }.getOrDefault(false)

    private fun copy(source: File, target: File) {
        target.parentFile?.mkdirs()
        FileInputStream(source).use { input -> FileOutputStream(target).use { out -> input.copyTo(out, 128 * 1024) } }
    }

    private fun unzip(archive: File, handle: DownloadQueue.JobHandle) {
        val total = archive.length()
        var written = 0L
        val root = folder.canonicalPath
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (cancelled.get()) return
                val entry = zip.nextEntry ?: break
                val target = File(folder, entry.name.replace('\\', '/'))
                val path = target.canonicalPath
                if (path != root && !path.startsWith(root + File.separator)) {
                    handle.log("itch.io: skipped an entry outside the game folder")
                    zip.closeEntry()
                    continue
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                    zip.closeEntry()
                    continue
                }
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { out ->
                    while (true) {
                        if (cancelled.get()) return
                        val read = zip.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        handle.stageProgress(written, total)
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private companion object {
        val ARCHIVES = setOf("7z", "rar", "tar", "gz", "bz2", "xz")
    }
}
