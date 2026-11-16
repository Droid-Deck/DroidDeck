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
import java.io.IOException
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
    private const val SEP = ">"
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
            try {
                purge(guest)
                purge(card)
            } catch (e: Exception) {
                Log.w(TAG, "$id: the old copy of a moved folder could not be removed", e)
            }
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
        if (SessionState.occupied) return@synchronized context.getString(R.string.store_data_session)
        val card = cardDir(context, id) ?: return@synchronized context.getString(R.string.store_data_no_storage)
        val internal = internalDir(context, id)
        val from = if (toCard) internal else card
        val to = if (toCard) card else internal
        onProgress(context.getString(R.string.store_data_moving), -1)
        val staging = File(to.parentFile, ".${to.name}.moving")
        val marker = marker(id, to)
        val placed = ArrayList<File>()
        try {
            if (hasSymlink(from)) return@synchronized context.getString(R.string.store_data_symlinks)
            if (!to.isDirectory && !to.mkdirs()) throw IOException("could not create ${to.path}")
            purge(from)
            purge(to)
            if (entries(to).isNotEmpty()) {
                if (marker !in SessionPrefs.flatpakDataMoving(context)) return@synchronized context.getString(R.string.store_data_failed)
                clear(to)
            }
            val bytes = size(from)
            val free = freeBytes(to)
            if (free < bytes + RESERVE) return@synchronized context.getString(R.string.store_data_space, com.droiddeck.launcher.core.FileUtils.sizeToString(free))
            SessionPrefs.flatpakDataMoving(context).filter { it.startsWith("$id$SEP") && it != marker }.forEach { SessionPrefs.setFlatpakDataMoving(context, it, false) }
            if (!SessionPrefs.setFlatpakDataMoving(context, marker, true)) throw IOException("could not record the move")
            if (staging.exists() && !staging.deleteRecursively()) throw IOException("could not clear ${staging.path}")
            staging.mkdirs()
            entries(from).forEach { it.copyRecursively(File(staging, it.name), overwrite = true) }
            if (size(staging) != bytes) throw IOException("copy is ${size(staging)} bytes, expected $bytes")
            for (entry in entries(staging)) {
                val target = File(to, entry.name)
                if (!entry.renameTo(target)) throw IOException("could not place ${target.path}")
                placed.add(target)
            }
            staging.deleteRecursively()
            if (!SessionPrefs.setFlatpakCardData(context, id, toCard)) {
                SessionPrefs.setFlatpakCardData(context, id, !toCard)
                throw IOException("could not save the data location")
            }
        } catch (e: Exception) {
            Log.w(TAG, "$id: move to ${if (toCard) "game storage" else "internal"} failed", e)
            val cleaned = placed.map { it.deleteRecursively() }.all { it } && (!staging.exists() || staging.deleteRecursively())
            if (cleaned) SessionPrefs.setFlatpakDataMoving(context, marker, false)
            return@synchronized context.getString(R.string.store_data_failed)
        }
        try {
            retire(from)
            if (!toCard && !from.deleteRecursively()) throw IOException("could not remove ${from.path}")
            SessionPrefs.setFlatpakDataMoving(context, marker, false)
            null
        } catch (e: Exception) {
            Log.w(TAG, "$id: the data moved but the old copy could not be removed", e)
            context.getString(R.string.store_data_failed)
        }
    }

    internal fun marker(id: String, to: File) = "$id$SEP${to.path}"

    private fun trash(dir: File) = File(dir.parentFile, ".${dir.name}.old")

    private fun purge(dir: File) {
        val old = trash(dir)
        if (old.exists() && !old.deleteRecursively()) throw IOException("could not remove ${old.path}")
    }

    private fun retire(dir: File) {
        purge(dir)
        val old = trash(dir)
        val entries = entries(dir)
        if (entries.isEmpty()) return
        if (!old.mkdirs()) throw IOException("could not create ${old.path}")
        entries.forEach { if (!it.renameTo(File(old, it.name))) throw IOException("could not retire ${it.path}") }
        purge(dir)
    }

    private fun clear(dir: File) {
        entries(dir).forEach { if (!it.deleteRecursively()) throw IOException("could not remove ${it.path}") }
    }

    internal fun entries(dir: File): List<File> {
        if (!dir.exists()) return emptyList()
        val all = dir.listFiles() ?: throw IOException("could not list ${dir.path}")
        return all.filter { it.name != KEPT }
    }

    private fun walk(top: File) = top.walkTopDown().onFail { _, e -> throw e }

    internal fun hasSymlink(dir: File): Boolean =
        entries(dir).any { top -> walk(top).any { Files.isSymbolicLink(it.toPath()) } }

    internal fun size(dir: File): Long =
        entries(dir).sumOf { top -> walk(top).filter { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }.sumOf { it.length() } }
}
