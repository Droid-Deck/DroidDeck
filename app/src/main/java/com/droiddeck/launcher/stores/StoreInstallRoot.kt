package com.droiddeck.launcher.stores

import android.content.Context
import android.os.StatFs
import android.os.storage.StorageManager
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.GameStorage
import java.io.File
import java.util.zip.CRC32

/**
 * Where store games are installed: `<root>/<Store>/<title>`.
 *
 * The default root is the app's own internal storage - the runtime's tree, where the folder sits at
 * /root/Games/Stores and needs no bind - whatever the session's Game storage setting says. An SD
 * card the app may write (its app folder, `GameStorage.options`) is offered as a second root at
 * Install time and never chosen silently. Every root is scanned, so a game stays in the library
 * wherever it went. A card that is the Steam library is already bound (`/mnt/droiddeck-sd`); any
 * other card's root is bound at a fixed place of its own (`/mnt/droiddeck-stores/<volume>`), so a
 * shortcut's path - and with it its appid - stays the same from one session to the next.
 */
object StoreInstallRoot {
    private const val INTERNAL = "root/Games/Stores"
    private const val ON_VOLUME = "Games"
    const val GUEST_EXTERNAL = "/mnt/droiddeck-stores"

    /** One place a game can be installed: the folder, its name for the dialog, and whether it is a card. */
    class Target(val root: File, val label: String, val removable: Boolean) {
        val freeBytes: Long get() = try { StatFs((if (root.isDirectory) root else root.parentFile ?: root).path).availableBytes } catch (e: Exception) { 0L }
    }

    /** The internal root: always there, always the default. */
    fun internalRoot(context: Context): File = File(LinuxRuntime.rootDir(context), INTERNAL)

    /** The roots a game can go to right now: internal first, then every writable card. */
    fun targets(context: Context): List<Target> {
        val internal = Target(internalRoot(context), context.getString(com.droiddeck.launcher.R.string.games_internal), removable = false)
        val cards = GameStorage.options(context).map { Target(File(it.path, ON_VOLUME), it.name, removable = true) }
        return listOf(internal) + cards
    }

    /** The default root, when nothing asks: internal storage. */
    fun installRoot(context: Context): File = internalRoot(context)

    /** Every root that may hold store games: the internal one, every card's, and the Steam library's (older installs). */
    fun roots(context: Context): List<File> {
        val cards = GameStorage.options(context).map { File(it.path, ON_VOLUME) }
        val library = GameStorage.effective(context)?.let { File(it.path, ON_VOLUME) }
        return (listOf(internalRoot(context)) + cards + listOfNotNull(library)).distinctBy { it.absolutePath }
    }

    /**
     * The card roots that need a bind of their own, with where the session sees them. A root under
     * the Steam library is left out: the library's bind already covers it, and the games there keep
     * the guest path (and appid) they were registered with.
     */
    fun externalRoots(context: Context): List<Pair<File, String>> {
        val library = GameStorage.effective(context)?.path
        return roots(context)
            .filter { !isInternal(context, it) && (library == null || !it.absolutePath.startsWith("$library/")) }
            .map { it to guestFor(context, it) }
    }

    /** A card root's guest path, keyed on the volume (its UUID, else a hash of the path), so it is the same across sessions. */
    private fun guestFor(context: Context, root: File): String {
        val sm = context.getSystemService(StorageManager::class.java)
        val uuid = runCatching { sm?.getStorageVolume(root)?.uuid }.getOrNull()?.takeIf { it.isNotBlank() }?.replace(Regex("[^A-Za-z0-9-]"), "")
        val key = uuid ?: "%08x".format(CRC32().apply { update(root.absolutePath.toByteArray()) }.value)
        return "$GUEST_EXTERNAL/$key"
    }

    fun isInternal(context: Context, root: File): Boolean = root.absolutePath == internalRoot(context).absolutePath

    /** Where a host path under a card root appears inside the session, or null when it is not under one. */
    fun externalGuestPath(context: Context, host: File): String? {
        val path = host.absolutePath
        for ((root, guest) in externalRoots(context)) {
            val dir = root.absolutePath
            if (path == dir) return guest
            if (path.startsWith("$dir/")) return guest + "/" + path.removePrefix("$dir/")
        }
        return null
    }

    /** The target a game folder sits under, for the page and the Downloads row; internal when unknown. */
    fun labelFor(context: Context, folder: File): String {
        val path = folder.absolutePath
        for (t in targets(context)) if (path.startsWith(t.root.absolutePath + "/")) return t.label
        return context.getString(com.droiddeck.launcher.R.string.games_internal)
    }

    fun isRemovable(context: Context, folder: File): Boolean = !folder.absolutePath.startsWith(internalRoot(context).absolutePath + "/")

    fun storeDir(root: File, store: Store): File = File(root, store.shortLabel)

    /** The game folders under every root, by store: what AddedGames scans in addition to the user's folders. */
    fun gameFolders(context: Context): List<File> = roots(context).flatMap { root ->
        Store.entries.flatMap { store ->
            storeDir(root, store).listFiles { f -> f.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()
        }
    }

    /**
     * The folder a game installs into under [root]. An existing folder wins over a fresh one, so a
     * repair, an update or a resume lands on what is already there: one with this game's sidecar
     * anywhere, else one under any root with this game's folder name and no finished sidecar (an
     * install an earlier build left without one, or that stopped before writing it).
     */
    fun folderFor(context: Context, store: Store, id: String, title: String, root: File = installRoot(context)): File =
        existingFolder(context, store, id, title) ?: File(storeDir(root, store), folderName(title, id))

    /** The folder an earlier install of this game left, finished or not; null when there is none. */
    fun existingFolder(context: Context, store: Store, id: String, title: String): File? {
        val all = roots(context).flatMap { r -> storeDir(r, store).listFiles { f -> f.isDirectory }?.toList().orEmpty() }
        all.firstOrNull { folder -> StoreGameSidecar.read(folder)?.let { it.store == store && it.id == id } == true }?.let { return it }
        val name = folderName(title, id)
        return all.firstOrNull { it.name == name && StoreGameSidecar.read(it)?.isInstalled != true }
    }

    /** An unfinished install: [id] from its `installing` sidecar, null when it has none (matched by folder name then). */
    class Unfinished(val store: Store, val id: String?, val folder: File)

    /**
     * A folder under a store root is a game only once its sidecar says installed. Without one it is
     * an install that never finished (stopped, killed, or from a build that wrote the sidecar last):
     * not a Custom game, and not listed in Steam.
     */
    fun isFinished(folder: File): Boolean = StoreGameSidecar.read(folder)?.isInstalled == true

    /** Every unfinished install under every root, for the Stores page's "Resume install". */
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

    /** A scratch folder in the app's cache for an install's in-flight pieces, when the install itself is on a card. */
    fun scratchDir(context: Context, store: Store, id: String): File =
        File(File(File(context.cacheDir, "stores"), store.id), id.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    /**
     * A folder name from a store title: letters, digits and a few safe marks, single spaces, at most
     * 60 characters; the store id when nothing printable is left. The name is the shortcut's folder
     * for ever, so it is chosen once at install and read back from the sidecar afterwards.
     */
    fun folderName(title: String, id: String): String {
        val cleaned = title.replace(Regex("[^A-Za-z0-9 ._'()\\-]"), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.')
        val cut = if (cleaned.length > 60) cleaned.take(60).trimEnd().trimEnd('.') else cleaned
        return cut.ifEmpty { id.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "game" } }
    }
}
