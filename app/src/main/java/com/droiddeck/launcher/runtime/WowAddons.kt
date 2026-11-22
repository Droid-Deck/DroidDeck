package com.droiddeck.launcher.runtime

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import android.content.Context
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreArchiveUnpacker
import com.droiddeck.launcher.stores.StoreInstalls
import com.droiddeck.launcher.stores.itch.ItchArchives
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicBoolean

object WowAddons {
    var imports by mutableIntStateOf(0)
        private set

    class TooLarge : IOException()

    class Target(val flavor: String, val addOns: File)

    class Addon(val name: String, val root: String)

    class Installed(val name: String, val version: String?, val wowiId: String?)

    class Origin(val id: String, val version: String)

    private const val ORIGINS = ".droiddeck-sources"

    internal const val MAX_EXPANDED_BYTES = 4L * 1024 * 1024 * 1024

    private val SUFFIX = Regex("[-_](standard|mainline|classic|vanilla|tbc|bcc|wrath|wotlkc|cata|mists|forever)$", RegexOption.IGNORE_CASE)

    fun targets(game: Library.SteamGame): List<Target> {
        if (game.source != Store.BATTLENET.id) return emptyList()
        val title = game.storeId?.let(BattleNet::title) ?: return emptyList()
        if (title.flavor == null) return emptyList()
        val folder = game.gameFiles ?: return emptyList()
        return targets(title, folder)
    }

    internal fun targets(title: BattleNet.Title, folder: File): List<Target> {
        val flavors = BattleNet.TITLES.filter { it.code == title.code }.mapNotNull { it.flavor }
            .flatMap { listOf(it, it.dropLast(1) + "_beta_") }.distinct()
        val root = if (folder.name in flavors) folder.parentFile ?: return emptyList() else folder
        return flavors.map { File(root, it) }.filter { it.isDirectory }.map { Target(it.name, addOnsFolder(it)) }
    }

