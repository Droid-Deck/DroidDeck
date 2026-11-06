package com.droiddeck.launcher.session

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.FileNotFoundException
import java.nio.file.Path

internal interface SecureLogOpener {
    fun open(context: Context, name: String): ParcelFileDescriptor?
}

class SecureLogFileProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Diagnostic shares are read-only")
        val name = sharedName(uri)
        return openShared(name) ?: throw FileNotFoundException("Diagnostic share is unavailable")
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val name = sharedName(uri)
        val size = openShared(name)?.use { Os.fstat(it.fileDescriptor).st_size }
            ?: throw FileNotFoundException("Diagnostic share is unavailable")
        val requested = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val columns = requested.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }
        return MatrixCursor(columns.toTypedArray(), 1).apply {
            addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) name else size }.toTypedArray())
        }
    }

    override fun getType(uri: Uri): String {
        val name = sharedName(uri)
        val extension = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException("read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read-only")

    private fun openShared(name: String): ParcelFileDescriptor? {
        val context = requireNotNull(context)
        openerOverride.get()?.let { return it.open(context, name) }
        return AndroidSecureLogOpener.open(context, name)
    }

    private fun sharedName(uri: Uri): String {
        val context = requireNotNull(context)
        if (uri.scheme != "content" || uri.authority != context.packageName + ".logs" || uri.fragment != null) {
            throw FileNotFoundException("Invalid diagnostic share URI")
        }
        val parts = uri.pathSegments
        if (parts.size != 2 || parts[0] != "shared_logs" || !safeName(parts[1])) {
            throw FileNotFoundException("Invalid diagnostic share URI")
        }
        return parts[1]
    }

    companion object {
        private val openerOverride = object : ThreadLocal<SecureLogOpener?>() {
            override fun initialValue(): SecureLogOpener? = null
        }

        internal fun setOpenerForTest(opener: SecureLogOpener?) {
            if (opener == null) openerOverride.remove() else openerOverride.set(opener)
        }

        internal fun safeName(name: String): Boolean =
            name.length in 1..255 && name != "." && name != ".." && '/' !in name && '\\' !in name && '\u0000' !in name
    }
}

private object AndroidSecureLogOpener : SecureLogOpener {
    override fun open(context: Context, name: String): ParcelFileDescriptor? {
        if (!SecureLogFileProvider.safeName(name)) return null
        val opened = ArrayList<ParcelFileDescriptor>(2)
        var leaf: java.io.FileDescriptor? = null
        return try {
            opened += openDirectory(context.cacheDir.path) ?: throw FileNotFoundException()
            opened += openDirectory(child(opened.last(), "share")) ?: throw FileNotFoundException()
            leaf = Os.open(
                child(opened.last(), name),
                OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK,
                0,
            )
            val stat = Os.fstat(leaf)
            if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_nlink != 1L) throw FileNotFoundException()
            ParcelFileDescriptor.dup(leaf)
        } catch (error: Throwable) {
            if (error is Error) throw error
            null
        } finally {
            leaf?.let { runCatching { Os.close(it) } }
            opened.asReversed().forEach { runCatching { it.close() } }
        }
    }

    private fun openDirectory(path: String): ParcelFileDescriptor? {
        val raw = try {
            Os.open(
                path,
                OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK,
                0,
            )
        } catch (_: Exception) {
            return null
        }
        return try {
            if (!OsConstants.S_ISDIR(Os.fstat(raw).st_mode)) null else ParcelFileDescriptor.dup(raw)
        } finally {
            Os.close(raw)
        }
    }

    private fun child(parent: ParcelFileDescriptor, name: String): String = "/proc/self/fd/${parent.fd}/$name"
}
