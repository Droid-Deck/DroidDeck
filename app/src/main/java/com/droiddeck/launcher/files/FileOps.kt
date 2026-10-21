package com.droiddeck.launcher.files

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.R
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.Locale

object FileOps {
    private const val TAG = "FileOps"

    fun interface ProgressCallback { fun onProgress(copiedBytes: Long, totalBytes: Long) }

    fun formatBytes(context: Context, bytes: Long): String {
        if (bytes <= 0) return context.getString(R.string.fm_size_zero)
        val units = arrayOf(context.getString(R.string.fm_unit_bytes), "KB", "MB", "GB", "TB")
        val group = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.lastIndex)
        return String.format(Locale.ENGLISH, "%.2f", bytes / Math.pow(1024.0, group.toDouble())) + " " + units[group]
    }

    fun isSymlink(file: File): Boolean = try { Files.isSymbolicLink(file.toPath()) } catch (e: Exception) { false }

    fun delete(target: File?): Boolean {
        if (target == null) return false
        if (target.isDirectory && !isSymlink(target)) {
            if (!clear(target)) return false
        }
        return target.delete()
    }

    fun clear(dir: File): Boolean {
        val children = dir.listFiles() ?: return true
        var ok = true
        for (child in children) if (!delete(child)) ok = false
        return ok
    }

    fun totalSize(f: File?): Long {
        if (f == null || !f.exists() || isSymlink(f)) return 0
        if (f.isDirectory) return f.listFiles()?.sumOf { totalSize(it) } ?: 0
        return f.length()
    }

    fun moveWithProgress(src: File, dst: File, progress: ProgressCallback?, isCancelled: () -> Boolean = { false }): Boolean {
        if (sameFile(src, dst)) return true
        if (isCancelled()) return false
        if (!dst.exists() && src.renameTo(dst)) {
            progress?.onProgress(1, 1)
            return true
        }
        if (!copyWithProgress(src, dst, progress, isCancelled)) return false
        if (isCancelled()) return false
        return delete(src)
    }

    fun copyWithProgress(src: File, dst: File, progress: ProgressCallback?, isCancelled: () -> Boolean = { false }): Boolean {
        val total = maxOf(totalSize(src), 1L)
        return copyWithProgress(src, dst, longArrayOf(0), total, progress, isCancelled)
    }

    private fun copyWithProgress(src: File, dst: File, done: LongArray, total: Long, progress: ProgressCallback?, isCancelled: () -> Boolean): Boolean {
        if (isCancelled()) throw java.util.concurrent.CancellationException("Copy cancelled: $src")
        if (isSymlink(src)) return copyLink(src, dst)
        if (sameFile(src, dst)) return true
        if (src.isDirectory && isWithin(dst, src)) {
            Log.e(TAG, "Refusing to copy directory into itself: $src -> $dst")
            return false
        }
        if (src.isDirectory) {
            if (!dst.exists() && !dst.mkdirs()) return false
            var allOk = true
            src.list()?.forEach { name ->
                if (!copyWithProgress(File(src, name), File(dst, name), done, total, progress, isCancelled)) {
                    Log.e(TAG, "Failed to copy: ${File(src, name)}")
                    allOk = false
                }
            }
            return allOk
        }
        val parent = dst.parentFile
        if (!src.exists() || (parent != null && !parent.exists() && !parent.mkdirs())) return false
        val chunk = 4L * 1024 * 1024
        var staging: File? = null
        var created = false
        var output: java.nio.channels.FileChannel? = null
        try {
            output = try {
                java.nio.channels.FileChannel.open(dst.toPath(), java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.CREATE_NEW)
                    .also { created = true }
            } catch (e: java.nio.file.FileAlreadyExistsException) {
                null
            }
            val target = if (!created && isSymlink(dst)) dst.canonicalFile else dst
            var permissions: Set<java.nio.file.attribute.PosixFilePermission>? = null
            if (output == null) {
                if (isSymlink(target) || target.parentFile?.isDirectory != true) {
                    throw IOException("Destination symlink target is not a writable directory entry: $dst")
                }
                if (target.isDirectory) throw IOException("Destination is a directory: $target")
                permissions = try {
                    Files.getPosixFilePermissions(target.toPath())
                } catch (e: java.nio.file.NoSuchFileException) {
                    null
                } catch (e: UnsupportedOperationException) {
                    null
                }
                if (target.exists() && !target.canWrite()) throw IOException("Destination is not writable: $target")
                val directory = Files.createTempDirectory(checkNotNull(target.absoluteFile.parentFile).toPath(), ".droiddeck-copy-")
                staging = directory.toFile()
                output = FileOutputStream(File(staging, "file")).channel
            }
            FileInputStream(src).channel.use { input ->
                checkNotNull(output).use { channel ->
                    val size = input.size()
                    var position = 0L
                    while (position < size) {
                        val transferred = input.transferTo(position, minOf(chunk, size - position), channel)
                        if (transferred <= 0) break
                        position += transferred
                        done[0] += transferred
                        progress?.onProgress(done[0], total)
                    }
                    if (position < size) throw IOException("Incomplete copy: $position/$size bytes")
                }
            }
            staging?.let { directory ->
                val copied = File(directory, "file").toPath()
                if (permissions != null && Files.getPosixFilePermissions(copied) != permissions) {
                    Files.setPosixFilePermissions(copied, permissions)
                }
                if (isCancelled()) throw java.util.concurrent.CancellationException("Copy cancelled: $src")
                Files.move(copied, target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            }
            return true
        } catch (e: java.util.concurrent.CancellationException) {
            if (created) dst.delete()
            throw e
        } catch (e: IOException) {
            Log.e(TAG, "Failed to copy file: $src to $dst", e)
            if (created) dst.delete()
            return false
        } finally {
            output?.close()
            staging?.let { directory ->
                File(directory, "file").delete()
                directory.delete()
            }
        }
    }

    private fun copyLink(src: File, dst: File): Boolean {
        if (src.absoluteFile == dst.absoluteFile) return true
        return try {
            val target = Files.readSymbolicLink(src.toPath())
            val parent = dst.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) return false
            try {
                Files.createSymbolicLink(dst.toPath(), target)
            } catch (e: java.nio.file.FileAlreadyExistsException) {
                val staging = Files.createTempDirectory(checkNotNull(dst.absoluteFile.parentFile).toPath(), ".droiddeck-link-")
                val tmp = staging.resolve("link")
                try {
                    Files.createSymbolicLink(tmp, target)
                    Files.move(tmp, dst.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                } finally {
                    Files.deleteIfExists(tmp)
                    Files.deleteIfExists(staging)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy link: $src to $dst", e)
            false
        }
    }

    private fun sameFile(a: File, b: File): Boolean =
        try { a.canonicalPath == b.canonicalPath } catch (e: IOException) { a.absolutePath == b.absolutePath }

    private fun isWithin(child: File, ancestor: File): Boolean {
        val c = try { child.canonicalPath } catch (e: IOException) { child.absolutePath }
        val a = try { ancestor.canonicalPath } catch (e: IOException) { ancestor.absolutePath }
        return c == a || c.startsWith(a + File.separator)
    }
}
