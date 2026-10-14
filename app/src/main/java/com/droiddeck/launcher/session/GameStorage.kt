package com.droiddeck.launcher.session

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.frontend.AddedGames
import java.io.File

object GameStorage {
    class Option(val label: String, val path: String, val name: String = label)

    fun options(context: Context): List<Option> {
        val sm = context.getSystemService(StorageManager::class.java)
        return context.getExternalFilesDirs(null).orEmpty().filterNotNull().mapNotNull { dir ->
            val removable = try { Environment.isExternalStorageRemovable(dir) } catch (e: Exception) { false }
            if (!removable) return@mapNotNull null
            val volume = try { sm?.getStorageVolume(dir) } catch (e: Exception) { null }
            val name = volume?.getDescription(context)?.takeIf { it.isNotBlank() && !it.equals("android", true) } ?: context.getString(R.string.gstore_sd_card)
            Option(context.getString(R.string.gstore_volume_free, name, free(dir)), File(dir, "steam").absolutePath, name)
        }
    }

    fun free(dir: File): String = try {
        FileUtils.sizeToString(StatFs(dir.path).availableBytes)
    } catch (e: Exception) {
        "?"
    }

    fun prepare(context: Context, path: String): String? {
        val root = File(path)
        val steamapps = File(root, "steamapps")
        if (!steamapps.isDirectory && !steamapps.mkdirs()) return context.getString(R.string.gstore_cannot_create, path)
        val probe = File(steamapps, ".writable")
        return try {
            if (!probe.createNewFile() && !probe.isFile) context.getString(R.string.gstore_cannot_write, path) else { probe.delete(); null }
        } catch (e: Exception) {
            context.getString(R.string.gstore_cannot_write_detail, path, e.message)
        }
    }

    fun effective(context: Context): Option? {
        val pref = SessionPrefs.gameStorage(context)
        return when {
            pref == SessionPrefs.GAME_STORAGE_OFF -> null
            pref.isEmpty() -> options(context).firstOrNull()?.let { Option(it.name, it.path) }
            else -> Option(SessionPrefs.gameStorageLabel(context), pref)
        }
    }

    class Library(val host: File, val guest: String, val label: String)

    fun gamesFolderLibraries(context: Context): List<Library> {
        if (SessionPrefs.gameStorage(context).isNotEmpty()) return emptyList()
        val card = effective(context)?.let { canonical(File(it.path)) }
        val sm = context.getSystemService(StorageManager::class.java)
        return AddedGames.roots(context).mapNotNull { root ->
            val host = canonical(root.host)
            if (!hasInstalledGames(root.host)) return@mapNotNull null
            if ('"' in root.guest || '\\' in root.guest || !root.host.isDirectory || !root.host.canWrite()) return@mapNotNull null
            if (card != null && (host == card || host.startsWith("$card/") || card.startsWith("$host/"))) return@mapNotNull null
            val volume = try { sm?.getStorageVolume(root.host)?.getDescription(context) } catch (e: Exception) { null }
            val name = root.host.name.ifEmpty { context.getString(R.string.gstore_folder) }
            val label = if (volume.isNullOrBlank()) name else context.getString(R.string.gstore_folder_on_volume, name, volume)
            Library(root.host, root.guest, label.replace('"', ' ').replace('\\', ' '))
        }
    }

    internal fun hasInstalledGames(root: File): Boolean =
        File(root, "steamapps").listFiles { f -> f.isFile && f.name.startsWith("appmanifest_") && f.name.endsWith(".acf") }?.isNotEmpty() == true

    private fun canonical(file: File): String = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

    fun labelFor(context: Context, path: String): String =
        options(context).firstOrNull { it.path == path }?.name
            ?: File(path).name.ifEmpty { context.getString(R.string.gstore_folder) }
}
