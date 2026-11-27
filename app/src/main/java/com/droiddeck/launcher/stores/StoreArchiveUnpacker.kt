package com.droiddeck.launcher.stores

import android.content.Context
import com.droiddeck.launcher.runtime.GuestCommand
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.stores.download.DownloadQueue
import com.droiddeck.launcher.stores.itch.ItchArchives
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
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

internal class StoreArchiveUnpacker(
    private val app: Context,
    private val cancelled: AtomicBoolean,
    private val label: String,
    private val unsupported: Int,
    private val extractFailed: Int,
    private val logName: String,
) {
    var work: File = File("")
    var limit = Long.MAX_VALUE

    class TooLarge : java.io.IOException()

    fun unpack(archive: File, filename: String, handle: DownloadQueue.JobHandle) =
        unpack(archive, filename, { done, total -> handle.stageProgress(done, total) }, handle::log)

    fun unpack(archive: File, filename: String, progress: (Long, Long) -> Unit, log: (String) -> Unit) {
        when (val kind = ItchArchives.kind(archive, filename)) {
            "zip" -> unzip(archive, progress, log)
            "7z" -> try {
                sevenZip(archive, progress, log)
            } catch (e: MemoryLimitException) {
                log("$label: ${archive.name} needs more memory than the app has, unpacking it in the runtime")
                unrar(archive, progress, log, kind)
            }
            "rar" -> unrar(archive, progress, log, kind)
            "exe" -> copy(archive, File(work, filename.ifBlank { "game.exe" }))
            "tar" -> untar(archive, null, progress, log)
            "gz", "bz2", "xz" -> untar(archive, kind, progress, log) ?: single(archive, kind, progress, log)
            else -> copy(archive, File(work, filename.ifBlank { "game" }))
        }
    }

    private fun target(name: String): File? {
        val file = File(work, name.replace('\\', '/'))
        val root = work.canonicalPath
        val path = file.canonicalPath
        return if (path == root || path.startsWith(root + File.separator)) file else null
    }

    private fun unrar(archive: File, progress: (Long, Long) -> Unit, log: (String) -> Unit, kind: String) {
        if (!LinuxRuntime.isInstalled(app)) throw IllegalStateException(app.getString(unsupported, kind))
        val binds = if (listOf(app.filesDir, app.cacheDir).any { work.absolutePath.startsWith(it.absolutePath + "/") }) null
            else listOf(work.absolutePath)
        fun bsdtar(args: List<String>, onLine: (String) -> Unit) =
            GuestCommand.run(app, listOf("/usr/bin/bsdtar") + args, logName = logName, extraBinds = binds, fastPath = true, cancelled = cancelled) { line ->
                if (!line.startsWith("proot ")) onLine(line)
            }
        var entries = 0L
        var size = 0L
        bsdtar(listOf("-tvf", archive.absolutePath)) { line ->
            entries++
            size += LISTED_SIZE.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        }
        if (size > limit) throw TooLarge()
        var done = 0L
        val tail = ArrayDeque<String>()
        val code = bsdtar(listOf("-xvf", archive.absolutePath, "-C", work.absolutePath)) { line ->
            if (line.startsWith("x ")) progress(++done, entries) else {
                if (tail.size == 5) tail.removeFirst()
                tail.addLast(line)
            }
        }
        if (cancelled.get()) return
        if (code != 0) {
            log("$label: bsdtar exited $code: ${tail.joinToString(" | ")}")
            throw IllegalStateException(app.getString(extractFailed, code))
        }
    }

    private fun sevenZip(archive: File, progress: (Long, Long) -> Unit, log: (String) -> Unit) {
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
                        log("$label: skipped an entry outside the game folder")
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
                            if (written > limit) throw TooLarge()
                            progress(written, total)
                        }
                    }
                }
            }
        }
    }

    private fun untar(archive: File, codec: String?, progress: (Long, Long) -> Unit, log: (String) -> Unit): Unit? {
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
                                log("$label: skipped an entry outside the game folder")
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
                                    if (written > limit) throw TooLarge()
                                    progress(written, total)
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

    private fun single(archive: File, codec: String, progress: (Long, Long) -> Unit, log: (String) -> Unit) {
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
                        if (written > limit) throw TooLarge()
                        progress(written, total)
                    }
                }
            }
        }
    }

    private fun copy(source: File, target: File) {
        target.parentFile?.mkdirs()
        FileInputStream(source).use { input -> FileOutputStream(target).use { out -> input.copyTo(out, 128 * 1024) } }
    }

    private fun unzip(archive: File, progress: (Long, Long) -> Unit, log: (String) -> Unit) {
        val total = archive.length()
        var written = 0L
        val buffer = ByteArray(64 * 1024)
        ZipFile.builder().setFile(archive).get().use { zip ->
            for (entry in zip.entries) {
                if (cancelled.get()) return
                val target = target(entry.name)
                if (target == null) {
                    log("$label: skipped an entry outside the game folder")
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
                            if (written > limit) throw TooLarge()
                            progress(written, total)
                        }
                    }
                }
            }
        }
    }

    private companion object {
        val LISTED_SIZE = Regex("""^\S+\s+\d+\s+\S+\s+\S+\s+(\d+)\s""")
    }
}
