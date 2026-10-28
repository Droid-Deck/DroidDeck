package com.droiddeck.launcher.stores

import java.io.File
import java.io.FileInputStream

object StoreExe {
    private val SKIP = listOf("redist", "unins", "setup", "crash", "report", "helper", "dotnet", "vcredist", "directx", "eac", "easyanticheat", "battleye", "launcher", "updater", "config")

    private val SHARED_OBJECT = Regex("""\.so(\.|$)""")

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

    fun pickLinux(folder: File, hint: String?, title: String): String {
        val hinted = hint?.takeIf { it.isNotBlank() }?.replace('\\', '/')?.trimStart('/')?.let { File(folder, it) }
        if (hinted != null && linuxCandidate(hinted)) return relative(folder, hinted)
        val candidates = ArrayList<File>()
        collectLinux(folder, candidates, 0)
        if (candidates.isEmpty()) return ""
        val key = title.lowercase().replace(Regex("[^a-z0-9]"), "")
        val matches = candidates.filter { f ->
            val n = f.nameWithoutExtension.lowercase().replace(Regex("[^a-z0-9]"), "")
            n.isNotEmpty() && (n == key || (key.length >= 4 && key.startsWith(n)) || (n.length >= 4 && n.startsWith(key)))
        }
        val best = matches.ifEmpty { candidates }.sortedWith(compareBy<File> { depth(folder, it) }.thenByDescending { it.length() }).first()
        return relative(folder, best)
    }

    private fun linuxCandidate(f: File): Boolean {
        if (!f.isFile || f.name.startsWith(".")) return false
        val lower = f.name.lowercase()
        return !SHARED_OBJECT.containsMatchIn(lower) && SKIP.none { lower.contains(it) } && isX86Elf(f)
    }

    internal fun isX86Elf(file: File): Boolean {
        val head = ByteArray(20)
        val read = runCatching { FileInputStream(file).use { it.read(head) } }.getOrDefault(-1)
        if (read < head.size) return false
        if (head[0] != 0x7F.toByte() || head[1] != 'E'.code.toByte() || head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte()) return false
        val machine = (head[18].toInt() and 0xFF) or ((head[19].toInt() and 0xFF) shl 8)
        return head[5].toInt() == 1 && (machine == 0x3E || machine == 0x03)
    }

    private fun collectLinux(dir: File, out: MutableList<File>, depth: Int) {
        if (depth > 4) return
        val files = dir.listFiles() ?: return
        for (f in files) if (linuxCandidate(f)) out.add(f)
        for (f in files) if (f.isDirectory && !f.name.startsWith(".")) collectLinux(f, out, depth + 1)
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
