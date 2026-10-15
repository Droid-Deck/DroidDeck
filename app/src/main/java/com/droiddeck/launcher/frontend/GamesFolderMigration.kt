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
