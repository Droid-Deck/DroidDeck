package com.droiddeck.launcher.runtime

import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.core.Hashes
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream
import org.json.JSONObject

internal object DesktopPack {
    const val INFO = "usr/share/droiddeck/desktop-pack.json"
    const val META = "usr/share/droiddeck/desktop"
    private const val MANIFEST = "$META/files.tsv.gz"
    private const val ORIGINALS = "$META/originals"
    private const val STATUS = "var/lib/dpkg/status"
    private const val STATUS_BEFORE = "var/lib/dpkg/status.droiddeck"
    private const val PACK_STATUS = "$META/dpkg-status"

    data class Info(val tag: String, val url: String, val sha256: String, val size: Long)

    data class Entry(val kind: Char, val type: Char, val size: Long, val value: String, val path: String) {
        companion object {
            fun parse(line: String): Entry? {
                val parts = line.split('\t', limit = 5)
                if (parts.size != 5 || parts[0].length != 1 || parts[1].length != 1) return null
                return Entry(parts[0][0], parts[1][0], parts[2].toLongOrNull() ?: return null, parts[3], parts[4])
            }
        }
    }

    data class Result(val removed: Int, val restored: Int, val kept: Int)

    fun info(root: File): Info? = runCatching {
        val json = JSONObject(FileUtils.readString(File(root, INFO)) ?: return null)
        Info(json.getString("tag"), json.getString("url"), json.getString("sha256"), json.getLong("size"))
    }.getOrNull()

    fun installed(root: File): Boolean = File(root, MANIFEST).isFile

    fun entries(root: File): List<Entry> =
        GZIPInputStream(File(root, MANIFEST).inputStream()).bufferedReader().use { reader ->
            reader.lineSequence().mapNotNull(Entry::parse).toList()
        }

    fun installedSize(root: File): Long =
        if (installed(root)) entries(root).filter { it.kind == 'N' && it.type == 'F' }.sumOf { it.size } else 0L

    fun prepareInstall(root: File) {
        val live = File(root, STATUS)
        val before = File(root, STATUS_BEFORE)
        if (!before.exists() && live.isFile) Files.copy(live.toPath(), before.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
    }

    fun finishInstall(root: File) {
        for (entry in entries(root).filter { it.kind == 'R' }.sortedByDescending { it.path.length }) {
            val file = inside(root, entry.path) ?: continue
            if (entry.type == 'D') file.delete() else Files.deleteIfExists(file.toPath())
        }
        val live = File(root, STATUS)
        val packStatus = File(root, PACK_STATUS)
        if (live.isFile && !packStatus.exists()) Files.copy(live.toPath(), packStatus.toPath())
        val before = File(root, STATUS_BEFORE)
        val runtime = File(File(root, ORIGINALS), STATUS)
        if (before.isFile && runtime.isFile && live.isFile) {
            writeStatus(live, mergeStatus(runtime.readText(), packStatus.readText(), before.readText()))
        }
        Files.deleteIfExists(before.toPath())
    }

    internal fun mergeStatus(base: String, target: String, mine: String): String {
        val from = stanzas(base)
        val into = LinkedHashMap(stanzas(target))
        val kept = stanzas(mine)
        for ((key, stanza) in kept) {
            if (from[key] != stanza && into[key] == from[key]) into[key] = stanza
        }
        for (key in from.keys) {
            if (key !in kept && into[key] == from[key]) into.remove(key)
        }
        return into.values.joinToString("") { it + "\n\n" }
    }

    private fun stanzas(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (stanza in text.split(Regex("\n[ \t]*\n")).map { it.trim('\n') }.filter { it.isNotBlank() }) {
            val fields = stanza.lines()
            fun field(name: String) = fields.firstOrNull { it.startsWith("$name:") }?.substringAfter(':')?.trim().orEmpty()
            out["${field("Package")}:${field("Architecture")}"] = stanza
        }
        return out
    }

    private fun writeStatus(file: File, text: String) {
        val part = LinuxRuntimeInstaller.partFile(file).toPath()
        Files.deleteIfExists(part)
        Files.copy(file.toPath(), part, StandardCopyOption.COPY_ATTRIBUTES)
        Files.write(part, text.toByteArray(), java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)
        Files.move(part, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun remove(root: File, progress: (Int) -> Unit = {}): Result {
        val entries = entries(root)
        var removed = 0
        var restored = 0
        var kept = 0
        val originals = File(root, ORIGINALS)
        val live = File(root, STATUS)
        val userStatus = if (live.isFile) live.readText() else null
        for (entry in entries.filter { it.kind == 'C' || it.kind == 'R' }) {
            val file = inside(root, entry.path) ?: continue
            if (entry.type == 'D') {
                file.mkdirs()
                continue
            }
            if (entry.kind == 'C' && !entry.path.startsWith("var/lib/dpkg/") && !matchesOrMissing(file, entry)) {
                kept++
                continue
            }
            val source = inside(originals, entry.path) ?: continue
            val sourcePath = source.toPath()
            if (!Files.exists(sourcePath, LinkOption.NOFOLLOW_LINKS)) continue
            file.parentFile?.mkdirs()
            val part = LinuxRuntimeInstaller.partFile(file).toPath()
            Files.deleteIfExists(part)
            if (Files.isSymbolicLink(sourcePath)) Files.createSymbolicLink(part, Files.readSymbolicLink(sourcePath))
            else Files.copy(sourcePath, part, StandardCopyOption.COPY_ATTRIBUTES)
            if (Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)) FileUtils.delete(file)
            Files.move(part, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            restored++
        }
        val added = entries.filter { it.kind == 'N' }
        val directories = ArrayList<File>()
        added.forEachIndexed { index, entry ->
            val file = inside(root, entry.path)
            when {
                file == null -> kept++
                entry.type == 'D' -> directories += file
                matches(file, entry) -> { Files.deleteIfExists(file.toPath()); removed++ }
                Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS) -> kept++
            }
            if (index % 2000 == 0) progress(index * 100 / added.size.coerceAtLeast(1))
        }
        for (dir in directories.sortedByDescending { it.path.length }) {
            if (Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS) && dir.list()?.isEmpty() == true) dir.delete()
        }
        val packStatus = File(root, PACK_STATUS)
        if (userStatus != null && packStatus.isFile && live.isFile) {
            writeStatus(live, mergeStatus(packStatus.readText(), live.readText(), userStatus))
        }
        FileUtils.delete(File(root, META))
        return Result(removed, restored, kept)
    }

    private fun matchesOrMissing(file: File, entry: Entry) =
        !Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS) || matches(file, entry)

    private fun matches(file: File, entry: Entry): Boolean {
        val path = file.toPath()
        return when (entry.type) {
            'L' -> Files.isSymbolicLink(path) && Files.readSymbolicLink(path).toString() == entry.value
            'F' -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && file.length() == entry.size &&
                Hashes.sha256(file).equals(entry.value, ignoreCase = true)
            else -> false
        }
    }

    internal fun inside(root: File, path: String): File? {
        if (path.isEmpty() || path.startsWith("/")) return null
        val normal = Paths.get(path).normalize()
        if (normal.toString().isEmpty() || normal.startsWith("..")) return null
        val parent = normal.parent
        val dir = if (parent == null) root else com.droiddeck.launcher.core.ArchivePaths.inside(root, parent.toString()) ?: return null
        return File(dir, normal.fileName.toString())
    }
}
