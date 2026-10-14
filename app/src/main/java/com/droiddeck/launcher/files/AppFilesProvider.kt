package com.droiddeck.launcher.files

import android.content.Context
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.droiddeck.launcher.R
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

class AppFilesProvider : DocumentsProvider() {
    private lateinit var base: File
    private lateinit var baseCanonical: String

    override fun attachInfo(context: Context, info: ProviderInfo) {
        super.attachInfo(context, info)
        base = context.dataDir
        baseCanonical = base.canonicalPath
    }

    override fun onCreate() = true

    override fun queryRoots(projection: Array<String>?): Cursor {
        val ctx = context!!
        val result = MatrixCursor(projection ?: ROOT_COLUMNS)
        result.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, base.absolutePath)
            add(Root.COLUMN_TITLE, titleFor(ctx))
            add(Root.COLUMN_SUMMARY, null)
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_SEARCH or Root.FLAG_SUPPORTS_IS_CHILD)
            add(Root.COLUMN_MIME_TYPES, "*/*")
            add(Root.COLUMN_AVAILABLE_BYTES, base.freeSpace)
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
        }
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).also { addRow(it, fileFor(documentId)) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<String>?, sortOrder: String?): Cursor {
        val result = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        fileFor(parentDocumentId).listFiles()?.filter { inside(it) }?.forEach { addRow(result, it) }
        return result
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileFor(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun openDocumentThumbnail(documentId: String, sizeHint: Point?, signal: CancellationSignal?): AssetFileDescriptor {
        val file = fileFor(documentId)
        return AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0, file.length())
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = fileFor(parentDocumentId)
        val name = displayName.substringAfterLast('/')
        var file = File(parent, name)
        var n = 2
        while (file.exists()) file = File(parent, "$name (${n++})")
        val made = try {
            if (mimeType == Document.MIME_TYPE_DIR) file.mkdir() else file.createNewFile()
        } catch (e: IOException) {
            false
        }
        if (!made) throw FileNotFoundException("could not create ${file.path}")
        return file.absolutePath
    }

    override fun deleteDocument(documentId: String) {
        val file = fileFor(documentId)
        if (file.absolutePath == base.absolutePath) throw FileNotFoundException("the app folder itself is not deleted")
        try {
            deleteTree(file.toPath())
        } catch (e: IOException) {
            throw FileNotFoundException("could not delete $documentId: ${e.message}")
        }
    }

    private fun deleteTree(root: Path) {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(root)
            return
        }
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    override fun removeDocument(documentId: String, parentDocumentId: String) = deleteDocument(documentId)

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = fileFor(documentId)
        val target = File(file.parentFile, displayName.substringAfterLast('/'))
        if (target.exists() || !file.renameTo(target)) throw FileNotFoundException("could not rename $documentId")
        return target.absolutePath
    }

    override fun moveDocument(sourceDocumentId: String, sourceParentDocumentId: String, targetParentDocumentId: String): String {
        val source = fileFor(sourceDocumentId)
        val targetParent = fileFor(targetParentDocumentId)
        if (!targetParent.isDirectory) throw FileNotFoundException("$targetParentDocumentId is not a folder")
        val target = File(targetParent, source.name)
        if (target.exists() || !source.renameTo(target)) throw FileNotFoundException("could not move $sourceDocumentId")
        return target.absolutePath
    }

    override fun getDocumentType(documentId: String): String = mimeOf(fileFor(documentId))

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = runCatching { fileFor(parentDocumentId).absolutePath }.getOrNull() ?: return false
        val child = runCatching { fileFor(documentId).absolutePath }.getOrNull() ?: return false
        return child == parent || child.startsWith("$parent/")
    }

    override fun querySearchDocuments(rootId: String, query: String, projection: Array<String>?): Cursor {
        val result = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        val needle = query.lowercase()
        val pending = ArrayDeque(listOf(base))
        var visited = 0
        while (pending.isNotEmpty() && result.count < 50 && visited++ < 20000) {
            val f = pending.removeFirst()
            if (!inside(f)) continue
            if (f.isDirectory) f.listFiles()?.let { pending.addAll(it) }
            else if (f.name.lowercase().contains(needle)) addRow(result, f)
        }
        return result
    }

    private fun fileFor(documentId: String): File {
        val f = File(documentId)
        if (!f.isAbsolute || !inside(f)) throw FileNotFoundException("$documentId is outside the app's files")
        if (!f.exists()) throw FileNotFoundException("$documentId not found")
        return f
    }

    private fun inside(f: File): Boolean {
        val abs = f.absoluteFile.normalize().path
        if (abs != base.absolutePath && !abs.startsWith(base.absolutePath + "/")) return false
        val real = runCatching { f.canonicalPath }.getOrNull() ?: return false
        return real == baseCanonical || real.startsWith("$baseCanonical/")
    }

    private fun addRow(result: MatrixCursor, file: File) {
        var flags = 0
        if (file.isDirectory) {
            if (file.canWrite()) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else if (file.canWrite()) {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        if (file.absolutePath != base.absolutePath && file.parentFile?.canWrite() == true) {
            flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or
                Document.FLAG_SUPPORTS_MOVE or Document.FLAG_SUPPORTS_REMOVE
        }
        val mime = mimeOf(file)
        if (mime.startsWith("image/")) flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL
        result.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, file.absolutePath)
            add(Document.COLUMN_DISPLAY_NAME, if (file.absolutePath == base.absolutePath) titleFor(context!!) else file.name)
            add(Document.COLUMN_SIZE, if (file.isDirectory) null else file.length())
            add(Document.COLUMN_MIME_TYPE, mime)
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    private fun mimeOf(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    companion object {
        private const val ROOT_ID = "app"

        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_MIME_TYPES, Root.COLUMN_FLAGS, Root.COLUMN_ICON,
            Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_AVAILABLE_BYTES,
        )
        private val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SIZE,
        )

        fun titleFor(context: Context): String {
            val name = context.getString(R.string.app_name)
            return when (context.packageName) {
                "com.tencent.ig" -> "$name PUBG"
                "com.antutu.benchmark.full" -> "$name AnTuTu"
                "com.ludashi.benchmark" -> "$name Ludashi"
                else -> name
            }
        }
    }
}
