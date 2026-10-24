package com.droiddeck.launcher.storage

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.R
import com.droiddeck.launcher.files.FileOps
import com.droiddeck.launcher.frontend.AddedExes
import com.droiddeck.launcher.frontend.AddedGames
import com.droiddeck.launcher.frontend.LibraryCache
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionState
import java.io.File

/**
 * Moves a game added with Games > Add a game to another drive. Unlike a Steam library
 * game it has no manifest; the folder itself moves and every per-folder record (picked
 * exe, stored app id, binds, shortcuts) is rewritten to the new location.
 */
object AddedGameMove {
    private const val SPACE_MARGIN = 64L * 1024 * 1024

    data class Status(
        val name: String,
        val target: File,
        val copied: Long,
        val total: Long,
        val error: String? = null,
        val done: Boolean = false,
    )

    var status by mutableStateOf<Status?>(null)
        private set

    val running: Boolean get() = status?.let { !it.done && it.error == null } == true

    private val main = Handler(Looper.getMainLooper())

    fun start(context: Context, folder: File, name: String, targetParent: File, onFinished: () -> Unit = {}) {
        if (status != null) return
        val app = context.applicationContext
        val total = runCatching { FileOps.totalSize(folder) }.getOrDefault(0L).coerceAtLeast(1L)
        status = Status(name, targetParent, 0, total)
        Thread({
            var last = 0L
            val error = runCatching {
                move(app, folder, targetParent) { copied, _ ->
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - last < 120 && copied < total) return@move
                    last = now
                    main.post { status = status?.copy(copied = copied, total = total) }
                }
            }.getOrElse { e -> app.getString(R.string.storage_move_failed, e.message ?: e.javaClass.simpleName) }
            main.post {
                val current = status
                if (current != null) status = current.copy(
                    copied = if (error == null) current.total else current.copied,
                    error = error,
                    done = error == null,
                )
                StorageState.refresh(app, force = true)
                onFinished()
            }
        }, "added-game-move").start()
    }

    fun dismiss() {
        if (status?.done == true || status?.error != null) status = null
    }

    fun move(context: Context, folder: File, targetParent: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): String? {
        if (SessionState.running) return context.getString(R.string.storage_move_session)
        val source = folder.takeIf { it.isDirectory } ?: return context.getString(R.string.storage_added_move_missing)
        if (samePath(targetParent, source)) return context.getString(R.string.storage_move_same)
        if (inside(targetParent, source)) return context.getString(R.string.storage_move_inside)
        val dest = File(targetParent, source.name)
        if (dest.exists()) return context.getString(R.string.storage_move_exists)
        if (!targetParent.isDirectory && !targetParent.mkdirs()) return context.getString(R.string.storage_move_missing)

        val total = runCatching { FileOps.totalSize(source) }.getOrDefault(0L)
        val free = runCatching { StatFs(nearestExisting(dest).path).availableBytes }.getOrNull()
        if (free != null && total > 0 && (free <= 0 || free < total + SPACE_MARGIN)) {
            return context.getString(
                R.string.storage_move_no_space,
                FileOps.formatBytes(context, total + SPACE_MARGIN),
                FileOps.formatBytes(context, free),
            )
        }

        val oldExe = AddedExes.list(context).firstOrNull { it.folder == source.path }?.let { File(it.exe) }
            ?: SessionPrefs.addedGameExe(context, source.path).takeIf { it.isNotEmpty() }?.let(::File)
        onProgress(0, total.coerceAtLeast(1L))
        val ok = runCatching {
            FileOps.moveWithProgress(source, dest, total.coerceAtLeast(1L), { done, _ ->
                onProgress(done, total.coerceAtLeast(1L))
            })
        }.getOrDefault(false)
        if (!ok) {
            runCatching { FileOps.delete(dest) }
            return context.getString(R.string.storage_move_failed, source.path)
        }
        if (!dest.isDirectory) return context.getString(R.string.storage_move_failed, source.path)

        val movedExe = oldExe?.takeIf { inside(it, source) }?.let { File(dest, it.path.removePrefix(source.path + File.separator)) }
        SessionPrefs.migrateAddedGameFolder(context, source.path, dest.path)
        if (movedExe != null) AddedExes.move(context, source.path, dest.path, movedExe.path)
        val newExe = movedExe?.takeIf { it.isFile }
            ?: SessionPrefs.addedGameExe(context, dest.path).takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isFile }
        if (AddedGames.guestPath(context, newExe ?: dest) == null) AddedGames.bindFolder(context, dest)
        SessionPrefs.setAddedGameRemoved(context, dest.path, false)
        runCatching {
            LibraryCache.save(
                context,
                LibraryCache.load(context).filterNot { it.library == Library.ADDED && it.gameFiles?.path == source.path },
            )
        }
        runCatching {
            AddedGames.single(context, dest.path)?.let { game ->
                com.droiddeck.launcher.stores.SteamLiveShortcuts.sync(context, listOf(game)) { true }
            }
        }
        onProgress(total.coerceAtLeast(1L), total.coerceAtLeast(1L))
        return null
    }

    private fun nearestExisting(file: File): File {
        var current: File? = file.absoluteFile
        while (current != null && !current.exists()) current = current.parentFile
        return current ?: file.absoluteFile
    }

    private fun inside(child: File, parent: File): Boolean {
        val c = canonical(child)
        val p = canonical(parent)
        return c != p && c.startsWith(p.trimEnd(File.separatorChar) + File.separator)
    }

    private fun samePath(a: File, b: File): Boolean = canonical(a) == canonical(b)

    private fun canonical(file: File): String =
        runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
}
