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
import com.droiddeck.launcher.frontend.AddedGames
import com.droiddeck.launcher.session.GameStorage
import com.droiddeck.launcher.session.SecondaryLibrary
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.session.SteamLibraryRegistry
import java.io.File
import java.util.UUID

object SteamGameMove {
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

    fun start(context: Context, game: GameUsage, target: File, onFinished: () -> Unit = {}) {
        if (status != null) return
        val app = context.applicationContext
        status = Status(game.name, target, 0, game.total)
        Thread({
            var last = 0L
            val error = runCatching {
                move(app, game, target) { copied, total ->
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
        }, "steam-game-move").start()
    }

    fun dismiss() {
        if (status?.done == true || status?.error != null) status = null
    }

    fun move(context: Context, game: GameUsage, target: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): String? {
        if (SessionState.running) return context.getString(R.string.storage_move_session)
        val gameDir = game.gameDir?.takeIf { it.isDirectory } ?: return context.getString(R.string.storage_move_bad_source)
        val manifest = game.manifest?.takeIf { it.isFile } ?: return context.getString(R.string.storage_move_bad_source)
        val sourceLibrary = game.libraryRoot?.takeIf { it.isDirectory } ?: return context.getString(R.string.storage_move_bad_source)
        val sourceData = game.dataRoot ?: return context.getString(R.string.storage_move_bad_source)
        val appId = game.appId
        if (!declares(manifest, appId)) return context.getString(R.string.storage_move_bad_source)
        if (samePath(target, sourceLibrary)) return context.getString(R.string.storage_move_same)
        if (inside(target, sourceLibrary)) return context.getString(R.string.storage_move_inside)
        val installDir = gameDir.name.takeIf { it.isNotEmpty() && '/' !in it && '\\' !in it }
            ?: return context.getString(R.string.storage_move_bad_source)
        // A freshly plugged drive has no steam folder yet; create the target root and let
        // GameStorage.prepare probe real writability below instead of trusting canWrite on FUSE.
        if (!target.isDirectory && !target.mkdirs()) return context.getString(R.string.storage_move_missing)

        val arch = if (game.x64) "x86_64" else "arm64"
        val plan = when (val resolved = resolve(context, game, target, arch)) {
            is Resolution.Error -> return resolved.message
            is Resolution.Ok -> resolved.plan
        }
        val targetGame = File(plan.library, "steamapps/common/$installDir")
        val targetManifest = File(plan.library, "steamapps/appmanifest_$appId.acf")
        if (samePath(gameDir, targetGame)) return context.getString(R.string.storage_move_same)
        if (targetGame.exists() || targetManifest.exists()) return context.getString(R.string.storage_move_exists)

        val targetData = plan.data
        fun dataItem(name: String): File {
            val private = File(sourceData, "steamapps/$name/$appId")
            if (samePath(sourceData, sourceLibrary) || private.exists()) return private
            return File(sourceLibrary, "steamapps/$name/$appId").takeIf { it.exists() } ?: private
        }
        val prefixSource = dataItem("compatdata")
        val shaderSource = dataItem("shadercache")
        val items = ArrayList<Item>()
        items.add(Item(gameDir, targetGame))
        items.add(Item(File(sourceLibrary, "steamapps/workshop/content/$appId"), File(plan.library, "steamapps/workshop/content/$appId")))
        items.add(Item(
            File(sourceLibrary, "steamapps/workshop/appworkshop_$appId.acf"),
            File(plan.library, "steamapps/workshop/appworkshop_$appId.acf"),
        ))
        items.add(Item(prefixSource, File(targetData, "steamapps/compatdata/$appId")))
        items.add(Item(shaderSource, File(targetData, "steamapps/shadercache/$appId")))
        items.add(Item(File(sourceLibrary, "steamapps/downloading/$appId"), File(plan.library, "steamapps/downloading/$appId")))
        items.add(Item(manifest, targetManifest))
        val active = items.filter { it.source.exists() && !samePath(it.source, it.destination) }
        val staleSources = if (samePath(sourceData, sourceLibrary)) emptyList() else listOf(
            prefixSource to File(sourceLibrary, "steamapps/compatdata/$appId"),
            shaderSource to File(sourceLibrary, "steamapps/shadercache/$appId"),
        ).filter { (moved, legacy) -> !samePath(moved, legacy) }
        val legacyTargetData = if (samePath(targetData, plan.library)) emptyList() else listOf(
            File(plan.library, "steamapps/compatdata/$appId"),
            File(plan.library, "steamapps/shadercache/$appId"),
        )
        val duplicate = items.firstOrNull { it.destination.exists() }
            ?: legacyTargetData.firstOrNull { it.exists() }
        if (duplicate != null) return context.getString(R.string.storage_move_exists)

        val problem = GameStorage.prepare(context, plan.library.path)
        if (problem != null) return problem
        val marker = File(plan.library, "libraryfolder.vdf")
        val markerExisted = marker.isFile
        if (!plan.internal && !markerExisted && writeMarker(marker, plan.label) != true) {
            if (marker.isFile) marker.delete()
            return context.getString(R.string.storage_move_not_writable, plan.library.path)
        }
        val hadGames = GameStorage.hasInstalledGames(plan.library)
        val wasRegistered = SteamLibraryRegistry.all(context).any { it.arch == arch && samePath(it.host, plan.library) }
        if (plan.register) {
            try {
                SteamLibraryRegistry.register(context, arch, plan.library, plan.label)
            } catch (e: Exception) {
                if (!markerExisted) marker.delete()
                return context.getString(R.string.storage_move_failed, e.message ?: plan.library.path)
            }
        }

        val sizes = runCatching { active.associateWith { FileOps.totalSize(it.source) } }.getOrElse { e ->
            cleanupRegistration(context, arch, plan, marker, markerExisted, hadGames, wasRegistered)
            return context.getString(R.string.storage_move_failed, e.message ?: e.javaClass.simpleName)
        }
        val spaceError = runCatching { freeSpaceError(context, active, sizes) }.getOrElse { e ->
            cleanupRegistration(context, arch, plan, marker, markerExisted, hadGames, wasRegistered)
            return context.getString(R.string.storage_move_failed, e.message ?: e.javaClass.simpleName)
        }
        if (spaceError != null) {
            cleanupRegistration(context, arch, plan, marker, markerExisted, hadGames, wasRegistered)
            return spaceError
        }

        val total = sizes.values.sum()
        var copied = 0L
        onProgress(0, total)
        val completed = ArrayList<Item>()
        for (item in active) {
            val size = sizes[item] ?: 0L
            val ok = runCatching {
                // The size was already measured above; pass it so the copy skips its own walk.
                FileOps.moveWithProgress(item.source, item.destination, maxOf(size, 1L), { done, _ ->
                    onProgress(copied + done.coerceAtMost(size), total)
                })
            }.getOrDefault(false)
            if (!ok) {
                FileOps.delete(item.destination)
                val rolledBack = rollback(completed)
                if (rolledBack) cleanupRegistration(context, arch, plan, marker, markerExisted, hadGames, wasRegistered)
                val base = context.getString(R.string.storage_move_failed, item.source.path)
                return if (rolledBack) base else base + " " + context.getString(R.string.storage_move_rollback)
            }
            completed.add(item)
            copied += size
            onProgress(copied, total)
        }
        if (staleSources.isNotEmpty()) {
            // The private prefix is authoritative; a migration may have left the library's old copy behind.
            runCatching {
                for ((moved, legacy) in staleSources) {
                    if (!legacy.exists()) continue
                    val movedSize = sizes.entries.firstOrNull { it.key.source == moved }?.value ?: 0
                    if (FileOps.totalSize(legacy) > movedSize) continue
                    FileOps.delete(legacy)
                }
            }
        }
        onProgress(total, total)
        return null
    }

    private class Item(val source: File, val destination: File)

    private enum class Kind { INTERNAL, CARD, ADDED, CUSTOM }

    private class Plan(
        val library: File,
        val data: File,
        val label: String,
        val internal: Boolean,
        val register: Boolean,
    )

    private sealed class Resolution {
        class Ok(val plan: Plan) : Resolution()
        class Error(val message: String) : Resolution()
    }

    private class Known(val root: File, val arch: String, val kind: Kind, val label: String)

    private fun resolve(context: Context, game: GameUsage, target: File, arch: String): Resolution {
        val targetPath = canonical(target)
        val gameDir = game.gameDir
        if (gameDir != null && (inside(target, gameDir) || samePath(target, gameDir))) {
            return Resolution.Error(context.getString(R.string.storage_move_inside))
        }
        val dataRoot = game.dataRoot
        if (dataRoot != null && (inside(target, dataRoot) || samePath(target, dataRoot))) {
            return Resolution.Error(context.getString(R.string.storage_move_inside))
        }
        val known = knownRoots(context)
        val matches = known.filter { canonical(it.root) == targetPath }
        val sameClient = matches.firstOrNull { it.arch == arch }
        val otherClient = matches.firstOrNull { it.arch != arch }
        // A folder that is already a moved or games-folder library for the other client
        // cannot become one for this client.
        if (sameClient != null && otherClient != null &&
            sameClient.kind != Kind.CARD && sameClient.kind != Kind.INTERNAL
        ) {
            return Resolution.Error(context.getString(R.string.storage_move_other_client))
        }
        val match = sameClient ?: otherClient
        if (match != null && match.arch != arch) {
            return Resolution.Error(context.getString(R.string.storage_move_other_client))
        }
        if (match != null) {
            val internal = match.kind == Kind.INTERNAL
            return Resolution.Ok(Plan(
                match.root,
                if (internal) match.root else SecondaryLibrary.privateRoot(context.filesDir, match.root),
                match.label,
                internal,
                match.kind == Kind.ADDED,
            ))
        }
        if (known.any { inside(target, it.root) }) {
            return Resolution.Error(context.getString(R.string.storage_move_inside))
        }
        val label = target.name.ifEmpty { context.getString(R.string.gstore_folder) }
            .replace('"', ' ').replace('\\', ' ').replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
        return Resolution.Ok(Plan(
            target,
            SecondaryLibrary.privateRoot(context.filesDir, target),
            label,
            false,
            true,
        ))
    }

    private fun knownRoots(context: Context): List<Known> {
        val out = ArrayList<Known>()
        val internalArm = SessionPrefs.steamRoot(context, "arm64")
        val internalX64 = SessionPrefs.steamRoot(context, "x86_64")
        out.add(Known(internalArm, "arm64", Kind.INTERNAL, "Internal storage"))
        out.add(Known(internalX64, "x86_64", Kind.INTERNAL, "Internal storage"))
        val card = GameStorage.effective(context)
        val cardArm = card?.let { File(it.path) }
        val cardX64 = card?.let { File(it.path, GameStorage.X64_CARD_DIR) }
        if (card != null) {
            out.add(Known(File(card.path), "arm64", Kind.CARD, card.label))
            out.add(Known(File(card.path, GameStorage.X64_CARD_DIR), "x86_64", Kind.CARD, card.label))
        }
        for (library in GameStorage.gamesFolderLibraries(context)) {
            out.add(Known(library.host, "arm64", Kind.ADDED, library.label))
        }
        // Games folders are arm64 libraries; they must not overlap the card or internal roots.
        val bases = listOfNotNull(internalArm, internalX64, cardArm, cardX64).map { canonical(it) }
        for (root in AddedGames.roots(context)) {
            if (!root.host.isDirectory || !root.host.canWrite()) continue
            val host = canonical(root.host)
            if (bases.any { base -> host == base || host.startsWith("$base/") || base.startsWith("$host/") }) continue
            out.add(Known(root.host, "arm64", Kind.ADDED, GameStorage.labelFor(context, root.host.path)))
        }
        for (library in SteamLibraryRegistry.all(context)) {
            out.add(Known(library.host, library.arch, Kind.CUSTOM, library.label))
        }
        return out.distinctBy { it.arch to canonical(it.root) }
    }

    private fun cleanupRegistration(
        context: Context,
        arch: String,
        plan: Plan,
        marker: File,
        markerExisted: Boolean,
        hadGames: Boolean,
        wasRegistered: Boolean,
    ) {
        if (hadGames || wasRegistered) return
        if (runCatching { GameStorage.hasInstalledGames(plan.library) }.getOrDefault(true)) return
        if (plan.register) runCatching { SteamLibraryRegistry.unregister(context, arch, plan.library) }
        if (!plan.internal && !markerExisted) runCatching { marker.delete() }
    }

    private fun rollback(completed: List<Item>): Boolean {
        var ok = true
        for (item in completed.asReversed()) {
            if (!item.destination.exists() || item.source.exists()) continue
            if (!FileOps.moveWithProgress(item.destination, item.source, null)) ok = false
        }
        return ok
    }

    private fun freeSpaceError(context: Context, items: List<Item>, sizes: Map<Item, Long>): String? {
        val groups = LinkedHashMap<String, MutableList<Item>>()
        for (item in items) {
            if (sameVolume(item.source, item.destination)) continue
            val key = volumeId(item.destination) ?: item.destination.absolutePath
            groups.getOrPut(key) { ArrayList() }.add(item)
        }
        if (groups.isEmpty()) return null
        for (group in groups.values) {
            val needed = group.sumOf { sizes[it] ?: 0L }
            if (needed <= 0L) continue
            val required = needed + SPACE_MARGIN
            val free = runCatching { StatFs(nearestExisting(group.first().destination).path).availableBytes }.getOrNull() ?: continue
            if (free <= 0L || free >= required) continue
            return context.getString(
                R.string.storage_move_no_space,
                FileOps.formatBytes(context, required),
                FileOps.formatBytes(context, free),
            )
        }
        return null
    }

    private fun writeMarker(file: File, label: String): Boolean = runCatching {
        val safe = label.replace('"', ' ').replace('\\', ' ').replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
        file.writeText(
            "\"libraryfolder\"\n{\n\t\"contentid\"\t\t\"%d\"\n\t\"label\"\t\t\"%s\"\n}\n"
                .format(UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE, safe),
        )
        true
    }.getOrDefault(false)

    private fun declares(manifest: File, appId: Int): Boolean =
        runCatching { Regex("\"appid\"\\s+\"$appId\"").containsMatchIn(manifest.readText()) }.getOrDefault(false)

    private fun sameVolume(a: File, b: File): Boolean {
        val left = volumeId(a) ?: return true
        val right = volumeId(b) ?: return true
        return left == right
    }

    private fun volumeId(file: File): String? = runCatching {
        android.system.Os.stat(nearestExisting(file).path).st_dev.toString()
    }.getOrNull() ?: runCatching {
        java.nio.file.Files.getFileStore(nearestExisting(file).toPath()).name()
    }.getOrNull()

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
