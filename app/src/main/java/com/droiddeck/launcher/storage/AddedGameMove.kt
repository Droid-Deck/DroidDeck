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
import com.droiddeck.launcher.session.SecondaryLibrary
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
        val cancelRequested: Boolean = false,
    )

    var status by mutableStateOf<Status?>(null)
        private set

    val running: Boolean get() = status?.let { !it.done && it.error == null } == true

    private val main = Handler(Looper.getMainLooper())

    fun start(context: Context, folder: File, name: String, targetParent: File, onFinished: () -> Unit = {}) {
        startInternal(context, folder, name, targetParent, adopt = null, onFinished)
    }

    fun startAdopt(context: Context, folder: File, name: String, target: MissingInstall, onFinished: () -> Unit = {}) {
        startInternal(context, folder, name, target.libraryRoot, adopt = target, onFinished)
    }

    private fun startInternal(context: Context, folder: File, name: String, target: File, adopt: MissingInstall?, onFinished: () -> Unit) {
        if (status != null) return
        val app = context.applicationContext
        val total = runCatching { FileOps.totalSize(folder) }.getOrDefault(0L).coerceAtLeast(1L)
        status = Status(name, target, 0, total)
        Thread({
            var last = 0L
            val report: (Long, Long) -> Unit = report@{ copied, _ ->
                val now = android.os.SystemClock.uptimeMillis()
                if (now - last < 120 && copied < total) return@report
                last = now
                main.post { status = status?.copy(copied = copied, total = total) }
            }
            val error = runCatching {
                if (adopt != null) adoptIntoSteam(app, folder, adopt, report, isCancelled = { status?.cancelRequested == true })
                else move(app, folder, target, report, isCancelled = { status?.cancelRequested == true })
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

    fun cancel() {
        status?.let { if (!it.done && it.error == null) status = it.copy(cancelRequested = true) }
    }

    fun move(context: Context, folder: File, targetParent: File, onProgress: (Long, Long) -> Unit = { _, _ -> }, isCancelled: () -> Boolean = { false }): String? {
        if (SessionState.running) return context.getString(R.string.storage_move_session)
        val source = folder.takeIf { it.isDirectory } ?: return context.getString(R.string.storage_added_move_missing)
        if (samePath(targetParent, source)) return context.getString(R.string.storage_move_same)
        if (inside(targetParent, source)) return context.getString(R.string.storage_move_inside)
        val dest = File(targetParent, source.name)
        if (dest.exists()) return context.getString(R.string.storage_move_exists)
        if (!targetParent.isDirectory && !targetParent.mkdirs()) return context.getString(R.string.storage_move_missing)

        val total = runCatching { FileOps.totalSize(source) }.getOrDefault(0L)
        // Zero or negative means the volume cannot report free space; only refuse when it
        // measurably reports less than needed, mirroring SteamGameMove.
        val free = runCatching { StatFs(nearestExisting(dest).path).availableBytes }.getOrNull()
        if (free != null && total > 0 && free > 0 && free < total + SPACE_MARGIN) {
            return context.getString(
                R.string.storage_move_no_space,
                FileOps.formatBytes(context, total + SPACE_MARGIN),
                FileOps.formatBytes(context, free),
            )
        }

        val oldExe = AddedExes.list(context).firstOrNull { it.folder == source.path }?.let { File(it.exe) }
            ?: SessionPrefs.addedGameExe(context, source.path).takeIf { it.isNotEmpty() }?.let(::File)
        MoveJournal.write(context, MoveJournal.Entry(
            kind = "added",
            name = source.name,
            items = listOf(source.path to dest.path),
            registration = null,
            startedAt = System.currentTimeMillis(),
        ))
        onProgress(0, total.coerceAtLeast(1L))
        val outcome = runCatching {
            FileOps.moveWithProgress(source, dest, total.coerceAtLeast(1L), { done, _ ->
                onProgress(done, total.coerceAtLeast(1L))
            }, isCancelled)
        }
        if (outcome.isFailure && outcome.exceptionOrNull() is java.util.concurrent.CancellationException) {
            runCatching { FileOps.delete(dest) }
            MoveJournal.clear(context)
            return context.getString(R.string.storage_move_cancelled)
        }
        if (!outcome.getOrDefault(false)) {
            runCatching { FileOps.delete(dest) }
            MoveJournal.clear(context)
            return context.getString(R.string.storage_move_failed, source.path)
        }
        if (!dest.isDirectory) {
            MoveJournal.clear(context)
            return context.getString(R.string.storage_move_failed, source.path)
        }

        val hadEntry = AddedExes.list(context).any { it.folder == source.path }
        val movedExe = oldExe?.takeIf { inside(it, source) }?.let { File(dest, it.path.removePrefix(source.path + File.separator)) }
        SessionPrefs.migrateAddedGameFolder(context, source.path, dest.path)
        if (hadEntry && movedExe != null) AddedExes.move(context, source.path, dest.path, movedExe.path)
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
        // The running client is always stopped for a move, so live shortcut edits are impossible.
        // Republishing refreshes the listing and the launcher now; the session-start script
        // rewrites the shortcut's exe and start dir by app id.
        com.droiddeck.launcher.frontend.AddedGamesPublisher.later(context)
        MoveJournal.clear(context)
        onProgress(total.coerceAtLeast(1L), total.coerceAtLeast(1L))
        return null
    }

    /**
     * Installs an added folder as a Steam game's files: the folder moves into
     * steamapps/common/<installDir> of the library whose manifest names it, the added-game
     * records are dropped, and Steam detects the game on its next start. When the added
     * game ran before, its Proton prefix moves with it so saves and settings survive.
     */
    fun adoptIntoSteam(context: Context, folder: File, target: MissingInstall, onProgress: (Long, Long) -> Unit = { _, _ -> }, isCancelled: () -> Boolean = { false }): String? {
        if (SessionState.running) return context.getString(R.string.storage_move_session)
        val source = folder.takeIf { it.isDirectory } ?: return context.getString(R.string.storage_added_move_missing)
        val cleanDir = target.installDir.takeIf { it.isNotEmpty() && '/' !in it && '\\' !in it }
            ?: return context.getString(R.string.storage_move_bad_source)
        val common = File(target.libraryRoot, "steamapps/common")
        if (!common.isDirectory && !common.mkdirs()) return context.getString(R.string.storage_move_missing)
        if (samePath(common, source) || inside(common, source)) return context.getString(R.string.storage_move_inside)
        val dest = File(common, cleanDir)
        if (dest.exists() && (dest.isFile || !(dest.listFiles()?.isEmpty() == true))) {
            return context.getString(R.string.storage_move_exists)
        }
        if (dest.isDirectory) runCatching { dest.delete() }

        val total = runCatching { FileOps.totalSize(source) }.getOrDefault(0L)
        val free = runCatching { StatFs(nearestExisting(dest).path).availableBytes }.getOrNull()
        if (free != null && total > 0 && free > 0 && free < total + SPACE_MARGIN) {
            return context.getString(
                R.string.storage_move_no_space,
                FileOps.formatBytes(context, total + SPACE_MARGIN),
                FileOps.formatBytes(context, free),
            )
        }
        val oldId = SessionPrefs.addedGameStoredAppId(context, source.path)
        MoveJournal.write(context, MoveJournal.Entry(
            kind = "adopt",
            name = source.name,
            items = listOf(source.path to dest.path),
            registration = null,
            startedAt = System.currentTimeMillis(),
        ))
        onProgress(0, total.coerceAtLeast(1L))
        val outcome = runCatching {
            FileOps.moveWithProgress(source, dest, total.coerceAtLeast(1L), { done, _ ->
                onProgress(done, total.coerceAtLeast(1L))
            }, isCancelled)
        }
        if (outcome.isFailure && outcome.exceptionOrNull() is java.util.concurrent.CancellationException) {
            runCatching { FileOps.delete(dest) }
            MoveJournal.clear(context)
            return context.getString(R.string.storage_move_cancelled)
        }
        if (!outcome.getOrDefault(false)) {
            runCatching { FileOps.delete(dest) }
            MoveJournal.clear(context)
            return context.getString(R.string.storage_move_failed, source.path)
        }
        if (!dest.isDirectory) {
            MoveJournal.clear(context)
            return context.getString(R.string.storage_move_failed, source.path)
        }

        migratePrefix(context, oldId, target)
        AddedExes.drop(context, source.path)
        SessionPrefs.migrateAddedGameFolder(context, source.path, dest.path, dropBinds = true)
        runCatching {
            LibraryCache.save(
                context,
                LibraryCache.load(context).filterNot { it.library == Library.ADDED && it.gameFiles?.path == source.path },
            )
        }
        com.droiddeck.launcher.frontend.AddedGamesPublisher.later(context)
        MoveJournal.clear(context)
        onProgress(total.coerceAtLeast(1L), total.coerceAtLeast(1L))
        return null
    }

    /**
     * Moves the added game's Proton prefix and shader cache onto the Steam app id, so saves
     * and settings survive the adoption. Only moves when the Steam side has no prefix yet;
     * an existing one is left untouched and the old folder stays for manual cleanup.
     */
    private fun migratePrefix(context: Context, oldId: Long?, target: MissingInstall) {
        if (oldId == null) return
        val app = context.applicationContext
        val names = listOf(oldId and 0xFFFFFFFFL, oldId, oldId.toInt().toLong()).map { it.toString() }.distinct()
        for (kind in listOf("compatdata", "shadercache")) {
            val old = names.mapNotNull { findCompatData(context, kind, it) }.firstOrNull() ?: continue
            val data = targetDataRoot(app, target.libraryRoot)
            val fresh = File(File(data, "steamapps/$kind"), target.appId.toString())
            if (fresh.exists()) {
                android.util.Log.i("AddedGameMove", "$kind for $oldId already installed as ${target.appId}; keeping the old folder")
                continue
            }
            if (!fresh.parentFile.isDirectory && !fresh.parentFile.mkdirs()) continue
            val ok = runCatching {
                FileOps.moveWithProgress(old, fresh, FileOps.totalSize(old).coerceAtLeast(1L), null)
            }.getOrDefault(false)
            android.util.Log.i("AddedGameMove", "$kind $old -> $fresh: $ok")
        }
    }

    private fun findCompatData(context: Context, kind: String, id: String): File? {
        val dirs = ArrayList<File>()
        dirs.add(File(SessionPrefs.steamRoot(context, "arm64"), "steamapps/$kind/$id"))
        runCatching { SessionPrefs.steamRoot(context, "x86_64") }.getOrNull()
            ?.let { dirs.add(File(it, "steamapps/$kind/$id")) }
        val libs = runCatching {
            Library.steamLibraries(context, "arm64") + Library.steamLibraries(context, "x86_64")
        }.getOrDefault(emptyList())
        for ((root, _) in libs.distinctBy { it.first.absolutePath }) {
            dirs.add(File(root, "steamapps/$kind/$id"))
            runCatching {
                dirs.add(File(SecondaryLibrary.privateRoot(context.filesDir, root), "steamapps/$kind/$id"))
            }
        }
        return dirs.firstOrNull { it.isDirectory }
    }

    private fun targetDataRoot(context: Context, libraryRoot: File): File {
        val internal = runCatching { SessionPrefs.steamRoot(context, "arm64") }.getOrNull()
        return if (internal != null && samePath(libraryRoot, internal)) libraryRoot
        else SecondaryLibrary.privateRoot(context.filesDir, libraryRoot)
    }

    private fun nearestExisting(file: File): File {        var current: File? = file.absoluteFile
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
