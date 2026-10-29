package com.droiddeck.launcher.stores

import android.content.Context
import android.os.StatFs
import android.os.storage.StorageManager
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.GameStorage
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import java.util.zip.CRC32

object StoreInstallRoot {
    const val SPACE_UNKNOWN = -1L
    private const val INTERNAL = "root/Games/Stores"
    private const val ON_VOLUME = "Games"
    const val GUEST_EXTERNAL = "/mnt/droiddeck-stores"

    class Target(val root: File, val label: String, val removable: Boolean, val folder: Boolean = false) {
        val freeBytes: Long get() = availableBytes(root)
    }

    data class StorageCheck(
        val enough: Boolean,
        val requiredBytes: Long,
        val availableBytes: Long,
        val errorMessage: String? = null
    )

    @JvmStatic
    fun availableBytes(file: File): Long = probeAvailableBytes(file, { StatFs(it.path).availableBytes }, { it.usableSpace })

    internal fun probeAvailableBytes(file: File, statBytes: (File) -> Long, usableBytes: (File) -> Long): Long {
        var dir: File? = if (file.isDirectory) file else file.parentFile
        if (dir == null) dir = file
        while (dir != null && !dir.exists()) dir = dir.parentFile
        val existing = dir ?: return SPACE_UNKNOWN
        return try {
            statBytes(existing).coerceAtLeast(SPACE_UNKNOWN)
        } catch (e: Exception) {
            StoresState.logLine("storage: StatFs failed for store install target (${e.javaClass.simpleName})")
            try {
                // File's zero also means an unavailable measurement; only StatFs establishes zero.
                usableBytes(existing).takeIf { it > 0L } ?: SPACE_UNKNOWN
            } catch (fallback: Exception) {
                StoresState.logLine("storage: usable-space fallback failed for store install target (${fallback.javaClass.simpleName})")
                SPACE_UNKNOWN
            }
        }
    }

    @JvmStatic
    fun insufficientSpace(required: Long, available: Long): Boolean = required > 0L && available >= 0L && available < required

    /**
     * The catalog size is the known minimum for an install. Epic's actual chunk-cache requirement
     * is determined from its manifest before chunk downloads begin.
     */
    fun estimatedBytesNeeded(item: CatalogItem): Long = StoreSizes.size(item).coerceAtLeast(0L)

    /** Pre-install storage check to detect insufficient space before starting. */
    fun checkStorage(context: Context, item: CatalogItem, targetRoot: File): StorageCheck {
        val size = estimatedBytesNeeded(item)
        val existing = existingFolder(context, item.store, item.id, item.title)
        val freeTarget = availableBytes(existing ?: targetRoot)
        // Backends account for completed files on resume; the catalog cannot describe remaining work.
        // Comparing the full catalog size to remaining free space would reject valid resumes.
        if (item.store == Store.EPIC || existing != null) return StorageCheck(enough = true, requiredBytes = size, availableBytes = freeTarget)
        if (size <= 0L) return StorageCheck(enough = true, requiredBytes = 0L, availableBytes = freeTarget)
        if (insufficientSpace(size, freeTarget)) {
            val msg = context.getString(com.droiddeck.launcher.R.string.stores_storage_insufficient, formatBytes(size), formatBytes(freeTarget))
            return StorageCheck(enough = false, requiredBytes = size, availableBytes = freeTarget, errorMessage = msg)
        }
        return StorageCheck(enough = true, requiredBytes = size, availableBytes = freeTarget)
    }

    fun internalRoot(context: Context): File = File(LinuxRuntime.rootDir(context), INTERNAL)

    fun targets(context: Context): List<Target> {
        val internal = Target(internalRoot(context), context.getString(com.droiddeck.launcher.R.string.games_internal), removable = false)
        val custom = customTarget(context)
        val cards = GameStorage.options(context).map { Target(File(it.path, ON_VOLUME), it.name, removable = true) }
        val library = GameStorage.effective(context)?.let { lib ->
            val root = File(lib.path, ON_VOLUME)
            if (cards.any { it.root.absolutePath == root.absolutePath }) return@let null
            Target(root, folderLabel(context, File(lib.path)), removable = true, folder = true)
        }
        return (listOf(internal) + listOfNotNull(custom) + cards + listOfNotNull(library))
            .distinctBy { it.root.absolutePath }
    }

    fun defaultStorageFolder(context: Context): File? =
        SessionPrefs.defaultStorageFolder(context).takeIf { it.isNotBlank() }?.let(::File)

