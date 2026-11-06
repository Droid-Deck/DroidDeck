package com.droiddeck.launcher.session

import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.droiddeck.launcher.R
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.ArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object SessionLogShare {
    data class Details(val sizeBytes: Long, val modifiedAt: Long)
    internal class EnhancedSave(
        val outputName: String,
        val sources: List<EnhancedSaveSource>,
    )

    internal data class EnhancedSaveSource(
        val path: String,
        val size: Long,
        val sha256: String,
    )

    private val main = Handler(Looper.getMainLooper())

    var progress by mutableStateOf<Float?>(null)
        private set

    fun prepare(context: Context, folder: () -> File?, done: (File?) -> Unit) {
        if (progress != null) return
        progress = 0f
        Thread({
            val zip = runCatching { folder()?.let { dir -> zipFolder(context, dir) { p -> main.post { if (progress != null) progress = p } } } }
                .onFailure { Log.w("SessionLogShare", "could not package session logs", it) }
                .getOrNull()
            main.post { progress = null; done(zip) }
        }, "share-logs").start()
    }

    fun latest(context: Context): File? = sessions(context).lastOrNull()

    fun sessions(context: Context): List<File> {
        val current = SessionPaths.current()?.takeIf { it.isDirectory && SessionPaths.isSessionFolder(it) }
        return (SessionPaths.sessionFolders(context) + listOfNotNull(current))
            .distinctBy { it.absoluteFile.toPath().normalize().toString() }
            .sortedWith(SessionPaths.chronological)
    }

    fun details(folder: File): Details {
        var size = 0L
        var modified = folder.lastModified()
        var visited = 0
        val root = folder.toPath()
        if (folder.isDirectory && !Files.isSymbolicLink(root)) runCatching {
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                    if (++visited > 16_384 || dir.nameCount - root.nameCount > 32) FileVisitResult.SKIP_SUBTREE
                    else FileVisitResult.CONTINUE

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (++visited > 16_384) return FileVisitResult.TERMINATE
                    if (attrs.isRegularFile && !attrs.isSymbolicLink) {
                        size = if (attrs.size() > Long.MAX_VALUE - size) Long.MAX_VALUE else size + attrs.size()
                        modified = maxOf(modified, attrs.lastModifiedTime().toMillis())
                    }
                    return FileVisitResult.CONTINUE
                }
            })
        }
        return Details(size, modified)
    }

    fun deleteSession(context: Context, folder: File): Boolean {
        if (runCatching { SessionPaths.current()?.canonicalFile == folder.canonicalFile }.getOrDefault(false)) return false
        val knownSession = runCatching { folder.canonicalFile }.getOrNull() in sessions(context).mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        return knownSession && folder.isDirectory && SecureDiagnosticOutput.deleteSession(context, folder)
    }

    fun prepareEnhanced(context: Context, folder: () -> File?, done: (List<File>) -> Unit) {
        if (progress != null) return
        progress = 0f
        Thread({
            val zips = runCatching {
                folder()?.let { dir ->
                    EnhancedSessionLogShare.create(context, dir, onProgress = { p ->
                        main.post { if (progress != null) progress = p }
                    })
                }.orEmpty()
            }.onFailure { Log.w("SessionLogShare", "could not package enhanced diagnostics", it) }
                .getOrDefault(emptyList())
            main.post { progress = null; done(zips) }
        }, "share-diagnostics").start()
    }


    fun zipFolder(context: Context, folder: File, out: File = shareDir(context), onProgress: ((Float) -> Unit)? = null): File? {
        return EnhancedSessionLogShare.createLegacy(context, folder, onProgress, out)
    }

    const val STORES_DIR = "stores"

    internal fun shareDir(context: Context): File = File(context.cacheDir, "share")

    private val enhancedArchiveName = Regex("""^(.+)-(session|graphics|games)\.zip$""")
    private val sha256Name = Regex("""^[0-9a-f]{64}$""")
    private val saveDestinationValidatorOverride = object : ThreadLocal<((Context, Uri) -> Boolean)?>() {
        override fun initialValue(): ((Context, Uri) -> Boolean)? = null
    }
    // Enhanced capture samples 64 MiB of raw input. Allow substantial redaction and ZIP expansion,
    // while still refusing a same-UID guest's replacement of a cache bundle with an unbounded file.
    private const val MAX_ENHANCED_SAVE_SOURCE_BYTES = 512L * 1024 * 1024

    private data class EnhancedSource(
        val root: Path,
        val relative: Path,
        val name: String,
        val session: String,
        val category: String,
    )

    private data class OpenEnhancedSource(
        val source: EnhancedSource,
        val channel: SeekableByteChannel,
        val size: Long,
    )

    private fun enhancedSources(context: Context, zips: List<File>): List<EnhancedSource> {
        require(zips.size == 1 || zips.size == 3) { "Expected one diagnostic bundle or all three" }
        val root = shareDir(context).toPath().toAbsolutePath().normalize()
        val sources = zips.map { source ->
            val path = source.toPath().toAbsolutePath().normalize()
            require(path.parent == root && path.fileName != null) { "Diagnostic bundle is outside the share cache" }
            val name = path.fileName.toString()
            require('\\' !in name) { "Diagnostic bundle name is invalid" }
            val match = enhancedArchiveName.matchEntire(name) ?: error("Unexpected diagnostic bundle name")
            EnhancedSource(root, root.relativize(path), name, match.groupValues[1], match.groupValues[2])
        }
        require(sources.distinctBy { it.relative }.size == sources.size) { "Duplicate diagnostic bundle" }
        require(sources.map { it.session }.distinct().size == 1) { "Diagnostic bundles belong to different sessions" }
        if (sources.size == 3) {
            require(sources.map { it.category }.toSet() == setOf("session", "graphics", "games")) {
                "Expected Session, Graphics, and Games bundles"
            }
        }
        return sources
    }

    private fun withEnhancedChannels(
        sources: List<EnhancedSource>,
        index: Int = 0,
        opened: MutableList<OpenEnhancedSource> = ArrayList(),
        block: (List<OpenEnhancedSource>) -> Unit,
    ): Boolean {
        if (index == sources.size) {
            var total = 0L
            for (source in opened) {
                require(source.size <= MAX_ENHANCED_SAVE_SOURCE_BYTES - total) {
                    "Diagnostic bundles exceed the 512 MiB save limit; save one bundle instead"
                }
                total += source.size
            }
            block(opened)
            return true
        }
        val source = sources[index]
        return EnhancedSessionLogShare.withSecureChannel(source.root, source.relative) { channel ->
            val size = channel.size()
            require(size in 1..MAX_ENHANCED_SAVE_SOURCE_BYTES) {
                "Diagnostic bundle exceeds the 512 MiB save limit; share it instead"
            }
            opened += OpenEnhancedSource(source, channel, size)
            try {
                withEnhancedChannels(sources, index + 1, opened, block)
            } finally {
                opened.removeAt(opened.lastIndex)
            }
        } == true
    }

    private fun digestExact(channel: SeekableByteChannel, size: Long): String {
        channel.position(0)
        var remaining = size
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(64 * 1024)
        while (remaining > 0) {
            buffer.clear()
            buffer.limit(minOf(buffer.capacity().toLong(), remaining).toInt())
            val read = channel.read(buffer)
            require(read > 0) { "Diagnostic bundle changed while reading" }
            digest.update(buffer.array(), 0, read)
            remaining -= read
        }
        require(channel.size() == size) { "Diagnostic bundle changed while reading" }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun copyExact(channel: SeekableByteChannel, size: Long, output: OutputStream): String {
        channel.position(0)
        var remaining = size
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(64 * 1024)
        while (remaining > 0) {
            buffer.clear()
            buffer.limit(minOf(buffer.capacity().toLong(), remaining).toInt())
            val read = channel.read(buffer)
            require(read > 0) { "Diagnostic bundle changed while saving" }
            output.write(buffer.array(), 0, read)
            digest.update(buffer.array(), 0, read)
            remaining -= read
        }
        require(channel.size() == size) { "Diagnostic bundle changed while saving" }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun enhancedSaveName(context: Context, zips: List<File>): String {
        val sources = enhancedSources(context, zips)
        if (sources.size == 1) return sources.single().name
        val base = sources.first().session
        return "$base-all.zip"
    }

    internal fun writeEnhancedSave(context: Context, zips: List<File>, output: OutputStream) {
        val sources = enhancedSources(context, zips)
        require(withEnhancedChannels(sources) { opened ->
            if (opened.size == 1) {
                val source = opened.single()
                copyExact(source.channel, source.size, output)
            } else ZipOutputStream(output.buffered()).use { archive ->
                archive.setLevel(java.util.zip.Deflater.NO_COMPRESSION)
                for (source in opened.sortedBy { it.source.name }) {
                    archive.putNextEntry(ZipEntry(source.source.name).apply { time = 0L })
                    copyExact(source.channel, source.size, archive)
                    archive.closeEntry()
                }
            }
        }) { "Diagnostic bundle is unsafe or unavailable" }
    }

    internal fun prepareEnhancedSave(context: Context, zips: List<File>): EnhancedSave {
        val sources = enhancedSources(context, zips)
        var prepared: List<EnhancedSaveSource>? = null
        require(withEnhancedChannels(sources) { opened ->
            prepared = opened.map {
                EnhancedSaveSource(
                    it.source.root.resolve(it.source.relative).toString(),
                    it.size,
                    digestExact(it.channel, it.size),
                )
            }
        }) { "Diagnostic bundle is unsafe or unavailable" }
        return EnhancedSave(enhancedSaveName(context, zips), prepared ?: error("Could not prepare diagnostic save"))
    }

    internal fun saveState(save: EnhancedSave): ArrayList<String> = arrayListOf<String>().apply {
        add("v1")
        add(save.outputName)
        add(save.sources.size.toString())
        save.sources.forEach {
            add(it.path)
            add(it.size.toString())
            add(it.sha256)
        }
    }

    internal fun restoreSave(context: Context, state: ArrayList<String>?): EnhancedSave? {
        if (state == null || state.size < 6 || state[0] != "v1") return null
        val count = state[2].toIntOrNull()?.takeIf { it == 1 || it == 3 } ?: return null
        if (state.size != 3 + count * 3) return null
        val sources = ArrayList<EnhancedSaveSource>(count)
        var total = 0L
        for (index in 0 until count) {
            val at = 3 + index * 3
            val size = state[at + 1].toLongOrNull()?.takeIf { it in 1..MAX_ENHANCED_SAVE_SOURCE_BYTES }
                ?: return null
            if (size > MAX_ENHANCED_SAVE_SOURCE_BYTES - total) return null
            total += size
            val sha256 = state[at + 2].takeIf(sha256Name::matches) ?: return null
            sources += EnhancedSaveSource(state[at], size, sha256)
        }
        val expectedName = runCatching { enhancedSaveName(context, sources.map { File(it.path) }) }.getOrNull()
            ?: return null
        if (state[1] != expectedName) return null
        return EnhancedSave(expectedName, sources)
    }

    internal fun setSaveDestinationValidatorForTest(validator: ((Context, Uri) -> Boolean)?) {
        if (validator == null) saveDestinationValidatorOverride.remove()
        else saveDestinationValidatorOverride.set(validator)
    }

    internal fun validSaveDestination(context: Context, destination: Uri): Boolean {
        saveDestinationValidatorOverride.get()?.let { return it(context, destination) }
        if (destination.scheme != ContentResolver.SCHEME_CONTENT ||
            !runCatching { DocumentsContract.isDocumentUri(context, destination) }.getOrDefault(false)) return false
        val authority = destination.authority ?: return false
        val provider = runCatching { context.packageManager.resolveContentProvider(authority, 0) }.getOrNull() ?: return false
        return provider.packageName.isNotBlank() && provider.packageName != context.packageName
    }

    private fun removePartialDocument(context: Context, destination: Uri) {
        val removed = runCatching { context.contentResolver.delete(destination, null, null) > 0 }.getOrDefault(false)
        if (!removed) runCatching { context.contentResolver.openOutputStream(destination, "rwt")?.close() }
    }

    internal fun saveEnhanced(context: Context, save: EnhancedSave, destination: Uri) {
        require(validSaveDestination(context, destination)) { "Invalid diagnostic save destination" }
        val restored = restoreSave(context, saveState(save)) ?: error("Invalid diagnostic save state")
        val sources = enhancedSources(context, restored.sources.map { File(it.path) })
        var destinationOpened = false
        try {
            require(withEnhancedChannels(sources) { opened ->
                val expected = restored.sources.associateBy { File(it.path).toPath().toAbsolutePath().normalize() }
                for (source in opened) {
                    val path = source.source.root.resolve(source.source.relative).toAbsolutePath().normalize()
                    val record = expected[path] ?: error("Diagnostic save source changed")
                    require(source.size == record.size && digestExact(source.channel, source.size) == record.sha256) {
                        "Diagnostic save source changed"
                    }
                }
                context.contentResolver.openOutputStream(destination, "w")?.use { output ->
                    destinationOpened = true
                    if (opened.size == 1) {
                        val source = opened.single()
                        val path = source.source.root.resolve(source.source.relative).toAbsolutePath().normalize()
                        require(copyExact(source.channel, source.size, output) == expected.getValue(path).sha256)
                    } else ZipOutputStream(output.buffered()).use { archive ->
                        archive.setLevel(java.util.zip.Deflater.NO_COMPRESSION)
                        for (source in opened.sortedBy { it.source.name }) {
                            val path = source.source.root.resolve(source.source.relative).toAbsolutePath().normalize()
                            archive.putNextEntry(ZipEntry(source.source.name).apply { time = 0L })
                            require(copyExact(source.channel, source.size, archive) == expected.getValue(path).sha256)
                            archive.closeEntry()
                        }
                    }
                } ?: error("Could not open diagnostic bundle destination")
            }) { "Diagnostic bundle is unsafe or unavailable" }
        } catch (error: Exception) {
            if (destinationOpened) removePartialDocument(context, destination)
            throw error
        }
    }

    fun saveLegacyAsync(context: Context, folder: File, destination: Uri, done: (Boolean) -> Unit) {
        if (progress != null) return
        progress = 0f
        val app = context.applicationContext
        Thread({
            val saved = runCatching {
                saveLegacy(app, folder, destination) { value ->
                    main.post { if (progress != null) progress = value }
                }
            }.isSuccess
            main.post {
                progress = null
                done(saved)
            }
        }, "save-session-logs").start()
    }

    internal fun saveLegacy(
        context: Context,
        folder: File,
        destination: Uri,
        onProgress: ((Float) -> Unit)? = null,
    ) {
        require(validSaveDestination(context, destination)) { "Invalid diagnostic save destination" }
        var destinationOpened = false
        try {
            context.contentResolver.openOutputStream(destination, "w")?.use { output ->
                destinationOpened = true
                require(EnhancedSessionLogShare.writeLegacy(context, folder, output, onProgress)) {
                    "Session log folder is unsafe or empty"
                }
            } ?: error("Could not open diagnostic bundle destination")
        } catch (error: Exception) {
            if (destinationOpened) removePartialDocument(context, destination)
            throw error
        }
    }

    fun clear(context: Context) {
        SecureDiagnosticOutput.clear(context, shareDir(context))
        SecureDiagnosticOutput.clear(context, File(context.cacheDir, "shared-logs"))
    }

    fun shareIntent(context: Context, zip: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".logs", zip)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, zip.nameWithoutExtension)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(zip.name, uri)
        return Intent.createChooser(send, context.getString(R.string.logshare_chooser)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun shareEnhancedIntent(context: Context, zips: List<File>): Intent {
        require(zips.isNotEmpty())
        val uris = ArrayList(zips.map { FileProvider.getUriForFile(context, context.packageName + ".logs", it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("application/zip")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .putExtra(Intent.EXTRA_SUBJECT, zips.first().name.substringBeforeLast('-'))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newUri(context.contentResolver, zips.first().name, uris.first()).also { clip ->
            for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
        }
        return Intent.createChooser(send, context.getString(R.string.logshare_chooser))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
