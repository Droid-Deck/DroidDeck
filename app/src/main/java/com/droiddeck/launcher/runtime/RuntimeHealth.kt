package com.droiddeck.launcher.runtime

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

object RuntimeHealth {
    private const val MAX_HOPS = 16

    fun brokenLibraryLinks(context: Context): List<String> = brokenLibraryLinks(LinuxRuntime.rootDir(context))

    /** A complete runtime has no library link in usr/lib whose target is missing. */
    fun brokenLibraryLinks(root: File): List<String> {
        val lib = File(root, "usr/lib")
        val names = lib.list() ?: return emptyList()
        return names.sorted().filter { name ->
            val path = File(lib, name).toPath()
            Files.isSymbolicLink(path) && !resolves(root, path)
        }
    }

    private fun resolves(root: File, link: Path): Boolean = try {
        var path = link
        var hops = 0
        while (Files.isSymbolicLink(path) && hops++ < MAX_HOPS) {
            val target = Files.readSymbolicLink(path)
            path = if (target.isAbsolute) File(root, target.toString()).toPath() else path.parent.resolve(target)
        }
        !Files.isSymbolicLink(path) && Files.exists(path)
    } catch (e: Exception) {
        false
    }
}
