package com.droiddeck.launcher.files

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Image
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.annotation.StringRes
import com.droiddeck.launcher.R
import java.io.File

// The File Manager's non-UI helpers: sorting, paths, conflict choices and favourite locations.

/*
 * Bannerlator's File Manager (ui/screens/FileManagerScreen.kt), carried over whole: the same
 * browsing, grid and list, sorting, search, multi-select, copy/cut/paste with conflicts and
 * progress, rename, delete, new folder, properties, favourites and the locations rail, and the
 * same pick modes. What this app has no use for is not here: running a file in a Wine container,
 * adding it to a container's shortcuts, the Drive C: / Drive Z: locations and the archive
 * unpacker that installs into a prefix. Everything else is line for line the same so a fix in
 * either app carries to the other.
 */

/**
 * What to do when a pasted item already exists at the destination.
 *
 * OVERWRITE and MERGE resolve to the same call - `copyWithProgress` recurses into an existing
 * directory and truncates existing files - but they mean different things to the user, so both are
 * offered and the wording is chosen per item type (files overwrite, folders merge).
 */
enum class ConflictChoice { OVERWRITE, MERGE, KEEP_BOTH, SKIP }

/**
 * Shortens a path from the LEFT, keeping whole segments.
 *
 * Compose's TextOverflow can only ellipsise the tail, which for a path throws away the part that
 * matters - `/storage/emulated/0/Games/Racing/Dir…` tells you nothing about where you are.
 */
internal fun elidePathStart(path: String, max: Int): String {
    if (path.length <= max) return path
    val parts = path.split('/').filter { it.isNotEmpty() }
    val out = StringBuilder()
    for (part in parts.asReversed()) {
        if (out.length + part.length + 1 > max - 2) break
        out.insert(0, "/$part")
    }
    return if (out.isEmpty()) "…" + path.takeLast(max - 1) else "…$out"
}

/** Folder-first ordering stays fixed across both sort directions. */
internal fun comparatorFor(sortBy: String, desc: Boolean): Comparator<File> {
    val inner: Comparator<File> = when (sortBy) {
        "date" -> compareBy { it.lastModified() }
        // Directory length() is meaningless, so folders sort by name within the size ordering
        // instead of pretending to have one.
        "size" -> compareBy { if (it.isDirectory) -1L else it.length() }
        else -> compareBy { it.name.lowercase() }
    }
    val directed = if (desc) inner.reversed() else inner
    return compareBy<File> { if (it.isDirectory) 0 else 1 }.then(directed)
}

// Image extensions that get a real thumbnail (via Coil) instead of the generic file icon.
internal val IMAGE_THUMB_EXTS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")

// True when [child] is [ancestor] itself or lives anywhere inside it.
internal fun isWithin(child: File, ancestor: File): Boolean {
    val c = runCatching { child.canonicalPath }.getOrDefault(child.absolutePath)
    val a = runCatching { ancestor.canonicalPath }.getOrDefault(ancestor.absolutePath)
    return c == a || c.startsWith(a + File.separator)
}

// ── Favorites: origin resolution ──

enum class FavStorage { INTERNAL, SD, OTHER }

data class FavLocation(
    val storage: FavStorage,
    @StringRes val driveLabel: Int, // Internal, SD card, or Storage
    val displayPath: String       // the unix absolute path
)

// Resolve where [file] lives (storage source + a friendly label) by prefix-matching its path.
fun describeLocation(file: File): FavLocation {
    val abs = file.absolutePath

    val internal = "/storage/emulated/0"
    if (abs == internal || abs.startsWith("$internal/")) {
        return FavLocation(FavStorage.INTERNAL, R.string.fm_drive_internal, abs)
    }

    if (abs.startsWith("/storage/")) {
        val name = abs.removePrefix("/storage/").substringBefore('/')
        if (name.isNotEmpty() && name != "emulated" && name != "self") {
            return FavLocation(FavStorage.SD, R.string.fm_drive_sd, abs)
        }
    }

    return FavLocation(FavStorage.OTHER, R.string.fm_drive_storage, abs)
}
