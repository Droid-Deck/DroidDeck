package com.droiddeck.launcher.frontend

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.StoreInstallRoot
import java.io.File
import java.util.zip.CRC32

object GamesFolderMigration {
    private const val TAG = "GamesFolderMigration"

    @Synchronized
    fun run(context: Context) {
        val dirs = SessionPrefs.addedGamesDirs(context)
        if (dirs.isEmpty()) return
        val roots = legacyRoots(SessionPrefs.gameFolderBinds(context).map { it.first } + dirs)
            .filter { root -> root.host.path in dirs }
        SessionPrefs.setGameFolderBinds(context, SessionPrefs.gameFolderBinds(context) + roots.map { it.host.path to it.guest })
        val removed = SessionPrefs.removedAddedGames(context).toSet()
        val entries = AddedExes.list(context).map { it.folder }.toMutableSet()
        val storeRoots = StoreInstallRoot.roots(context).map { it.absolutePath }
        for (root in roots) {
            var n = 0
            val steamInstalls = AddedGames.steamInstallDirs(root.host)
            for (top in root.host.listFiles { f -> f.isDirectory }?.sortedBy { it.name.lowercase() }.orEmpty()) {
                if (AddedGames.isSteamLibraryFolder(top.name) || top.name.lowercase() in steamInstalls) continue
                if (top.absolutePath in storeRoots || StoreGameSidecar.read(top) != null) continue
                if (top.path in entries) continue
                val topPick = SessionPrefs.addedGameExe(context, top.path).takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.isFile }
                val groups = AddedGames.groupGames(top)
                val played = topPick ?: groups?.let { AddedGames.rank(top).exes.firstOrNull() }
                val owner = played?.let { exe -> top.listFiles { f -> f.isDirectory }?.firstOrNull { exe.path.startsWith(it.path + File.separator) } }
                val folders = when {
                    groups == null -> listOf(top)
                    topPick == null || owner in groups -> groups
                    owner == null || owner.name.startsWith(".") || AddedGames.isSteamLibraryFolder(owner.name) -> listOf(top)
                    else -> (groups + owner).sortedBy { it.name.lowercase() }
                }
                for (folder in folders) {
                    if (folder.absolutePath in storeRoots || StoreGameSidecar.read(folder) != null) continue
                    if (folder.path in entries) continue
                    val picked = SessionPrefs.addedGameExe(context, folder.path).takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.isFile }
                        ?: topPick?.takeIf { it.path.startsWith(folder.path + File.separator) }
                    val ranked = AddedGames.rank(folder).exes.firstOrNull()
                    val exe = picked ?: played?.takeIf { folder == owner } ?: ranked ?: continue
                    AddedExes.put(context, AddedExes.Entry(folder.path, exe.path, picked = picked != null || exe != ranked))
                    entries.add(folder.path)
                    if (folder != top && folder == owner) SessionPrefs.inheritAddedGame(context, top.path, folder.path)
                    if (folder != top && top.path in removed) SessionPrefs.setAddedGameRemoved(context, folder.path, true)
                    else if (folder.path !in removed) n++
                }
            }
            Log.i(TAG, "games: migrated ${root.host} → $n games")
        }
        SessionPrefs.setAddedGamesDirs(context, emptyList())
        SessionPrefs.setGamesFoldersMigrated(context)
    }

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
