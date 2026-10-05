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

/**
 * The file operations the File Manager needs, as Bannerlator's FileUtils has them: a delete that
 * says whether it worked, a copy and a move that report bytes as they go, and the size format
 * the rows use. Kept apart from core/FileUtils, whose callers want a quiet delete.
 */
object FileOps {
    private const val TAG = "FileOps"

    /** Byte-accurate copy progress (copied/total bytes), reported as the copy proceeds. */
    fun interface ProgressCallback { fun onProgress(copiedBytes: Long, totalBytes: Long) }

    fun formatBytes(context: Context, bytes: Long): String {
        if (bytes <= 0) return context.getString(R.string.fm_size_zero)
        val units = arrayOf(context.getString(R.string.fm_unit_bytes), "KB", "MB", "GB", "TB")
        val group = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.lastIndex)
        return String.format(Locale.ENGLISH, "%.2f", bytes / Math.pow(1024.0, group.toDouble())) + " " + units[group]
    }

    fun isSymlink(file: File): Boolean = try { Files.isSymbolicLink(file.toPath()) } catch (e: Exception) { false }

    /** Recursive delete that never follows a symlink; true when everything went. */
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

    /** Recursive total size in bytes of files under [f] (skips symlinks). Used to size a progress bar. */
    fun totalSize(f: File?): Long {
        if (f == null || !f.exists() || isSymlink(f)) return 0
        if (f.isDirectory) return f.listFiles()?.sumOf { totalSize(it) } ?: 0
        return f.length()
    }

    /**
     * Moves [src] to [dst], relinking instead of copying whenever possible. A rename is instant
     * regardless of size but only works within one filesystem; renameTo returning false is the
     * signal that a boundary was crossed, so it doubles as the check, and the fallback is
     * copy-then-delete. Only attempted when the destination does not exist: renaming onto an
     * existing directory does not merge.
     */
    fun moveWithProgress(src: File, dst: File, progress: ProgressCallback?): Boolean {
        if (sameFile(src, dst)) return true
        if (!dst.exists() && src.renameTo(dst)) {
            progress?.onProgress(1, 1)
            return true
        }
        if (!copyWithProgress(src, dst, progress)) return false
        return delete(src)
    }

    /** A copy that reports byte progress, smooth even within one large file. */
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
        val chunk = 4L * 1024 * 1024   // report progress every 4 MiB
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

    /** True when [child] is [ancestor] itself or lives anywhere inside it. */
    private fun isWithin(child: File, ancestor: File): Boolean {
        val c = try { child.canonicalPath } catch (e: IOException) { child.absolutePath }
        val a = try { ancestor.canonicalPath } catch (e: IOException) { ancestor.absolutePath }
        return c == a || c.startsWith(a + File.separator)
    }
}
