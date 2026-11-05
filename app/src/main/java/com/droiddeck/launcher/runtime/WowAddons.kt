package com.droiddeck.launcher.runtime

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreInstalls
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.IOException

object WowAddons {
    var imports by mutableIntStateOf(0)
        private set

    class Target(val flavor: String, val addOns: File)

    class Addon(val name: String, val root: String)

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

    fun installed(target: Target): Int =
        target.addOns.listFiles()?.count { it.isDirectory && !it.name.startsWith(".") } ?: 0

    fun import(zipFile: File, target: Target): List<String> {
        ZipFile.builder().setFile(zipFile).get().use { zip ->
            val entries = zip.entries.toList().filter { clean(it.name) != null }
            val addons = addons(entries.filter { !it.isDirectory }.mapNotNull { clean(it.name) })
            if (addons.isEmpty()) return emptyList()
            val addOns = target.addOns
            if (!addOns.isDirectory && !addOns.mkdirs()) throw IOException("Cannot create ${addOns.path}")
            val stage = File(addOns.parentFile, ".addon-import-${System.nanoTime()}")
            try {
                val fresh = File(stage, "new")
                val buffer = ByteArray(64 * 1024)
                for (entry in entries) {
                    if (entry.isDirectory) continue
                    val path = clean(entry.name) ?: continue
                    val addon = addons.firstOrNull { path.startsWith(it.root) } ?: continue
                    val out = inside(fresh, addon.name + "/" + path.removePrefix(addon.root)) ?: continue
                    out.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        out.outputStream().use { output ->
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
                swap(addons.map { File(fresh, it.name) to it.name }, addOns, File(stage, "old").apply { mkdirs() })
            } finally {
                StoreInstalls.deleteTree(stage)
            }
            imports++
            return addons.map { it.name }
        }
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
