package com.droiddeck.launcher.runtime

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

internal object DesktopFileRemoval {
    data class Entry(val kind: String, val size: Long, val identity: String, val path: String) {
        companion object {
            fun parse(line: String): Entry {
                val parts = line.split('\t', limit = 4)
                require(parts.size == 4 && parts[0] in setOf("F", "L")) { "Invalid removal inventory" }
                return Entry(parts[0], parts[1].toLong(), parts[2], parts[3])
            }
        }
    }
    data class Result(val removed: Int, val bytes: Long, val kept: Int)

    fun remove(root: File, entries: Iterable<Entry>, protected: Set<String>, progress: (Int) -> Unit = {}): Result {
        var removed = 0
        var bytes = 0L
        var kept = 0
        val buffer = ByteArray(65536)
        val total = (entries as? Collection<*>)?.size ?: 0
        val directories = mutableSetOf<File>()
        for ((index, entry) in entries.withIndex()) {
            val file = safeFile(root, entry.path)
            if (file == null || entry.path in protected) { kept++; continue }
            val attrs = try { Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
                catch (_: java.nio.file.NoSuchFileException) { continue }
            val matches = when (entry.kind) {
                "L" -> attrs.isSymbolicLink && Files.readSymbolicLink(file.toPath()).toString() == entry.identity
                else -> attrs.isRegularFile && attrs.size() == entry.size && sha(file, buffer) == entry.identity
            }
            if (!matches) { kept++; continue }
            Files.delete(file.toPath())
            removed++
            bytes += if (attrs.isRegularFile) attrs.size() else 0
            var parent = file.parentFile
            while (parent != root && parent != null) { directories.add(parent); parent = parent.parentFile }
            if (index % 200 == 0) progress(if (total > 0) index * 99 / total else -1)
        }
        for (dir in directories.sortedByDescending { it.path.length }) {
            if (dir.list()?.isEmpty() == true && !dir.delete()) throw IOException("Cannot remove $dir")
        }
        progress(99)
        return Result(removed, bytes, kept)
    }

    internal fun safeFile(root: File, name: String): File? {
        if (Files.isSymbolicLink(root.toPath())) return null
        val parts = name.split('/')
        if (parts.firstOrNull() !in setOf("usr", "etc") || parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        var parent = root
        for (part in parts.dropLast(1)) {
            parent = File(parent, part)
            if (Files.isSymbolicLink(parent.toPath())) return null
        }
        return File(root, name)
    }

    internal fun sha(file: File, buffer: ByteArray = ByteArray(65536)): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        val digits = "0123456789abcdef"
        return buildString(64) {
            for (byte in digest.digest()) { val b = byte.toInt() and 255; append(digits[b ushr 4]); append(digits[b and 15]) }
        }
    }

    private val RECORD = Regex("[A-Za-z0-9@._+:-]+")

    fun desktopRecords(root: File): Set<String> {
        val list = safeFile(root, "usr/share/droiddeck/pacman/desktop-packages.txt") ?: return emptySet()
        if (!Files.isRegularFile(list.toPath(), NOFOLLOW_LINKS)) return emptySet()
        return list.readLines().mapNotNull { line ->
            line.split(' ').takeIf { it.size == 2 }?.let { "${it[0]}-${it[1]}" }?.takeIf { RECORD.matches(it) && it != "." && it != ".." }
        }.toSet()
    }

    fun removeRecords(root: File, records: Set<String>) {
        val lib = File(root, "var/lib/pacman")
        if (Files.isSymbolicLink(File(root, "var").toPath()) || Files.isSymbolicLink(lib.toPath())) return
        val local = File(lib, "local")
        if (!local.isDirectory || Files.isSymbolicLink(local.toPath())) return
        for (record in records) {
            if (!RECORD.matches(record) || record == "." || record == "..") continue
            val dir = File(local, record)
            if (Files.isSymbolicLink(dir.toPath()) || !dir.isDirectory) continue
            for (file in dir.listFiles().orEmpty()) {
                if (Files.isSymbolicLink(file.toPath()) || file.isFile) Files.delete(file.toPath())
            }
            if (!dir.delete()) throw IOException("Cannot remove $dir")
        }
        for (name in listOf("sync/droiddeck.db", "droiddeck-desktop")) {
            val file = File(lib, name)
            if (Files.isSymbolicLink(file.toPath()) || file.isFile) Files.delete(file.toPath())
        }
    }

    fun runtimeFiles(root: File): Set<String> = buildSet {
        val database = File(root, "var/lib/pacman/local")
        if (!database.isDirectory || Files.isSymbolicLink(database.toPath())) throw IOException("Runtime package database missing")
        val desktop = desktopRecords(root)
        for (pkg in database.listFiles().orEmpty()) {
            if (pkg.name in desktop) continue
            val files = File(pkg, "files")
            if (!pkg.isDirectory || Files.isSymbolicLink(pkg.toPath()) || !files.isFile || Files.isSymbolicLink(files.toPath())) continue
            var inFiles = false
            files.forEachLine { line ->
                when {
                    line == "%FILES%" -> inFiles = true
                    line.startsWith('%') -> inFiles = false
                    inFiles && line.isNotEmpty() && !line.endsWith('/') -> add(line)
                }
            }
        }
        if (isEmpty()) throw IOException("Runtime package inventory missing")
    }
}
