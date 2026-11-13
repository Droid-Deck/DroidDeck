package com.droiddeck.launcher.glasses

import android.content.Context
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

internal object VitureSdkImport {
    internal data class Limits(
        val archiveBytes: Long = 128L * 1024 * 1024,
        val expandedBytes: Long = 256L * 1024 * 1024,
        val libraryBytes: Long = 64L * 1024 * 1024,
        val entries: Int = 4096,
        val elfMetadataBytes: Long = 32L * 1024 * 1024,
    )

    private val libraries = listOf("libglasses.so", "libcarina_vio.so", "libcloud_protocol.so")
    private val paths = libraries.associateBy { "android/arm64-v8a/$it" } + mapOf(
        "android/LICENSE" to "LICENSE", "android/NOTICE" to "NOTICE",
        "include/viture_version.h" to "viture_version.h",
    )
    private val installLock = Any()
    private val activeStages = mutableSetOf<File>()
    private val stageName = Regex("stage-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun prepare(context: Context, input: InputStream): Prepared = prepare(root(context), input)

    internal fun prepare(root: File, input: InputStream, limits: Limits = Limits()): Prepared {
        val stage = synchronized(installLock) {
            if (!root.isDirectory && !root.mkdirs()) throw IOException("Could not create SDK storage.")
            File(root.canonicalFile, "stage-${UUID.randomUUID()}").also {
                if (!it.mkdir()) throw IOException("Could not create SDK staging folder.")
                activeStages.add(it)
            }
        }
        try {
            val archive = File(stage, "archive.zip")
            LimitedInputStream(input, limits.archiveBytes).use { source ->
                archive.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkInterrupted()
                        val size = source.read(buffer)
                        if (size < 0) break
                        output.write(buffer, 0, size)
                    }
                }
            }
            val archiveEntries = ZipFile(archive).use { zip ->
                if (zip.size() > limits.entries) invalid("The SDK archive contains too many files.")
                zip.entries().asSequence().map { it.name }.toList()
            }
            if (archiveEntries.toSet().size != archiveEntries.size) invalid("The SDK archive contains duplicate files.")
            val found = mutableSetOf<String>()
            val seen = mutableSetOf<String>()
            var prefix: String? = null
            var expanded = 0L
            var entries = 0
            ZipInputStream(archive.inputStream()).use { zip ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkInterrupted()
                    val entry = zip.nextEntry ?: break
                    if (++entries > limits.entries) invalid("The SDK archive contains too many files.")
                    val name = entry.name
                    if (!safePath(name)) invalid("The SDK archive contains an unsafe path.")
                    if (!seen.add(name)) invalid("The SDK archive contains duplicate files.")
                    val expected = paths.keys.singleOrNull { name == it || name.endsWith("/$it") }
                    val outputName = expected?.let(paths::getValue)
                    if (expected != null) {
                        val parent = name.removeSuffix(expected)
                        if (prefix != null && prefix != parent) invalid("SDK files must belong to one SDK folder.")
                        prefix = parent
                        if (!found.add(outputName!!)) invalid("The SDK archive contains duplicate SDK files.")
                    }
                    val limit = if (outputName in libraries) limits.libraryBytes
                        else if (outputName != null) 1024L * 1024 else limits.expandedBytes
                    var size = 0L
                    val output = outputName?.let { File(stage, it).outputStream() }
                    try {
                        while (true) {
                            checkInterrupted()
                            val count = zip.read(buffer)
                            if (count < 0) break
                            size += count
                            expanded += count
                            if (size > limit || expanded > limits.expandedBytes) invalid("The SDK archive is too large.")
                            output?.write(buffer, 0, count)
                        }
                        output?.fd?.sync()
                    } finally { output?.close() }
                    zip.closeEntry()
                }
            }
            if (seen != archiveEntries.toSet()) invalid("The SDK archive directory is inconsistent.")
            if (!archive.delete()) throw IOException("Could not clear the staged SDK archive.")
            if (found != paths.values.toSet()) invalid("Select the complete VITURE Android SDK ZIP with ARM64 libraries, headers, LICENSE and NOTICE.")
            val version = version(File(stage, "viture_version.h").readText())
            if (!version.startsWith("2.4.")) invalid("This app supports VITURE Android SDK 2.4.x.")
            if (!File(stage, "LICENSE").readText().contains("VITURE") || File(stage, "NOTICE").length() == 0L)
                invalid("The SDK license or notices are missing.")
            libraries.forEach { Elf.validate(File(stage, it), limits.elfMetadataBytes) }
            val manifest = Properties().apply {
                setProperty("version", version)
                for (name in paths.values) setProperty(name, digest(File(stage, name)))
            }
            File(stage, "manifest.properties").outputStream().use { output ->
                manifest.store(output, null)
                output.fd.sync()
            }
            return Prepared(root, stage, version, libraries.sumOf { File(stage, it).length() })
        } catch (error: Throwable) {
            synchronized(installLock) {
                activeStages.remove(stage)
                deleteStage(stage)
            }
            throw error
        }
    }

    fun installedDirectory(context: Context): File? = installedDirectory(root(context))

    internal fun installedDirectory(root: File): File? = synchronized(installLock) {
        recover(root)
        val current = File(root, "current")
        if (validInstall(current)) current else null
    }

    private fun recover(root: File) {
        val current = File(root, "current")
        val backup = File(root, "previous")
        val pending = File(root, "activation.pending")
        if (pending.exists()) {
            if (current.exists() && !current.deleteRecursively()) throw IOException("Could not discard the interrupted SDK import.")
            // Remove the marker before restoring the backup so recovery can safely repeat after process death.
            if (!pending.delete()) throw IOException("Could not clear the interrupted SDK import.")
        }
        if (!current.exists() && backup.exists() && (!backup.isDirectory || !backup.renameTo(current)))
            throw IOException("Could not recover the previous SDK.")
        root.canonicalFile.listFiles()?.filter { stageName.matches(it.name) && it !in activeStages }
            ?.forEach(::deleteStage)
    }

    private fun deleteStage(directory: File) {
        try {
            Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(directory: Path, error: IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.delete(directory)
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (_: IOException) {
            // Recovery retries files that could not be removed without blocking the installed SDK.
        }
    }

    private fun validInstall(directory: File): Boolean = runCatching {
        val record = File(directory, "manifest.properties")
        if (!record.isFile || record.length() > 8192) return false
        val manifest = Properties().apply { record.inputStream().use { load(it) } }
        manifest.getProperty("version")?.matches(Regex("2\\.4\\.\\d+")) == true && paths.values.all { name ->
            val file = File(directory, name)
            file.isFile && file.length() in 1..(64L * 1024 * 1024) && manifest.getProperty(name) == digest(file)
        }
    }.getOrDefault(false)

    internal class Prepared internal constructor(
        private val root: File,
        val directory: File,
        val version: String,
        val libraryBytes: Long,
    ) : Closeable {
        private var consumed = false

        fun install(activate: (File) -> Boolean): File = synchronized(installLock) {
            check(!consumed) { "This SDK import has already been used." }
            checkInterrupted()
            if (!validInstall(directory)) invalid("The prepared SDK files have changed. Select the archive again.")
            recover(root)
            val current = File(root, "current")
            val backup = File(root, "previous")
            val pending = File(root, "activation.pending")
            if (backup.exists() && !backup.deleteRecursively()) throw IOException("Could not clear the previous SDK backup.")
            if (current.exists() && !current.renameTo(backup)) throw IOException("Could not preserve the installed SDK.")
            var promoted = false
            try {
                pending.outputStream().use { output -> output.write(1); output.fd.sync() }
                if (!directory.renameTo(current)) throw IOException("Could not install the SDK.")
                promoted = true
                activeStages.remove(directory)
                if (!activate(current)) throw IOException("This SDK could not be loaded on this device.")
                if (!pending.delete()) throw IOException("Could not finish installing the SDK.")
            } catch (error: Throwable) {
                if (promoted) consumed = true
                try { recover(root) } catch (recovery: Throwable) { error.addSuppressed(recovery) }
                throw error
            }
            consumed = true
            backup.deleteRecursively()
            current
        }

        override fun close() = synchronized(installLock) {
            if (!consumed) {
                consumed = true
                activeStages.remove(directory)
                deleteStage(directory)
            }
            Unit
        }
    }

    private fun root(context: Context) = File(context.applicationContext.noBackupFilesDir, "viture-sdk")

    private fun safePath(name: String): Boolean = name.isNotEmpty() && name.length <= 1024 &&
        !name.startsWith('/') && !name.contains('\\') && !name.contains(':') && !name.contains('\u0000') &&
        name.trimEnd('/').split('/').none { it.isEmpty() || it == "." || it == ".." }

    private fun version(header: String): String = listOf("MAJOR", "MINOR", "PATCH").joinToString(".") { part ->
        val matches = Regex("(?m)^\\s*#define\\s+VITURE_VERSION_$part\\s+(\\d+)\\s*$").findAll(header).toList()
        if (matches.size != 1) invalid("The SDK version header is invalid.")
        matches.single().groupValues[1].toIntOrNull()?.toString() ?: invalid("The SDK version is invalid.")
    }

    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkInterrupted()
                val size = input.read(buffer)
                if (size < 0) break
                hash.update(buffer, 0, size)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("SDK import cancelled.")
    }

    private fun invalid(message: String): Nothing = throw IOException(message)

    private class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var total = 0L
        override fun read(): Int = `in`.read().also { if (it >= 0) count(1) }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            `in`.read(buffer, offset, length).also { if (it > 0) count(it) }
        private fun count(size: Int) {
            total += size
            if (total > limit) invalid("The SDK archive is too large.")
        }
    }

    internal object Elf {
        private data class Section(val type: Int, val offset: Long, val size: Long, val link: Int, val stride: Long)
        private val requiredSymbols = mapOf(
            "libglasses.so" to setOf("GetVersionString", "xr_device_provider_create", "xr_device_provider_initialize",
                "xr_device_provider_destroy", "xr_device_provider_get_gl_pose_carina", "xr_device_provider_register_callbacks_carina"),
            "libcarina_vio.so" to setOf("carina_init", "carina_start", "carina_stop", "carina_vio_get_gl_pose"),
        )

        fun validate(file: File, metadataLimit: Long = 32L * 1024 * 1024) = RandomAccessFile(file, "r").use { input ->
            var metadataBytes = 0L
            fun consumeMetadata(size: Long) {
                checkInterrupted()
                if (size < 0 || size > metadataLimit - metadataBytes) invalid("${file.name} has excessive ELF metadata.")
                metadataBytes += size
            }
            fun read(offset: Long, size: Int): ByteBuffer {
                checkInterrupted()
                if (offset < 0 || size < 0 || offset > input.length() - size) invalid("${file.name} is truncated.")
                val data = ByteArray(size)
                input.seek(offset)
                input.readFully(data)
                return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            }
            val header = read(0, 64)
            if (header.getInt(0) != 0x464c457f || header.get(4).toInt() != 2 || header.get(5).toInt() != 1 ||
                header.get(6).toInt() != 1 || header.getShort(16).toInt() != 3 || header.getShort(18).toInt() != 183 ||
                header.getInt(20) != 1 || header.getShort(52).toInt() != 64)
                invalid("${file.name} is not an Android ARM64 shared library.")
            val programOffset = header.getLong(32)
            val programStride = header.getShort(54).toInt() and 0xffff
            val programCount = header.getShort(56).toInt() and 0xffff
            if (programStride != 56 || programCount !in 1..256) invalid("${file.name} has invalid program headers.")
            var loadable = false
            repeat(programCount) { index ->
                val program = read(programOffset + index.toLong() * programStride, programStride)
                if (program.getInt(0) == 1) {
                    val offset = program.getLong(8)
                    val size = program.getLong(32)
                    if (offset < 0 || size < 0 || offset > input.length() - size || program.getLong(40) < size)
                        invalid("${file.name} has invalid load segments.")
                    loadable = true
                }
            }
            if (!loadable) invalid("${file.name} has no loadable code.")
            val sectionOffset = header.getLong(40)
            val sectionStride = header.getShort(58).toInt() and 0xffff
            val sectionCount = header.getShort(60).toInt() and 0xffff
            if (sectionStride != 64 || sectionCount !in 1..4096) invalid("${file.name} has invalid section headers.")
            val sections = List(sectionCount) { index ->
                val section = read(sectionOffset + index.toLong() * sectionStride, sectionStride)
                Section(section.getInt(4), section.getLong(24), section.getLong(32), section.getInt(40), section.getLong(56))
            }
            fun bytes(section: Section): ByteBuffer {
                if (section.size !in 1..(8L * 1024 * 1024)) invalid("${file.name} has an invalid symbol table.")
                consumeMetadata(section.size)
                return read(section.offset, section.size.toInt())
            }
            fun strings(section: Section): ByteBuffer {
                val linked = sections.getOrNull(section.link) ?: invalid("${file.name} has invalid symbol strings.")
                if (linked.type != 3) invalid("${file.name} has invalid symbol strings.")
                return bytes(linked)
            }
            fun string(table: ByteBuffer, offset: Int): String {
                checkInterrupted()
                if (offset !in 0 until table.limit()) invalid("${file.name} has an invalid symbol name.")
                var end = offset
                while (end < table.limit() && table.get(end).toInt() != 0 && end - offset <= 4096) end++
                if (end == table.limit() || end - offset > 4096) invalid("${file.name} has an invalid symbol name.")
                consumeMetadata((end - offset).toLong())
                return String(table.array(), offset, end - offset, Charsets.UTF_8)
            }
            val required = requiredSymbols[file.name].orEmpty().toMutableSet()
            var hasSymbols = false
            for (section in sections.filter { it.type == 11 }) {
                if (section.stride != 24L || section.size % 24 != 0L) invalid("${file.name} has invalid exported symbols.")
                val symbols = bytes(section)
                val names = strings(section)
                for (offset in 0 until symbols.limit() step 24) {
                    checkInterrupted()
                    val info = symbols.get(offset + 4).toInt() and 0xff
                    val visibility = symbols.get(offset + 5).toInt() and 3
                    if (symbols.getShort(offset + 6).toInt() != 0 && info shr 4 in 1..2 &&
                        (info and 15) == 2 && visibility in listOf(0, 3)) {
                        hasSymbols = true
                        required.remove(string(names, symbols.getInt(offset)))
                    }
                }
            }
            if (!hasSymbols || required.isNotEmpty()) invalid("${file.name} does not provide the required VITURE SDK functions.")
            var soname: String? = null
            for (section in sections.filter { it.type == 6 }) {
                if (section.stride != 16L || section.size % 16 != 0L) invalid("${file.name} has invalid dynamic metadata.")
                val dynamic = bytes(section)
                val names = strings(section)
                for (offset in 0 until dynamic.limit() step 16) if (dynamic.getLong(offset) == 14L) {
                    val index = dynamic.getLong(offset + 8)
                    if (index !in 0..Int.MAX_VALUE.toLong()) invalid("${file.name} has an invalid library name.")
                    soname = string(names, index.toInt())
                }
            }
            if (soname != file.name) invalid("${file.name} has an unexpected library identity.")
        }
    }
}
