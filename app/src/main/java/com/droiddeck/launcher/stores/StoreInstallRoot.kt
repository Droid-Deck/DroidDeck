package com.droiddeck.launcher.stores

import android.content.Context
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.GameStorage
import java.io.File

/**
 * Where store games are installed: `<Games storage>/Games/<Store>/<title>`.
 *
 * The Games storage is the second Steam library when one is chosen (an SD card's app folder,
 * bound into the session at /mnt/droiddeck-sd), else the runtime's own tree, where the folder
 * sits at /root/Games/Stores - beside the user's added folders, which are bound under /root/Games
 * one by one. Both roots are always scanned, so a game installed on the card before the setting
 * changed stays in the library; new installs go to [installRoot].
 */
object StoreInstallRoot {
    private const val INTERNAL = "root/Games/Stores"
    private const val ON_LIBRARY = "Games"

    /** The folder a new install goes under (before the store's own folder). */
    fun installRoot(context: Context): File =
        GameStorage.effective(context)?.let { File(it.path, ON_LIBRARY) } ?: File(LinuxRuntime.rootDir(context), INTERNAL)

    /** Every root that may hold store games, the current install root first. */
    fun roots(context: Context): List<File> {
        val internal = File(LinuxRuntime.rootDir(context), INTERNAL)
        val library = GameStorage.effective(context)?.let { File(it.path, ON_LIBRARY) }
        return listOfNotNull(library, internal).distinctBy { it.absolutePath }
    }

    fun storeDir(root: File, store: Store): File = File(root, store.shortLabel)

    /** The game folders under every root, by store: what AddedGames scans in addition to the user's folders. */
    fun gameFolders(context: Context): List<File> = roots(context).flatMap { root ->
        Store.entries.flatMap { store ->
            storeDir(root, store).listFiles { f -> f.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()
        }
    }

    /** The folder a game installs into; an existing folder with this game's sidecar wins over a fresh name. */
    fun folderFor(context: Context, store: Store, id: String, title: String): File {
        for (root in roots(context)) {
            val dir = storeDir(root, store)
            dir.listFiles { f -> f.isDirectory }?.forEach { folder ->
                val sidecar = StoreGameSidecar.read(folder)
                if (sidecar != null && sidecar.store == store && sidecar.id == id) return folder
            }
        }
        return File(storeDir(installRoot(context), store), folderName(title, id))
    }

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
