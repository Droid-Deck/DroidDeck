package com.droiddeck.launcher.session

import android.content.Context
import java.io.File
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.SeekableByteChannel
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes

internal object JvmSecureSourceOpener : SecureSourceOpener {
    override fun <T> withChannel(root: Path, relative: Path, block: (SeekableByteChannel) -> T): T? {
        val expected = root.resolve(relative).normalize()
        if (!expected.startsWith(root.normalize())) return null
        val initial = runCatching {
            Files.readAttributes(expected, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }.getOrNull() ?: return null
        val links = runCatching {
            (Files.getAttribute(expected, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toLong()
        }.getOrNull() ?: return null
        val expectedKey = initial.fileKey() ?: return null
        if (!initial.isRegularFile || initial.isSymbolicLink || links != 1L) return null
        val first = Files.newDirectoryStream(root)
        if (first !is SecureDirectoryStream<*>) {
            first.close()
            return null
        }
        @Suppress("UNCHECKED_CAST")
        val secureFirst = first as SecureDirectoryStream<Path>
        val opened = ArrayList<DirectoryStream<Path>>()
        opened += first
        var current = secureFirst
        return try {
            val parts = relative.map { it.toString() }
            if (parts.isEmpty()) return null
            for (part in parts.dropLast(1)) {
                val child = Paths.get(part)
                val attrs = current.getFileAttributeView(
                    child, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS,
                ).readAttributes()
                if (!attrs.isDirectory || attrs.isSymbolicLink) return null
                val next = current.newDirectoryStream(child, LinkOption.NOFOLLOW_LINKS)
                opened += next
                current = next
            }
            val leaf = Paths.get(parts.last())
            val attrs = current.getFileAttributeView(
                leaf, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS,
            ).readAttributes()
            if (!attrs.isRegularFile || attrs.isSymbolicLink || attrs.fileKey() != expectedKey) return null
            current.newByteChannel(leaf, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
                val after = current.getFileAttributeView(
                    leaf, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS,
                ).readAttributes()
                if (after.fileKey() != expectedKey) null else block(channel)
            }
        } finally {
            opened.asReversed().forEach { runCatching { it.close() } }
        }
    }
}

internal object JvmDiagnosticOutputProvider : DiagnosticOutputProvider {
    private val legacyStage = Regex("""^.+-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.diagnostic-stage$""")

    override fun open(context: Context, requested: File): DiagnosticOutputDirectory? {
        val wanted = requested.toPath().toAbsolutePath().normalize()
        val cache = context.cacheDir.toPath().toAbsolutePath().normalize()
        val external = context.getExternalFilesDir(null)?.toPath()?.toAbsolutePath()?.normalize()
        val sessions = com.droiddeck.launcher.runtime.LinuxRuntime.logDir(context).toPath().toAbsolutePath().normalize()
        val ephemeral = cache.resolve("session-logs")
        val existingSession = (wanted.parent == sessions || wanted.parent == ephemeral) &&
            SessionPaths.isSessionFolder(requested)
        val allowed = wanted == cache.resolve("share") || wanted == cache.resolve("shared-logs") ||
            (external != null && wanted.parent?.parent == external && wanted.parent?.fileName?.toString() == "agent-logs") ||
            existingSession || wanted == sessions || wanted == sessions.resolve(SessionPaths.TOOLS_DIR)
        if (!allowed || Files.isSymbolicLink(wanted)) return null
        return runCatching {
            if (existingSession) {
                if (!Files.isDirectory(wanted, LinkOption.NOFOLLOW_LINKS)) return null
            } else Files.createDirectories(wanted)
            if (Files.isSymbolicLink(wanted)) return null
            val parentStream = Files.newDirectoryStream(wanted.parent)
            if (parentStream !is SecureDirectoryStream<*>) {
                parentStream.close()
                return null
            }
            @Suppress("UNCHECKED_CAST")
            val parent = parentStream as SecureDirectoryStream<Path>
            val name = wanted.fileName
            val attrs = parent.getFileAttributeView(
                name,
                BasicFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            ).readAttributes()
            if (!attrs.isDirectory || attrs.isSymbolicLink || attrs.fileKey() == null) {
                parent.close()
                return null
            }
            JvmDiagnosticOutputDirectory(requested, parent, name, attrs.fileKey())
        }.getOrNull()
    }

    override fun <T> withExistingDirectory(
        root: File,
        block: (SecureDirectoryStream<Path>) -> T,
    ): T? {
        if (Files.isSymbolicLink(root.toPath())) return null
        val stream = runCatching { Files.newDirectoryStream(root.toPath()) }.getOrNull() ?: return null
        if (stream !is SecureDirectoryStream<*>) {
            stream.close()
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return (stream as SecureDirectoryStream<Path>).use(block)
    }

    override fun cleanupLegacyStages(context: Context) {
        val cache = Files.newDirectoryStream(context.cacheDir.toPath())
        if (cache !is SecureDirectoryStream<*>) {
            cache.close()
            return
        }
        @Suppress("UNCHECKED_CAST")
        (cache as SecureDirectoryStream<Path>).use { root ->
            val iterator = root.iterator()
            var visited = 0
            while (iterator.hasNext() && visited++ < 16_384) {
                val name = iterator.next().fileName
                if (legacyStage.matches(name.toString())) cleanupLegacyStage(root, name)
            }
            runCatching { root.newDirectoryStream(Paths.get("share"), LinkOption.NOFOLLOW_LINKS) }
                .getOrNull()?.use { share -> cleanupLegacyStage(share, Paths.get(".diagnostic-stage")) }
        }
    }

    private fun cleanupLegacyStage(parent: SecureDirectoryStream<Path>, name: Path) {
        val original = runCatching {
            parent.getFileAttributeView(name, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                .readAttributes()
        }.getOrNull() ?: return
        if (!original.isDirectory || original.isSymbolicLink) return
        val stage = runCatching { parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS) }.getOrNull() ?: return
        stage.use { current ->
            val activeExists = try {
                current.getFileAttributeView(
                    Paths.get(".active"), BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS,
                ).readAttributes()
                true
            } catch (_: NoSuchFileException) {
                false
            } catch (_: Exception) {
                return
            }
            if (activeExists) return
            JvmDiagnosticOutputDirectory.clearSecure(current, 0, intArrayOf(0))
        }
        val current = runCatching {
            parent.getFileAttributeView(name, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                .readAttributes()
        }.getOrNull() ?: return
        if (current.fileKey() == original.fileKey()) runCatching { parent.deleteDirectory(name) }
    }
}

private class JvmDiagnosticOutputDirectory(
    override val file: File,
    private val parent: SecureDirectoryStream<Path>,
    private val directoryName: Path,
    private val fileKey: Any,
) : DiagnosticOutputDirectory {
    private var closed = false

    override fun clear() {
        check(!closed)
        withDirectory { clearSecure(it, 0, intArrayOf(0)) }
    }

    override fun child(name: String): DiagnosticOutputDirectory {
        check(!closed)
        require(safeName(name))
        val childPath = file.toPath().resolve(name)
        if (!Files.exists(childPath, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(childPath)
        val childParent = parent.newDirectoryStream(directoryName, LinkOption.NOFOLLOW_LINKS)
        return try {
            val childName = Paths.get(name)
            val attrs = childParent.getFileAttributeView(
                childName,
                BasicFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            ).readAttributes()
            require(attrs.isDirectory && !attrs.isSymbolicLink && attrs.fileKey() != null)
            JvmDiagnosticOutputDirectory(File(file, name), childParent, childName, attrs.fileKey())
        } catch (error: Throwable) {
            childParent.close()
            throw error
        }
    }

    override fun contains(name: String): Boolean = attributes(name) != null

    override fun containsRegular(name: String): Boolean = attributes(name)?.let {
        it.isRegularFile && !it.isSymbolicLink
    } ?: false

    override fun write(fileName: String, body: (OutputStream) -> Unit): File {
        check(!closed)
        require(fileName.isNotEmpty() && fileName != "." && fileName != ".." && '/' !in fileName && '\\' !in fileName)
        val leaf = Paths.get(fileName)
        var completed = false
        try {
            withDirectory { directory ->
                directory.newByteChannel(
                    leaf,
                    setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                ).use { channel -> Channels.newOutputStream(channel).use(body) }
                val attrs = directory.getFileAttributeView(
                    leaf,
                    BasicFileAttributeView::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                ).readAttributes()
                require(attrs.isRegularFile && !attrs.isSymbolicLink)
            }
            completed = true
            return File(file, fileName)
        } finally {
            if (!completed) runCatching { withDirectory { it.deleteFile(leaf) } }
        }
    }

    override fun deleteIfEmpty(): Boolean {
        check(!closed)
        if (withDirectory { it.iterator().hasNext() }) return false
        val current = parent.getFileAttributeView(
            directoryName,
            BasicFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ).readAttributes()
        if (current.fileKey() != fileKey) return false
        closed = true
        return runCatching { parent.deleteDirectory(directoryName); true }.getOrDefault(false)
    }

    override fun close() {
        closed = true
        parent.close()
    }

    private fun <T> withDirectory(block: (SecureDirectoryStream<Path>) -> T): T {
        val attrs = parent.getFileAttributeView(
            directoryName,
            BasicFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ).readAttributes()
        require(attrs.isDirectory && !attrs.isSymbolicLink && attrs.fileKey() == fileKey)
        return parent.newDirectoryStream(directoryName, LinkOption.NOFOLLOW_LINKS).use(block)
    }

    private fun attributes(name: String): BasicFileAttributes? {
        check(!closed)
        if (!safeName(name)) return null
        return runCatching {
            withDirectory {
                it.getFileAttributeView(
                    Paths.get(name),
                    BasicFileAttributeView::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                ).readAttributes()
            }
        }.getOrNull()
    }

    companion object {
        fun clearSecure(current: SecureDirectoryStream<Path>, depth: Int, visited: IntArray) {
            require(depth <= 32)
            val names = ArrayList<Path>()
            val iterator = current.iterator()
            while (iterator.hasNext()) {
                require(names.size < 16_384 - visited[0])
                names.add(iterator.next().fileName)
            }
            for (name in names) {
                require(++visited[0] <= 16_384)
                val attrs = current.getFileAttributeView(
                    name,
                    BasicFileAttributeView::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                ).readAttributes()
                if (attrs.isDirectory && !attrs.isSymbolicLink) {
                    val key = attrs.fileKey()
                    current.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS).use { child ->
                        clearSecure(child, depth + 1, visited)
                    }
                    val after = current.getFileAttributeView(
                        name,
                        BasicFileAttributeView::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    ).readAttributes()
                    if (key != null && after.fileKey() == key) current.deleteDirectory(name)
                } else current.deleteFile(name)
            }
        }

        private fun safeName(name: String): Boolean =
            name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name
    }
}

internal fun installJvmSecureSourceOpener() {
    EnhancedSessionLogShare.setSourceOpenerForTest(JvmSecureSourceOpener)
    SecureDiagnosticOutput.setProviderForTest(JvmDiagnosticOutputProvider)
}

internal fun resetSecureSourceOpener() {
    EnhancedSessionLogShare.setSourceOpenerForTest(null)
    SecureDiagnosticOutput.setProviderForTest(null)
}
