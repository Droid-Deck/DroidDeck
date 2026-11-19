package com.droiddeck.launcher.storage

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

/** Parts of the Linux userland that the system and Steam make again when needed, so clearing them loses nothing. */
enum class SystemCache { TEMP, PACKAGES, STEAM_WEB, STEAM_CORRUPT }

class SystemCacheUsage(val kind: SystemCache, val files: List<File>, val bytes: Long)

object SystemCaches {
    /** Temporary files nothing has touched for a day belong to no program still running. */
    const val TEMP_IDLE_MS = 24L * 60 * 60 * 1000

    /**
     * What each cache would delete. In /tmp, hidden entries (X11, ICE and other sockets' folders) stay, as does
     * anything changed within [TEMP_IDLE_MS] or holding a socket or pipe. apt keeps only its downloaded packages
     * here, and Steam's web cache and the download chunks it set aside as corrupt come back or are not needed.
     */
    fun find(rootfs: File, steamRoots: List<File>, now: Long): Map<SystemCache, List<File>> = linkedMapOf(
        SystemCache.TEMP to File(rootfs, "tmp").listFiles().orEmpty().filter { !it.name.startsWith(".") && idle(it, now) },
        SystemCache.PACKAGES to File(rootfs, "var/cache/apt/archives").let { dir ->
            dir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".deb") } + File(dir, "partial").listFiles().orEmpty()
        },
        SystemCache.STEAM_WEB to steamRoots.map { File(it, "config/htmlcache") }.filter { it.isDirectory },
        SystemCache.STEAM_CORRUPT to steamRoots.map { File(it, "steamapps/corrupt") }.filter { it.isDirectory },
    )

    /** Nothing inside changed for [TEMP_IDLE_MS] and nothing is a socket or pipe; links are not followed. */
    internal fun idle(file: File, now: Long): Boolean = runCatching {
        Files.walk(file.toPath()).use { paths ->
            paths.allMatch { p ->
                val a = Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                !a.isOther && now - a.lastModifiedTime().toMillis() >= TEMP_IDLE_MS
            }
        }
    }.getOrDefault(false)
}