    fun customTarget(context: Context): Target? {
        val folder = defaultStorageFolder(context) ?: return null
        // Writes are probed when the folder is chosen; File.canWrite() is unreliable on FUSE volumes.
        if (!folder.isDirectory) return null
        val label = SessionPrefs.defaultStorageFolderLabel(context).ifBlank { folderLabel(context, folder) }
        return Target(File(folder, ON_VOLUME), label, removable = true, folder = true)
    }

    fun detectedExternalTarget(context: Context): Target? {
        val custom = defaultStorageFolder(context)?.let { File(it, ON_VOLUME) }?.absolutePath
        return externalTarget(targets(context).filter { it.root.absolutePath != custom }, internalRoot(context))
    }

    fun installRoot(context: Context): File = internalRoot(context)

    fun externalTarget(context: Context): Target? =
        if (SessionPrefs.defaultStorageFolder(context).isNotBlank()) customTarget(context)
        else detectedExternalTarget(context)

    internal fun externalTarget(targets: List<Target>, internalRoot: File): Target? =
        targets.firstOrNull { it.folder && it.root.absolutePath != internalRoot.absolutePath }
            ?: targets.firstOrNull { it.removable && it.root.absolutePath != internalRoot.absolutePath }

    fun defaultTarget(context: Context): Target? = when (SessionPrefs.defaultStorage(context)) {
        SessionPrefs.DEFAULT_STORAGE_INTERNAL -> Target(internalRoot(context), context.getString(com.droiddeck.launcher.R.string.games_internal), removable = false)
        SessionPrefs.DEFAULT_STORAGE_EXTERNAL -> externalTarget(context)
        else -> null
    }

    fun libraryRoot(context: Context): File? = GameStorage.effective(context)?.let { File(it.path, ON_VOLUME) }

    fun roots(context: Context): List<File> {
        val cards = GameStorage.options(context).map { File(it.path, ON_VOLUME) }
        val custom = defaultStorageFolder(context)?.takeIf { it.isDirectory }?.let { File(it, ON_VOLUME) }
        val library = GameStorage.effective(context)?.let { File(it.path, ON_VOLUME) }
        return (listOf(internalRoot(context)) + cards + listOfNotNull(custom) + listOfNotNull(library))
            .distinctBy { it.absolutePath }
    }

    fun externalRoots(context: Context): List<Pair<File, String>> {
        val library = GameStorage.effective(context)?.path
        val used = mutableSetOf<String>()
        return roots(context)
            .filter { !isInternal(context, it) && (library == null || !it.absolutePath.startsWith("$library/")) }
            .map { root ->
                val base = guestFor(context, root)
                val guest = if (used.add(base)) base else {
                    val suffix = "%08x".format(CRC32().apply { update(root.absolutePath.toByteArray()) }.value)
                    "$base-$suffix".also { used.add(it) }
                }
                root to guest
            }
    }

    fun folderLabel(context: Context, folder: File): String {
        val name = folder.name.ifBlank { context.getString(com.droiddeck.launcher.R.string.gstore_folder) }
        val volume = runCatching {
            context.getSystemService(StorageManager::class.java)?.getStorageVolume(folder)?.getDescription(context)
        }.getOrNull()
        return if (volume.isNullOrBlank() || volume.equals("android", true) || volume.equals(name, true)) name
        else context.getString(com.droiddeck.launcher.R.string.gstore_folder_on_volume, name, volume)
    }

    fun prepareDefaultFolder(context: Context, folder: File): String? {
        if (!folder.isDirectory) return context.getString(com.droiddeck.launcher.R.string.gstore_cannot_create, folder.path)
        val root = File(folder, ON_VOLUME)
        if (!root.isDirectory && !root.mkdirs()) return context.getString(com.droiddeck.launcher.R.string.gstore_cannot_create, folder.path)
        return try {
            File.createTempFile(".droiddeck-write-probe", null, root).delete()
            null
        } catch (e: Exception) {
            context.getString(com.droiddeck.launcher.R.string.gstore_cannot_write_detail, root.path, e.message)
        }
    }

    /** Xodus has its own PRoot mount table and cannot see the selected Steam library's SD bind. */
    fun externalRootsForXodus(context: Context): List<Pair<File, String>> {
        val roots = externalRoots(context).toMutableList()
        val selectedLibrary = GameStorage.effective(context)?.let { File(it.path, ON_VOLUME) }
            ?.takeUnless { isInternal(context, it) }
        if (selectedLibrary != null && roots.none { it.first.absolutePath == selectedLibrary.absolutePath }) {
            roots += selectedLibrary to guestFor(context, selectedLibrary)
        }
        return roots
    }

    fun externalBindSpecs(context: Context): List<String> = externalBindSpecs(externalRoots(context), LinuxRuntime.rootDir(context))

