package com.droiddeck.launcher.session

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.FileUtils
import java.io.File

/**
 * Where a second Steam library can live on this device.
 *
 * An SD card is offered through the app's own folder on it ({@code Android/data/<pkg>/files/steam}):
 * the one place on a card a targetSdk-28 app may write without a permission prompt, and a real
 * filesystem path the session can bind. Any other folder comes from the File Manager's pick mode
 * and is checked for writing before it is accepted. Both are FUSE-backed on Android and stream
 * slowly (intro movies, big asset loads); internal stays the default and the dialog says so.
 */
object GameStorage {
    /** A library root: [label] as the menu lists it (a volume with its free space), [name] alone. */
    class Option(val label: String, val path: String, val name: String = label)

    /** Every removable volume, as its app folder, with the free space it has. */
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

    /**
     * Makes [path] a library root the session can bind - the folder and its steamapps/ - and proves
     * it writable. Returns why it cannot be used, or null when it can.
     */
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

    /** The root the session binds, from the setting: automatic = the first card in the phone. */
    fun effective(context: Context): Option? {
        val pref = SessionPrefs.gameStorage(context)
        return when {
            pref == SessionPrefs.GAME_STORAGE_OFF -> null
            pref.isEmpty() -> options(context).firstOrNull()?.let { Option(it.name, it.path) }
            else -> Option(SessionPrefs.gameStorageLabel(context), pref)
        }
    }

    /** The label the client shows for a chosen folder: its last name, or the volume's. */
    fun labelFor(context: Context, path: String): String =
        options(context).firstOrNull { it.path == path }?.name
            ?: File(path).name.ifEmpty { context.getString(R.string.gstore_folder) }
}
