package com.droiddeck.launcher.runtime

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.GameStorage
import com.droiddeck.launcher.session.SecondaryLibrary
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionState
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

object FlatpakData {
    private const val TAG = "FlatpakData"
    private const val DIR = "flatpak-data"
    private const val HOST = "root/.var/app"
    private const val GUEST = "/root/.var/app"
    private const val KEPT = ".ld.so"
    private const val RESERVE = 64L shl 20
    private val ID = Regex("(?!\\.+$)[A-Za-z0-9_.-]+")
    private val lock = Any()

    fun enabled(context: Context): Set<String> = SessionPrefs.flatpakCardData(context)

    fun available(context: Context): Boolean = GameStorage.effective(context) != null

    internal fun cardDir(context: Context, id: String): File? =
        GameStorage.effective(context)?.let { File(File(SecondaryLibrary.contentPath(File(it.path)), DIR), id) }

    internal fun internalDir(context: Context, id: String) = File(LinuxRuntime.rootDir(context), "$HOST/$id")

    fun binds(context: Context): List<String> = synchronized(lock) {
        val ids = enabled(context).filter { ID.matches(it) }.sorted()
        if (ids.isEmpty()) return@synchronized emptyList()
        if (!available(context)) {
            Log.w(TAG, "game storage is not attached; ${ids.size} app(s) use their internal data this session")
            return@synchronized emptyList()
        }
        ids.flatMap { id ->
            val card = cardDir(context, id) ?: return@flatMap emptyList()
            val guest = internalDir(context, id)
            val kept = File(guest, KEPT)
            if (!card.isDirectory && !card.mkdirs() || !kept.isDirectory && !kept.mkdirs() || !File(card, KEPT).isDirectory && !File(card, KEPT).mkdirs()) {
                Log.w(TAG, "$id: data folder could not be prepared; internal this session")
                return@flatMap emptyList()
            }
            listOf("${card.path}:$GUEST/$id", "${kept.path}:$GUEST/$id/$KEPT")
        }
    }

    fun move(
        context: Context, id: String, toCard: Boolean, onProgress: (String, Int) -> Unit,
        freeBytes: (File) -> Long = { runCatching { StatFs(it.path).availableBytes }.getOrDefault(0L) },
    ): String? = synchronized(lock) {
        if (!ID.matches(id)) return@synchronized context.getString(R.string.store_data_failed)
        if (SessionState.running) return@synchronized context.getString(R.string.store_data_session)
        val card = cardDir(context, id) ?: return@synchronized context.getString(R.string.store_data_no_storage)
        val internal = internalDir(context, id)
        val from = if (toCard) internal else card
        val to = if (toCard) card else internal
        onProgress(context.getString(R.string.store_data_moving), -1)
        if (toCard && hasSymlink(from)) return@synchronized context.getString(R.string.store_data_symlinks)
        if (!to.isDirectory && !to.mkdirs()) return@synchronized context.getString(R.string.store_data_failed)
        if (entries(to).isNotEmpty()) return@synchronized context.getString(R.string.store_data_failed)
        val bytes = size(from)
        val free = freeBytes(to)
        if (free < bytes + RESERVE) return@synchronized context.getString(R.string.store_data_space, com.droiddeck.launcher.core.FileUtils.sizeToString(free))
        val staging = File(to.parentFile, ".${to.name}.moving")
        val placed = ArrayList<File>()
        try {
            staging.deleteRecursively()
            staging.mkdirs()
            entries(from).forEach { it.copyRecursively(File(staging, it.name), overwrite = true) }
            if (size(staging) != bytes) throw java.io.IOException("copy is ${size(staging)} bytes, expected $bytes")
            for (entry in entries(staging)) {
                val target = File(to, entry.name)
                if (!entry.renameTo(target)) throw java.io.IOException("could not place ${target.path}")
                placed.add(target)
            }
            staging.deleteRecursively()
        } catch (e: Exception) {
            Log.w(TAG, "$id: move to ${if (toCard) "game storage" else "internal"} failed", e)
            placed.forEach { it.deleteRecursively() }
            staging.deleteRecursively()
            return@synchronized context.getString(R.string.store_data_failed)
        }
        SessionPrefs.setFlatpakCardData(context, id, toCard)
        if (toCard) entries(from).forEach { it.deleteRecursively() } else from.deleteRecursively()
        null
    }

    private fun entries(dir: File): List<File> = dir.listFiles().orEmpty().filter { it.name != KEPT }

    internal fun hasSymlink(dir: File): Boolean =
        entries(dir).any { top -> top.walkTopDown().any { Files.isSymbolicLink(it.toPath()) } }

    internal fun size(dir: File): Long =
        entries(dir).sumOf { top -> top.walkTopDown().filter { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }.sumOf { it.length() } }
}