    fun externalBindSpecsForXodus(context: Context): List<String> =
        externalBindSpecs(externalRootsForXodus(context), LinuxRuntime.rootDir(context))

    internal fun externalBindSpecs(roots: List<Pair<File, String>>, guestRoot: File): List<String> =
        roots.mapNotNull { (host, guest) ->
            if (!host.isDirectory || !host.canRead()) return@mapNotNull null
            val target = File(guestRoot, guest.removePrefix("/"))
            if (target.isDirectory || target.mkdirs()) "${host.path}:$guest" else null
        }

    private fun guestFor(context: Context, root: File): String {
        val sm = context.getSystemService(StorageManager::class.java)
        val uuid = runCatching { sm?.getStorageVolume(root)?.uuid }.getOrNull()?.takeIf { it.isNotBlank() }?.replace(Regex("[^A-Za-z0-9-]"), "")
        val key = uuid ?: "%08x".format(CRC32().apply { update(root.absolutePath.toByteArray()) }.value)
        return "$GUEST_EXTERNAL/$key"
    }

    fun isInternal(context: Context, root: File): Boolean = root.absolutePath == internalRoot(context).absolutePath

    fun externalGuestPath(context: Context, host: File): String? {
        return externalGuestPath(host, externalRoots(context))
    }

    fun guestPath(context: Context, host: File): String = externalGuestPath(context, host) ?: host.path

    fun guestPathForXodus(context: Context, host: File): String =
        externalGuestPath(host, externalRootsForXodus(context)) ?: host.path

    internal fun externalGuestPath(host: File, roots: List<Pair<File, String>>): String? {
        val path = host.absolutePath
        for ((root, guest) in roots.sortedByDescending { it.first.absolutePath.length }) {
            val dir = root.absolutePath.trimEnd(File.separatorChar)
            if (path == dir) return guest
            val prefix = dir + File.separator
            if (path.startsWith(prefix)) return guest + "/" + path.removePrefix(prefix).replace(File.separatorChar, '/')
        }
        return null
    }

    fun labelFor(context: Context, folder: File): String {
        val path = folder.absolutePath
        for (t in targets(context)) if (path.startsWith(t.root.absolutePath + "/")) return t.label
        return context.getString(com.droiddeck.launcher.R.string.games_internal)
    }

    fun isRemovable(context: Context, folder: File): Boolean = !folder.absolutePath.startsWith(internalRoot(context).absolutePath + "/")

    fun storeDir(root: File, store: Store): File = File(root, store.shortLabel)

    fun gameFolders(context: Context): List<File> = roots(context).flatMap { root ->
        Store.entries.flatMap { store ->
            storeDir(root, store).listFiles { f -> f.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()
        }
    }

    fun folderFor(context: Context, store: Store, id: String, title: String, root: File = installRoot(context)): File =
        existingFolder(context, store, id, title) ?: File(storeDir(root, store), folderName(title, id))

    fun existingFolder(context: Context, store: Store, id: String, title: String): File? {
        val all = roots(context).flatMap { r -> storeDir(r, store).listFiles { f -> f.isDirectory }?.toList().orEmpty() }
        all.firstOrNull { folder -> StoreGameSidecar.read(folder)?.let { it.store == store && it.id == id } == true }?.let { return it }
        val name = folderName(title, id)
        return all.firstOrNull { it.name == name && StoreGameSidecar.read(it)?.isInstalled != true }
    }

    class Unfinished(val store: Store, val id: String?, val folder: File)

    fun isFinished(folder: File): Boolean = StoreGameSidecar.read(folder)?.isInstalled == true

    fun unfinished(context: Context): List<Unfinished> = roots(context).flatMap { root ->
        Store.entries.filter { it != Store.BATTLENET }.flatMap { store ->
            storeDir(root, store).listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { folder ->
                val sidecar = StoreGameSidecar.read(folder)
                when {
                    sidecar == null -> Unfinished(store, null, folder)
                    store == Store.GOG && sidecar.state == StoreGameSidecar.STATE_UPDATING -> null
                    !sidecar.isInstalled -> Unfinished(store, sidecar.id, folder)
                    else -> null
                }
            }
        }
    }

    fun scratchDir(context: Context, store: Store, id: String): File =
        File(File(File(context.cacheDir, "stores"), store.id), id.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    fun folderName(title: String, id: String): String {
        val cleaned = title.replace(Regex("[^A-Za-z0-9 ._'()\\-]"), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.')
        val cut = if (cleaned.length > 60) cleaned.take(60).trimEnd().trimEnd('.') else cleaned
        return cut.ifEmpty { id.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "game" } }
    }
}
