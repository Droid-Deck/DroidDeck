package com.droiddeck.launcher.session

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.channels.FileChannel
import java.util.UUID

internal interface DiagnosticOutputDirectory : AutoCloseable {
    val file: File
    fun clear()
    fun child(name: String): DiagnosticOutputDirectory
    fun contains(name: String): Boolean
    fun containsRegular(name: String): Boolean
    fun readSmallRegular(name: String, maxBytes: Int): ByteArray?
    fun write(name: String, body: (OutputStream) -> Unit): File
    fun writeAtomic(name: String, body: (OutputStream) -> Unit): File
    fun deleteIfEmpty(): Boolean
}

internal interface DiagnosticOutputProvider {
    fun open(context: Context, requested: File): DiagnosticOutputDirectory?
    fun cleanupLegacyStages(context: Context) = Unit
    fun <T> withExistingDirectory(root: File, block: (SecureDirectoryStream<Path>) -> T): T?
}

internal object SecureDiagnosticOutput {
    private val providerOverride = object : ThreadLocal<DiagnosticOutputProvider?>() {
        override fun initialValue(): DiagnosticOutputProvider? = null
    }

    internal fun setProviderForTest(provider: DiagnosticOutputProvider?) {
        if (provider == null) providerOverride.remove() else providerOverride.set(provider)
    }

    fun open(context: Context, requested: File): DiagnosticOutputDirectory? {
        providerOverride.get()?.let { return it.open(context, requested) }
        return AndroidDiagnosticOutputProvider.open(context, requested)
    }

    fun clear(context: Context, requested: File): Boolean = try {
        open(context, requested)?.use {
            it.clear()
            it.deleteIfEmpty()
        } ?: false
    } catch (_: Exception) {
        false
    }

