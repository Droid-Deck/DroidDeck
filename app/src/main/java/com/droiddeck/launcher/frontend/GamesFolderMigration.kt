package com.droiddeck.launcher.frontend

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.StoreInstallRoot
import java.io.File
import java.util.zip.CRC32

/**
 * Games folders (the roots earlier builds let the user add in Steam settings) become what the
 * Games tab's "Add all games in this folder" makes: one entry per game ([AddedExes]). Everything
 * a game had is keyed by its own folder and stays as it was - the chosen exe, the shortcut appid,
 * the name, Start in, launch options, art choices, a removal. The roots stay bound into the
 * session at the same guest paths ([SessionPrefs.gameFolderBinds]), so every exe keeps its path
 * inside the session and every Steam shortcut its target; they are no longer Steam libraries.
 * Runs whenever saved roots remain (so twice is the same as once) and clears them after.
 */
object GamesFolderMigration {
    private const val TAG = "GamesFolderMigration"

    @Synchronized
    fun run(context: Context) {
        val dirs = SessionPrefs.addedGamesDirs(context)
        if (dirs.isEmpty()) return
        val roots = legacyRoots(SessionPrefs.gameFolderBinds(context).map { it.first } + dirs)
            .filter { root -> root.host.path in dirs }
        // The binds first: a game's guest path (and with it the shortcut's appid) depends on them.
        SessionPrefs.setGameFolderBinds(context, SessionPrefs.gameFolderBinds(context) + roots.map { it.host.path to it.guest })
        val removed = SessionPrefs.removedAddedGames(context).toSet()
        val entries = AddedExes.list(context).map { it.folder }.toMutableSet()
        val storeRoots = StoreInstallRoot.roots(context).map { it.absolutePath }
        for (root in roots) {
            var n = 0
            val steamInstalls = AddedGames.steamInstallDirs(root.host)
            for (folder in root.host.listFiles { f -> f.isDirectory }?.sortedBy { it.name.lowercase() }.orEmpty()) {
                if (folder.name.equals("steamapps", ignoreCase = true) || folder.name.lowercase() in steamInstalls) continue
                if (folder.absolutePath in storeRoots || StoreGameSidecar.read(folder) != null) continue
                if (folder.path in entries) continue
                val picked = SessionPrefs.addedGameExe(context, folder.path).takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.isFile }
                val exe = picked ?: AddedGames.rank(folder).exes.firstOrNull() ?: continue
                AddedExes.put(context, AddedExes.Entry(folder.path, exe.path, picked = picked != null))
                entries.add(folder.path)
                if (folder.path !in removed) n++
            }
            Log.i(TAG, "games: migrated ${root.host} → $n games")
        }
        SessionPrefs.setAddedGamesDirs(context, emptyList())
        SessionPrefs.setGamesFoldersMigrated(context)
    }

    /**
     * The guest paths earlier builds gave Games folders: /root/Games/<folder name>, with a short
     * hash of the host path when two share a name. Kept exactly, so nothing moves in the session.
     */
    internal fun legacyRoots(paths: List<String>): List<AddedGames.Root> {
        val hosts = paths.distinct().map { File(it) }
        val names = hosts.groupingBy { it.name.lowercase() }.eachCount()
        return hosts.map { host ->
            val name = host.name.ifEmpty { "games" }
            val guestName = if ((names[name.lowercase()] ?: 0) > 1) name + "-" + "%08x".format(CRC32().apply { update(host.path.toByteArray()) }.value).take(4) else name
            AddedGames.Root(host, "${AddedGames.GUEST_DIR}/$guestName")
        }
    }
}
