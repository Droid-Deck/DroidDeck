package com.droiddeck.launcher.sync

import java.io.File

class SyncthingGuard(private val home: File, roots: List<File>, forbidden: List<File>) {
    private val roots = roots.map { it.canonicalFile }
    private val forbidden = forbidden.map { it.canonicalFile }

    fun allows(path: String): Boolean {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return false
        val expanded = when {
            trimmed == "~" -> home
            trimmed.startsWith("~/") -> File(home, trimmed.removePrefix("~/"))
            else -> File(trimmed)
        }
        if (!expanded.isAbsolute) return false
        val target = runCatching { expanded.canonicalFile }.getOrNull() ?: return false
        return roots.any { within(target, it) } && forbidden.none { within(target, it) || within(it, target) }
    }

    fun browsable(dir: File): Boolean {
        val target = runCatching { dir.canonicalFile }.getOrNull() ?: return false
        return roots.any { within(target, it) } && forbidden.none { within(target, it) }
    }

    private fun within(target: File, root: File) = target == root || target.path.startsWith(root.path + File.separator)
}