    fun deleteSession(context: Context, folder: File): Boolean {
        val target = folder.toPath().toAbsolutePath().normalize()
        val persistent = LinuxRuntime.logDir(context).toPath().toAbsolutePath().normalize()
        val ephemeral = File(context.cacheDir, "session-logs").toPath().toAbsolutePath().normalize()
        if (target.parent !in setOf(persistent, ephemeral) || !SessionPaths.isSessionFolder(folder)) return false
        return try {
            open(context, folder)?.use {
                it.clear()
                it.deleteIfEmpty()
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    fun deleteTools(context: Context): Boolean {
        val tools = File(LinuxRuntime.logDir(context), SessionPaths.TOOLS_DIR)
        return try {
            open(context, tools)?.use {
                it.clear()
                it.deleteIfEmpty()
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    fun cleanupLegacyStages(context: Context) {
        try {
            providerOverride.get()?.let {
                it.cleanupLegacyStages(context)
                return
            }
            AndroidDiagnosticOutputProvider.cleanupLegacyStages(context)
        } catch (_: Exception) {
            // Cleanup is best effort; traversal limits must not abort application startup.
        }
    }

    fun <T> withExistingDirectory(root: File, block: (SecureDirectoryStream<Path>) -> T): T? {
        if (root.toPath().toAbsolutePath().normalize() !=
            LinuxRuntime.legacyLogDir().toPath().toAbsolutePath().normalize()) return null
        providerOverride.get()?.let { return it.withExistingDirectory(root, block) }
        return AndroidDiagnosticOutputProvider.withExistingDirectory(root, block)
    }
}

private object AndroidDiagnosticOutputProvider : DiagnosticOutputProvider {
    private const val DIRECTORY_MODE = 0x1c0
    private const val FILE_MODE = 0x180
    private const val STAGING_DIR = "diagnostic-staging"
    private val stagingLock = Any()
    private val legacyStage = Regex("""^.+-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.diagnostic-stage$""")
    private val stagedOutput = Regex("""^\.diagnostic-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.partial$""")

    override fun open(context: Context, requested: File): DiagnosticOutputDirectory? {
        val wanted = requested.toPath().toAbsolutePath().normalize()
        val cache = context.cacheDir.toPath().toAbsolutePath().normalize()
        if (wanted == cache.resolve("share") || wanted == cache.resolve("shared-logs")) {
            return open(context, cache.toFile(), listOf(wanted.fileName.toString()), create = true)
        }
        val logs = LinuxRuntime.logDir(context).toPath().toAbsolutePath().normalize()
        val ephemeral = cache.resolve("session-logs")
        if (wanted == logs) return open(context, context.filesDir, listOf(logs.fileName.toString()), create = true)
        if ((wanted.parent == logs || wanted.parent == ephemeral) && SessionPaths.isSessionName(requested.name)) {
            return open(context, wanted.parent.toFile(), listOf(wanted.fileName.toString()), create = true)
        }
        if (wanted == logs.resolve(SessionPaths.TOOLS_DIR)) {
            return open(context, logs.toFile(), listOf(SessionPaths.TOOLS_DIR), create = true)
        }
        val external = context.getExternalFilesDir(null)?.toPath()?.toAbsolutePath()?.normalize() ?: return null
        val agentLogs = external.resolve("agent-logs")
        return if (wanted.parent == agentLogs && safeName(wanted.fileName.toString())) {
            open(
                context, external.toFile(), listOf("agent-logs", wanted.fileName.toString()), create = true,
                atomicMigrationAllowed = false,
            )
        } else null
    }

    override fun <T> withExistingDirectory(root: File, block: (SecureDirectoryStream<Path>) -> T): T? {
        val descriptor = openDirectory(root.path) ?: return null
        return try {
            val stream = secureStream(descriptor) ?: return null
            stream.use(block)
        } finally {
            descriptor.close()
        }
    }

    override fun cleanupLegacyStages(context: Context) {
        cleanupInternalStaging(context)
        val cache = openDirectory(context.cacheDir.path) ?: return
        try {
            val stream = secureStream(cache) ?: return
            stream.use { root ->
                val iterator = root.iterator()
                var visited = 0
                while (iterator.hasNext() && visited++ < 16_384) {
                    val name = iterator.next().fileName
                    if (legacyStage.matches(name.toString())) cleanupStage(root, name)
                }
                val share = runCatching {
                    root.newDirectoryStream(Paths.get("share"), LinkOption.NOFOLLOW_LINKS)
                }.getOrNull()
                share?.use { cleanupStage(it, Paths.get(".diagnostic-stage")) }
            }
        } finally {
            cache.close()
        }
    }

    private fun cleanupStage(parent: SecureDirectoryStream<Path>, name: Path) {
        val child = runCatching { parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS) }.getOrNull()
            ?: return
        val originalKey = runCatching {
            child.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes().fileKey()
        }.getOrNull()
        if (originalKey == null) {
            child.close()
            return
        }
        val activeExists = try {
            child.getFileAttributeView(
                Paths.get(".active"), BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS,
            ).readAttributes()
            true
        } catch (_: NoSuchFileException) {
            false
        } catch (_: Exception) {
            child.close()
            return
        }
        val active = if (activeExists) runCatching {
            child.newByteChannel(
                Paths.get(".active"),
                setOf(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
            )
        }.getOrNull() else null
        val lock = (active as? FileChannel)?.let { runCatching { it.tryLock() }.getOrNull() }
        if (activeExists && lock == null) {
            runCatching { active?.close() }
            child.close()
            return
        }
        try {
            val cleared = try {
                clear(child, 0, intArrayOf(0))
                true
            } catch (_: Exception) {
                false
            }
            child.close()
            if (!cleared) return
            val currentKey = runCatching {
                parent.getFileAttributeView(name, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                    .readAttributes().fileKey()
            }.getOrNull()
            if (currentKey == originalKey) runCatching { parent.deleteDirectory(name) }
        } finally {
            runCatching { lock?.close() }
            runCatching { active?.close() }
            runCatching { child.close() }
        }
    }

    private fun open(
        context: Context,
        root: File,
        parts: List<String>,
        create: Boolean,
        atomicMigrationAllowed: Boolean = true,
    ): DiagnosticOutputDirectory? {
        if (parts.any { !safeName(it) }) return null
        val descriptors = ArrayList<ParcelFileDescriptor>()
        return try {
            descriptors += openDirectory(root.path) ?: throw IOException("Diagnostic output root is unavailable")
            for (part in parts) {
                val child = childPath(descriptors.last(), part)
                if (create) try {
                    Os.mkdir(child, DIRECTORY_MODE)
                } catch (error: ErrnoException) {
                    if (error.errno != OsConstants.EEXIST) throw error
                }
                descriptors += openDirectory(child) ?: throw IOException("Diagnostic output directory is unavailable")
            }
            AndroidDiagnosticOutputDirectory(
                parts.fold(root) { parent, child -> File(parent, child) }, descriptors, parts.lastOrNull(),
                context, atomicMigrationAllowed,
            )
        } catch (error: Throwable) {
            descriptors.asReversed().forEach { runCatching { it.close() } }
            if (error is Exception) null else throw error
        }
    }

    internal fun openDirectory(path: String): ParcelFileDescriptor? {
        return try {
            val raw = Os.open(
                path,
                OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK,
                0,
            )
            try {
                if (!OsConstants.S_ISDIR(Os.fstat(raw).st_mode)) return null
                ParcelFileDescriptor.dup(raw)
            } finally {
                Os.close(raw)
            }
        } catch (_: Exception) {
            null
        }
    }

    internal fun secureStream(descriptor: ParcelFileDescriptor): SecureDirectoryStream<Path>? {
        val stream: DirectoryStream<Path> = runCatching {
            Files.newDirectoryStream(Paths.get("/proc/self/fd/${descriptor.fd}"))
        }.getOrNull() ?: return null
        if (stream !is SecureDirectoryStream<*>) {
            stream.close()
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return stream as SecureDirectoryStream<Path>
    }

    internal fun clear(current: SecureDirectoryStream<Path>, depth: Int, visited: IntArray) {
        require(depth <= 32) { "Diagnostic output tree is too deep" }
        val names = ArrayList<Path>()
        val iterator = current.iterator()
        while (iterator.hasNext()) {
            require(names.size < 16_384 - visited[0]) { "Diagnostic output tree has too many entries" }
            names.add(iterator.next().fileName)
        }
        for (name in names) {
            require(++visited[0] <= 16_384) { "Diagnostic output tree has too many entries" }
            val attrs = runCatching {
                current.getFileAttributeView(name, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                    .readAttributes()
            }.getOrNull() ?: continue
            if (attrs.isDirectory && !attrs.isSymbolicLink) {
                val child = runCatching { current.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS) }.getOrNull()
                    ?: continue
                val openedKey = runCatching {
                    child.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes().fileKey()
                }.getOrNull()
                child.use { clear(it, depth + 1, visited) }
                val currentKey = runCatching {
                    current.getFileAttributeView(name, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                        .readAttributes().fileKey()
                }.getOrNull()
                if (openedKey != null && openedKey == currentKey) runCatching { current.deleteDirectory(name) }
            } else {
                val currentKey = runCatching {
                    current.getFileAttributeView(name, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                        .readAttributes().fileKey()
                }.getOrNull()
                if (attrs.fileKey() != null && attrs.fileKey() == currentKey) runCatching { current.deleteFile(name) }
            }
        }
    }

    internal fun childPath(parent: ParcelFileDescriptor, name: String): String =
        "/proc/self/fd/${parent.fd}/$name"

    internal fun safeName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name

    internal const val fileMode = FILE_MODE

    internal fun openInternalStaging(context: Context, create: Boolean): ParcelFileDescriptor? {
        val root = openDirectory(context.noBackupFilesDir.path) ?: return null
        return try {
            val path = childPath(root, STAGING_DIR)
            if (create) try {
                Os.mkdir(path, DIRECTORY_MODE)
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.EEXIST) throw error
            }
            openDirectory(path)
        } finally {
            root.close()
        }
    }

    private fun cleanupInternalStaging(context: Context) {
        synchronized(stagingLock) { cleanupInternalStagingLocked(context) }
    }

    private fun cleanupInternalStagingLocked(context: Context) {
        val staging = openInternalStaging(context, false) ?: return
        try {
            val stream = secureStream(staging) ?: return
            stream.use { directory ->
                val iterator = directory.iterator()
                var visited = 0
                while (iterator.hasNext() && visited++ < 16_384) {
                    val name = iterator.next().fileName
                    if (!stagedOutput.matches(name.toString())) continue
                    val attrs = runCatching {
                        directory.getFileAttributeView(
                            name, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS,
                        ).readAttributes()
                    }.getOrNull() ?: continue
                    if (!attrs.isDirectory) runCatching { directory.deleteFile(name) }
                }
            }
            runCatching { Os.fsync(staging.fileDescriptor) }
        } finally {
            staging.close()
        }
    }

    internal fun <T> withStagingLock(block: () -> T): T = synchronized(stagingLock, block)
}

private class AndroidDiagnosticOutputDirectory(
    override val file: File,
    private val descriptors: List<ParcelFileDescriptor>,
    private val finalName: String?,
    private val context: Context,
    private val atomicMigrationAllowed: Boolean,
) : DiagnosticOutputDirectory {
    private val directoryStat = Os.fstat(descriptors.last().fileDescriptor)
    private var closed = false

    init {
        require(OsConstants.S_ISDIR(directoryStat.st_mode)) { "Diagnostic output identity is unavailable" }
    }

    override fun clear() {
        check(!closed)
        withDirectory { AndroidDiagnosticOutputProvider.clear(it, 0, intArrayOf(0)) }
    }

    override fun child(name: String): DiagnosticOutputDirectory {
        check(!closed)
        require(AndroidDiagnosticOutputProvider.safeName(name)) { "Unsafe diagnostic directory name" }
        val path = AndroidDiagnosticOutputProvider.childPath(descriptors.last(), name)
        try {
            Os.mkdir(path, 0x1c0)
        } catch (error: ErrnoException) {
            if (error.errno != OsConstants.EEXIST) throw error
        }
        val parent = ParcelFileDescriptor.dup(descriptors.last().fileDescriptor)
        val child = AndroidDiagnosticOutputProvider.openDirectory(path)
            ?: run { parent.close(); throw IOException("Diagnostic child directory is unavailable") }
        return try {
            AndroidDiagnosticOutputDirectory(
                File(file, name), listOf(parent, child), name, context, atomicMigrationAllowed,
            )
        } catch (error: Throwable) {
            parent.close()
            child.close()
            throw error
        }
    }

    override fun containsRegular(name: String): Boolean {
        check(!closed)
        if (!AndroidDiagnosticOutputProvider.safeName(name)) return false
        return runCatching {
            withDirectory {
                val attrs = it.getFileAttributeView(
                    Paths.get(name), BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS,
                ).readAttributes()
                attrs.isRegularFile && !attrs.isSymbolicLink
            }
        }.getOrDefault(false)
    }

    override fun contains(name: String): Boolean {
        check(!closed)
        if (!AndroidDiagnosticOutputProvider.safeName(name)) return false
        return runCatching {
            withDirectory {
                it.getFileAttributeView(
                    Paths.get(name), BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS,
                ).readAttributes()
                true
            }
        }.getOrDefault(false)
    }

    override fun readSmallRegular(name: String, maxBytes: Int): ByteArray? {
        check(!closed)
        if (!AndroidDiagnosticOutputProvider.safeName(name) || maxBytes <= 0) return null
        val raw = try {
            Os.open(
                AndroidDiagnosticOutputProvider.childPath(descriptors.last(), name),
                OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK,
                0,
            )
        } catch (_: Exception) {
            return null
        }
        return try {
            val before = Os.fstat(raw)
            if (!OsConstants.S_ISREG(before.st_mode) || before.st_nlink != 1L || before.st_size !in 1..maxBytes.toLong()) {
                return null
            }
            val bytes = ByteArray(before.st_size.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val count = Os.read(raw, bytes, offset, bytes.size - offset)
                if (count <= 0) return null
                offset += count
            }
            val after = Os.fstat(raw)
            val current = Os.lstat(AndroidDiagnosticOutputProvider.childPath(descriptors.last(), name))
            if (before.st_dev != after.st_dev || before.st_ino != after.st_ino || before.st_size != after.st_size ||
                current.st_dev != after.st_dev || current.st_ino != after.st_ino || current.st_nlink != 1L ||
                !OsConstants.S_ISREG(current.st_mode)) null else bytes
        } catch (_: Exception) {
            null
        } finally {
            Os.close(raw)
        }
    }

    override fun write(name: String, body: (OutputStream) -> Unit): File {
        check(!closed)
        require(AndroidDiagnosticOutputProvider.safeName(name)) { "Unsafe diagnostic archive name" }
        val raw = Os.open(
            AndroidDiagnosticOutputProvider.childPath(descriptors.last(), name),
            OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_CLOEXEC or
                OsConstants.O_NOFOLLOW,
            AndroidDiagnosticOutputProvider.fileMode,
        )
        var completed = false
        try {
            val initial = Os.fstat(raw)
            require(OsConstants.S_ISREG(initial.st_mode) && initial.st_nlink == 1L)
            ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(raw)).use(body)
            Os.fsync(raw)
            val after = Os.fstat(raw)
            require(initial.st_dev == after.st_dev && initial.st_ino == after.st_ino && after.st_nlink == 1L)
            val current = Os.lstat(AndroidDiagnosticOutputProvider.childPath(descriptors.last(), name))
            require(current.st_dev == after.st_dev && current.st_ino == after.st_ino &&
                OsConstants.S_ISREG(current.st_mode) && current.st_nlink == 1L)
            val currentDirectory = Os.lstat(file.path)
            require(currentDirectory.st_dev == directoryStat.st_dev && currentDirectory.st_ino == directoryStat.st_ino &&
                OsConstants.S_ISDIR(currentDirectory.st_mode))
            withDirectory { directory ->
                val attrs = directory.getFileAttributeView(
                    Paths.get(name), BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS,
                ).readAttributes()
                require(attrs.isRegularFile && !attrs.isSymbolicLink)
            }
            completed = true
            return File(file, name)
        } finally {
            if (!completed) runCatching {
                val written = Os.fstat(raw)
                val current = Os.lstat(AndroidDiagnosticOutputProvider.childPath(descriptors.last(), name))
                if (written.st_dev == current.st_dev && written.st_ino == current.st_ino) {
                    withDirectory { it.deleteFile(Paths.get(name)) }
                }
            }
            runCatching { Os.close(raw) }
        }
    }

    override fun writeAtomic(name: String, body: (OutputStream) -> Unit): File =
        AndroidDiagnosticOutputProvider.withStagingLock { writeAtomicLocked(name, body) }

    private fun writeAtomicLocked(name: String, body: (OutputStream) -> Unit): File {
        check(!closed)
        check(atomicMigrationAllowed) { "Atomic migration output is unavailable for this directory" }
        require(AndroidDiagnosticOutputProvider.safeName(name)) { "Unsafe migration output name" }
        val staging = AndroidDiagnosticOutputProvider.openInternalStaging(context, true)
            ?: throw IOException("Diagnostic staging directory is unavailable")
        val temporaryName = ".diagnostic-${UUID.randomUUID()}.partial"
        val raw = Os.open(
            AndroidDiagnosticOutputProvider.childPath(staging, temporaryName),
            OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_CLOEXEC or
                OsConstants.O_NOFOLLOW,
            AndroidDiagnosticOutputProvider.fileMode,
        )
        var published = false
        var completed = false
        try {
            val initial = Os.fstat(raw)
            require(OsConstants.S_ISREG(initial.st_mode) && initial.st_nlink == 1L)
            require(initial.st_dev == directoryStat.st_dev) { "Diagnostic staging is on another filesystem" }
            ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(raw)).use(body)
            Os.fsync(raw)
            val after = Os.fstat(raw)
            require(initial.st_dev == after.st_dev && initial.st_ino == after.st_ino && after.st_nlink == 1L)
            val staged = Os.lstat(AndroidDiagnosticOutputProvider.childPath(staging, temporaryName))
            require(staged.st_dev == after.st_dev && staged.st_ino == after.st_ino &&
                OsConstants.S_ISREG(staged.st_mode) && staged.st_nlink == 1L)
            requireCurrentDirectoryPath()
            DiagnosticAtomicFiles.publishNoReplace(
                rename = {
                    DiagnosticAtomicFiles.renameNoReplace(staging.fd, temporaryName, descriptors.last().fd, name)
                },
                link = {
                    Os.link(
                        AndroidDiagnosticOutputProvider.childPath(staging, temporaryName),
                        AndroidDiagnosticOutputProvider.childPath(descriptors.last(), name),
                    )
                },
                // From this point, failure cleanup owns the linked destination inode.
                onPublished = { published = true },
                verifyLinked = {
                    val linkedStage = Os.lstat(AndroidDiagnosticOutputProvider.childPath(staging, temporaryName))
                    val linkedFinal = Os.lstat(
                        AndroidDiagnosticOutputProvider.childPath(descriptors.last(), name),
                    )
                    require(linkedStage.st_dev == after.st_dev && linkedStage.st_ino == after.st_ino &&
                        linkedFinal.st_dev == after.st_dev && linkedFinal.st_ino == after.st_ino &&
                        OsConstants.S_ISREG(linkedStage.st_mode) && OsConstants.S_ISREG(linkedFinal.st_mode) &&
                        linkedStage.st_nlink == 2L && linkedFinal.st_nlink == 2L)
                },
                unlinkStage = {
                    withSecureDirectory(staging) { it.deleteFile(Paths.get(temporaryName)) }
                },
                verifyFinal = {
                    val linkedFinal = Os.lstat(
                        AndroidDiagnosticOutputProvider.childPath(descriptors.last(), name),
                    )
                    require(linkedFinal.st_dev == after.st_dev && linkedFinal.st_ino == after.st_ino &&
                        OsConstants.S_ISREG(linkedFinal.st_mode) && linkedFinal.st_nlink == 1L)
                },
            )
            val final = Os.lstat(AndroidDiagnosticOutputProvider.childPath(descriptors.last(), name))
            require(final.st_dev == after.st_dev && final.st_ino == after.st_ino && final.st_nlink == 1L)
            requireCurrentDirectoryPath()
            Os.fsync(staging.fileDescriptor)
            Os.fsync(descriptors.last().fileDescriptor)
            completed = true
            return File(file, name)
        } finally {
            if (!completed) runCatching {
                val expected = Os.fstat(raw)
                val publishedStat = runCatching {
                    Os.lstat(AndroidDiagnosticOutputProvider.childPath(descriptors.last(), name))
                }.getOrNull()
                if (published && publishedStat != null &&
                    publishedStat.st_dev == expected.st_dev && publishedStat.st_ino == expected.st_ino) {
                    withDirectory { it.deleteFile(Paths.get(name)) }
                }
                val staged = runCatching {
                    Os.lstat(AndroidDiagnosticOutputProvider.childPath(staging, temporaryName))
                }.getOrNull()
                if (staged != null && staged.st_dev == expected.st_dev && staged.st_ino == expected.st_ino) {
                    withSecureDirectory(staging) { it.deleteFile(Paths.get(temporaryName)) }
                }
                Os.fsync(staging.fileDescriptor)
                Os.fsync(descriptors.last().fileDescriptor)
            }
            runCatching { Os.close(raw) }
            runCatching { staging.close() }
        }
    }

    override fun deleteIfEmpty(): Boolean {
        check(!closed)
        if (withDirectory { it.iterator().hasNext() }) return false
        val nameText = finalName ?: return false
        if (descriptors.size < 2) return false
        val parent = AndroidDiagnosticOutputProvider.secureStream(descriptors[descriptors.lastIndex - 1]) ?: return false
        return parent.use {
            val name = Paths.get(nameText)
            val current = runCatching { Os.lstat(AndroidDiagnosticOutputProvider.childPath(descriptors[descriptors.lastIndex - 1], nameText)) }
                .getOrNull()
            if (current == null || current.st_dev != directoryStat.st_dev || current.st_ino != directoryStat.st_ino) false
            else runCatching { it.deleteDirectory(name); true }.getOrDefault(false)
        }
    }

    override fun close() {
        closed = true
        descriptors.asReversed().forEach { runCatching { it.close() } }
    }

    private fun <T> withDirectory(block: (SecureDirectoryStream<Path>) -> T): T {
        val directory = AndroidDiagnosticOutputProvider.secureStream(descriptors.last())
            ?: throw IllegalStateException("Secure relative file operations are unavailable")
        return directory.use(block)
    }

    private fun <T> withSecureDirectory(
        descriptor: ParcelFileDescriptor,
        block: (SecureDirectoryStream<Path>) -> T,
    ): T {
        val directory = AndroidDiagnosticOutputProvider.secureStream(descriptor)
            ?: throw IllegalStateException("Secure staging operations are unavailable")
        return directory.use(block)
    }

    private fun requireCurrentDirectoryPath() {
        val current = Os.lstat(file.path)
        require(current.st_dev == directoryStat.st_dev && current.st_ino == directoryStat.st_ino &&
            OsConstants.S_ISDIR(current.st_mode)) { "Diagnostic output directory was replaced" }
    }
}
