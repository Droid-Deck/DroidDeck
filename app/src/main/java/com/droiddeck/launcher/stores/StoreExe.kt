package com.droiddeck.launcher.stores

import java.io.File

/**
 * Which .exe in an install folder is the game. The store's own hint (a manifest's launch
 * executable) wins when the file exists; else the candidates, helpers and installers left out,
 * the one named most like the title first, then the shallowest. Relative to the folder, forward
 * slashes; "" when the folder has none.
 */
object StoreExe {
    private val SKIP = listOf("redist", "unins", "setup", "crash", "report", "helper", "dotnet", "vcredist", "directx", "eac", "easyanticheat", "battleye", "launcher", "updater", "config")

    fun pick(folder: File, hint: String?, title: String): String {
        val hinted = hint?.takeIf { it.isNotBlank() }?.replace('\\', '/')?.trimStart('/')?.let { File(folder, it) }
        if (hinted != null && hinted.isFile) return relative(folder, hinted)
        val candidates = ArrayList<File>()
        collect(folder, candidates, 0)
        if (candidates.isEmpty()) return ""
        val key = title.lowercase().replace(Regex("[^a-z0-9]"), "")
        val best = candidates.firstOrNull { f ->
            val n = f.nameWithoutExtension.lowercase().replace(Regex("[^a-z0-9]"), "")
            n.isNotEmpty() && (n == key || (key.length >= 4 && key.startsWith(n)) || (n.length >= 4 && n.startsWith(key)))
        } ?: candidates.sortedWith(compareBy<File> { depth(folder, it) }.thenByDescending { it.length() }).first()
        return relative(folder, best)
    }

    private fun collect(dir: File, out: MutableList<File>, depth: Int) {
        if (depth > 4) return
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.isFile && f.name.endsWith(".exe", ignoreCase = true)) {
                val lower = f.name.lowercase()
                if (SKIP.none { lower.contains(it) }) out.add(f)
            }
        }
        for (f in files) if (f.isDirectory && !f.name.startsWith(".")) collect(f, out, depth + 1)
    }

    private fun depth(root: File, f: File): Int = relative(root, f).count { it == '/' }

    fun relative(root: File, f: File): String =
        f.absolutePath.removePrefix(root.absolutePath).trimStart('/').replace('\\', '/')
}
