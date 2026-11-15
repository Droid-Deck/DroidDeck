package com.droiddeck.launcher.session

import java.nio.channels.SeekableByteChannel
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
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

internal fun installJvmSecureSourceOpener() {
    EnhancedSessionLogShare.setSourceOpenerForTest(JvmSecureSourceOpener)
}

internal fun resetSecureSourceOpener() {
    EnhancedSessionLogShare.setSourceOpenerForTest(null)
}
