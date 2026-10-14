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

enum class ConflictChoice { OVERWRITE, MERGE, KEEP_BOTH, SKIP }

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

internal fun comparatorFor(sortBy: String, desc: Boolean): Comparator<File> {
    val inner: Comparator<File> = when (sortBy) {
        "date" -> compareBy { it.lastModified() }
        "size" -> compareBy { if (it.isDirectory) -1L else it.length() }
        else -> compareBy { it.name.lowercase() }
    }
    val directed = if (desc) inner.reversed() else inner
    return compareBy<File> { if (it.isDirectory) 0 else 1 }.then(directed)
}

internal val IMAGE_THUMB_EXTS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")

internal fun isWithin(child: File, ancestor: File): Boolean {
    val c = runCatching { child.canonicalPath }.getOrDefault(child.absolutePath)
    val a = runCatching { ancestor.canonicalPath }.getOrDefault(ancestor.absolutePath)
    return c == a || c.startsWith(a + File.separator)
}


enum class FavStorage { INTERNAL, SD, OTHER }

data class FavLocation(
    val storage: FavStorage,
    @StringRes val driveLabel: Int,
    val displayPath: String
)

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

    if (Regex("/files/linuxfs(/|$)").containsMatchIn(abs)) {
        return FavLocation(FavStorage.OTHER, R.string.fm_drive_linux, abs)
    }

    return FavLocation(FavStorage.OTHER, R.string.fm_drive_storage, abs)
}
