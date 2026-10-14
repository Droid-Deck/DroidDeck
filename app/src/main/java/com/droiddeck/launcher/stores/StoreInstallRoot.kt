package com.droiddeck.launcher.stores

import android.content.Context
import android.os.StatFs
import android.os.storage.StorageManager
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.GameStorage
import java.io.File
import java.util.zip.CRC32

object StoreInstallRoot {
    private const val INTERNAL = "root/Games/Stores"
    private const val ON_VOLUME = "Games"
    const val GUEST_EXTERNAL = "/mnt/droiddeck-stores"

    class Target(val root: File, val label: String, val removable: Boolean) {
        val freeBytes: Long get() = try { StatFs((if (root.isDirectory) root else root.parentFile ?: root).path).availableBytes } catch (e: Exception) { 0L }
    }

    fun internalRoot(context: Context): File = File(LinuxRuntime.rootDir(context), INTERNAL)

    fun targets(context: Context): List<Target> {
        val internal = Target(internalRoot(context), context.getString(com.droiddeck.launcher.R.string.games_internal), removable = false)
        val cards = GameStorage.options(context).map { Target(File(it.path, ON_VOLUME), it.name, removable = true) }
        return listOf(internal) + cards
    }

    fun installRoot(context: Context): File = internalRoot(context)

    fun roots(context: Context): List<File> {
        val cards = GameStorage.options(context).map { File(it.path, ON_VOLUME) }
        val library = GameStorage.effective(context)?.let { File(it.path, ON_VOLUME) }
        return (listOf(internalRoot(context)) + cards + listOfNotNull(library)).distinctBy { it.absolutePath }
    }

    fun externalRoots(context: Context): List<Pair<File, String>> {
        val library = GameStorage.effective(context)?.path
        return roots(context)
            .filter { !isInternal(context, it) && (library == null || !it.absolutePath.startsWith("$library/")) }
            .map { it to guestFor(context, it) }
    }

    private fun guestFor(context: Context, root: File): String {
        val sm = context.getSystemService(StorageManager::class.java)
        val uuid = runCatching { sm?.getStorageVolume(root)?.uuid }.getOrNull()?.takeIf { it.isNotBlank() }?.replace(Regex("[^A-Za-z0-9-]"), "")
        val key = uuid ?: "%08x".format(CRC32().apply { update(root.absolutePath.toByteArray()) }.value)
        return "$GUEST_EXTERNAL/$key"
    }

    fun isInternal(context: Context, root: File): Boolean = root.absolutePath == internalRoot(context).absolutePath

    fun externalGuestPath(context: Context, host: File): String? {
        val path = host.absolutePath
        for ((root, guest) in externalRoots(context)) {
            val dir = root.absolutePath
            if (path == dir) return guest
            if (path.startsWith("$dir/")) return guest + "/" + path.removePrefix("$dir/")
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
        Store.entries.flatMap { store ->
            storeDir(root, store).listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { folder ->
                val sidecar = StoreGameSidecar.read(folder)
                when {
                    sidecar == null -> Unfinished(store, null, folder)
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