    fun installed(target: Target): List<String> =
        target.addOns.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }
            .map { it.name }.sortedBy { it.lowercase() }

    fun details(target: Target): List<Installed> {
        val origins = origins(target)
        return installed(target).map { name ->
            val toc = toc(File(target.addOns, name))
            val origin = origins[name]
            Installed(name, origin?.version ?: toc["Version"], origin?.id ?: toc["X-WoWI-ID"])
        }
    }

    internal fun tocFields(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("\uFEFF")
            if (!line.startsWith("##") || ':' !in line) continue
            val key = line.removePrefix("##").substringBefore(':').trim()
            val value = line.substringAfter(':').trim()
            if (key.isNotEmpty() && value.isNotEmpty() && '@' !in value) out.putIfAbsent(key, value)
        }
        return out
    }

    private fun toc(folder: File): Map<String, String> {
        val tocs = folder.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".toc", ignoreCase = true) }
        val main = tocs.firstOrNull { it.name.dropLast(4).equals(folder.name, ignoreCase = true) }
            ?: tocs.firstOrNull { it.name.dropLast(4).replace(SUFFIX, "").equals(folder.name, ignoreCase = true) }
            ?: tocs.minByOrNull { it.name.length } ?: return emptyMap()
        return runCatching { tocFields(main.readText()) }.getOrDefault(emptyMap())
    }

    internal fun parseOrigins(text: String): Map<String, Origin> = text.lineSequence().mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size != 3 || parts.any { it.isBlank() }) null else parts[0] to Origin(parts[1], parts[2])
    }.toMap()

    internal fun formatOrigins(origins: Map<String, Origin>): String =
        origins.entries.sortedBy { it.key.lowercase() }.joinToString("") { (name, o) -> "$name\t${o.id}\t${o.version.replace(Regex("\\s+"), " ")}\n" }

    private fun originsFile(target: Target) = File(target.addOns, ORIGINS)

    private fun origins(target: Target): Map<String, Origin> =
        runCatching { parseOrigins(originsFile(target).readText()) }.getOrDefault(emptyMap())

    private val originsLock = Any()

    private fun keepOrigins(target: Target, names: List<String>, origin: Origin?) = synchronized(originsLock) {
        val current = origins(target)
        val next = current - names.toSet() + (origin?.let { o -> names.associateWith { o } } ?: emptyMap())
        if (next != current) {
            val file = originsFile(target)
            if (next.isEmpty()) file.delete() else {
                val temp = File(target.addOns, "$ORIGINS.tmp")
                temp.writeText(formatOrigins(next))
                if (!temp.renameTo(file)) {
                    temp.delete()
                    throw IOException("Cannot save $ORIGINS")
                }
            }
        }
    }

    fun delete(target: Target, name: String) {
        val folder = target.addOns.listFiles()?.firstOrNull { it.name == name && !it.name.startsWith(".") }
            ?: throw IOException("Cannot find $name")
        StoreInstalls.deleteTree(folder)
        if (Files.exists(folder.toPath(), LinkOption.NOFOLLOW_LINKS)) throw IOException("Cannot delete $name")
        runCatching { keepOrigins(target, listOf(name), null) }
        imports++
    }

    fun import(archive: File, target: Target, origin: Origin? = null, context: Context? = null): List<String> {
        if (context == null || ItchArchives.kind(archive, archive.name) == "zip") return importZip(archive, target, origin)
        val work = File(File(context.cacheDir, "wowaddons"), "unpack-${System.nanoTime()}")
        try {
            if (!work.mkdirs()) throw IOException("Cannot create ${work.path}")
            val unpacker = StoreArchiveUnpacker(context, AtomicBoolean(), "Addons", R.string.stores_itch_archive, R.string.stores_itch_extract_failed, "addon-unpack")
            unpacker.work = work
            unpacker.limit = MAX_EXPANDED_BYTES
            try {
                unpacker.unpack(archive, archive.name, { _, _ -> }, {})
            } catch (_: StoreArchiveUnpacker.TooLarge) {
                throw TooLarge()
            }
            return importFolder(work, target, origin)
        } finally {
            StoreInstalls.deleteTree(work)
        }
    }

    private class Entry(val path: String, val open: () -> InputStream)

    private fun importZip(zipFile: File, target: Target, origin: Origin?): List<String> =
        ZipFile.builder().setFile(zipFile).get().use { zip ->
            val all = zip.entries.toList()
            if (all.any { it.isUnixSymlink }) return emptyList()
            importEntries(all.filter { !it.isDirectory }.mapNotNull { entry -> clean(entry.name)?.let { Entry(it) { zip.getInputStream(entry) } } }, target, origin)
        }

    internal fun importFolder(folder: File, target: Target, origin: Origin?): List<String> {
        val entries = ArrayList<Entry>()
        var links = false
        Files.walkFileTree(folder.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!attrs.isRegularFile) links = true
                else clean(folder.toPath().relativize(file).joinToString("/"))?.let { entries.add(Entry(it) { Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS) }) }
                return if (links) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
            }
        })
        return if (links) emptyList() else importEntries(entries, target, origin)
    }

    private fun importEntries(entries: List<Entry>, target: Target, origin: Origin?): List<String> {
        val addons = addons(entries.map { it.path })
        if (addons.isEmpty()) return emptyList()
        val addOns = target.addOns
        if (!addOns.isDirectory && !addOns.mkdirs()) throw IOException("Cannot create ${addOns.path}")
        val stage = File(addOns.parentFile, ".addon-import-${System.nanoTime()}")
        try {
            val fresh = File(stage, "new")
            val buffer = ByteArray(64 * 1024)
            var written = 0L
            for (entry in entries) {
                val addon = addons.firstOrNull { entry.path.startsWith(it.root) } ?: continue
                val out = inside(fresh, addon.name + "/" + entry.path.removePrefix(addon.root)) ?: continue
                out.parentFile?.mkdirs()
                entry.open().use { input ->
                    out.outputStream().use { output ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            written += read
                            if (written > MAX_EXPANDED_BYTES) throw TooLarge()
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
            swap(addons.map { File(fresh, it.name) to it.name }, addOns, File(stage, "old").apply { mkdirs() })
        } finally {
            StoreInstalls.deleteTree(stage)
        }
        runCatching { keepOrigins(target, addons.map { it.name }, origin) }
        imports++
        return addons.map { it.name }
    }

    internal fun addons(files: List<String>): List<Addon> {
        val tocs = files.filter { it.endsWith(".toc", ignoreCase = true) }
        val folders = tocs.map { it.substringBeforeLast('/', "") }.distinct().sortedBy { it.length }
        val roots = ArrayList<String>()
        for (folder in folders) {
            if (roots.none { it.isEmpty() || folder.startsWith("$it/") }) roots.add(folder)
        }
        return roots.mapNotNull { root ->
            val inFolder = tocs.filter { it.substringBeforeLast('/', "") == root }
                .map { it.substringAfterLast('/').dropLast(4) }
            val name = addonName(root.substringAfterLast('/'), inFolder)
            name.takeIf { it.isNotEmpty() && !it.startsWith(".") }?.let { Addon(it, if (root.isEmpty()) "" else "$root/") }
        }.distinctBy { it.name.lowercase() }
    }

    private fun addonName(folder: String, tocs: List<String>): String {
        val match = tocs.firstOrNull { it.equals(folder, ignoreCase = true) }
            ?: tocs.firstOrNull { it.replace(SUFFIX, "").equals(folder, ignoreCase = true) }
        if (match != null) return folder
        return tocs.map { it.replace(SUFFIX, "") }.minByOrNull { it.length }.orEmpty()
    }

    private fun clean(name: String): String? {
        val parts = name.replace('\\', '/').trim('/').split('/')
        if (name.startsWith("/") || name.startsWith("\\") || parts.any { it.isEmpty() || it == "." || it == ".." || it.endsWith(":") }) return null
        if (parts.first() == "__MACOSX" || parts.last().startsWith("._")) return null
        return parts.joinToString("/")
    }

    private fun inside(root: File, path: String): File? {
        val out = File(root, path)
        return out.takeIf { it.canonicalPath.startsWith(root.canonicalPath + File.separator) }
    }

    private fun addOnsFolder(flavor: File): File = child(child(flavor, "Interface"), "AddOns")

    private fun child(parent: File, name: String): File =
        parent.listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: File(parent, name)

    internal fun swap(fresh: List<Pair<File, String>>, addOns: File, old: File) {
        val done = ArrayList<Replaced>()
        try {
            for ((folder, name) in fresh) done.add(replace(folder, addOns, name, old))
        } catch (e: IOException) {
            done.asReversed().forEach { undo(it) }
            throw e
        }
    }

    private class Replaced(val installed: File, val previous: File?, val kept: File?)

    private fun undo(replaced: Replaced) {
        StoreInstalls.deleteTree(replaced.installed)
        if (replaced.previous != null) replaced.kept?.renameTo(replaced.previous)
    }

    private fun replace(fresh: File, addOns: File, name: String, old: File): Replaced {
        val current = addOns.listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) }
        val kept = current?.let { File(old, it.name) }
        if (current != null && !current.renameTo(kept!!)) throw IOException("Cannot replace ${current.path}")
        val installed = File(addOns, name)
        if (!fresh.renameTo(installed)) {
            kept?.renameTo(current)
            throw IOException("Cannot install $name")
        }
        return Replaced(installed, current, kept)
    }
}
