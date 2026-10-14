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

    fun moveWithProgress(src: File, dst: File, progress: ProgressCallback?): Boolean {
        if (sameFile(src, dst)) return true
        if (!dst.exists() && src.renameTo(dst)) {
            progress?.onProgress(1, 1)
            return true
        }
        if (!copyWithProgress(src, dst, progress)) return false
        return delete(src)
    }

    fun copyWithProgress(src: File, dst: File, progress: ProgressCallback?): Boolean {
        val total = maxOf(totalSize(src), 1L)
        return copyWithProgress(src, dst, longArrayOf(0), total, progress)
    }

    private fun copyWithProgress(src: File, dst: File, done: LongArray, total: Long, progress: ProgressCallback?): Boolean {
        if (isSymlink(src)) return true
        if (sameFile(src, dst)) return true
        if (src.isDirectory && isWithin(dst, src)) {
            Log.e(TAG, "Refusing to copy directory into itself: $src -> $dst")
            return false
        }
        if (src.isDirectory) {
            if (!dst.exists() && !dst.mkdirs()) return false
            var allOk = true
            src.list()?.forEach { name ->
                if (!copyWithProgress(File(src, name), File(dst, name), done, total, progress)) {
                    Log.e(TAG, "Failed to copy: ${File(src, name)}")
                    allOk = false
                }
            }
            return allOk
        }
        val parent = dst.parentFile
        if (!src.exists() || (parent != null && !parent.exists() && !parent.mkdirs())) return false
        val chunk = 4L * 1024 * 1024
        try {
            FileInputStream(src).channel.use { input ->
                FileOutputStream(dst).channel.use { output ->
                    val size = input.size()
                    var position = 0L
                    while (position < size) {
                        val transferred = input.transferTo(position, minOf(chunk, size - position), output)
                        if (transferred <= 0) break
                        position += transferred
                        done[0] += transferred
                        progress?.onProgress(done[0], total)
                    }
                    if (position < size) throw IOException("Incomplete copy: $position/$size bytes")
                }
            }
            return true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to copy file: $src to $dst", e)
            dst.delete()
            return false
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
