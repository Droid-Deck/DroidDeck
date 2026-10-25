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

    /** USB drives vold mounted at /mnt/media_rw but hides from apps; SD cards are visible. */
    private fun hiddenUsb(context: Context): List<File> {
        val visible = visibleVolumes(context)
        return mountPoints()
            .filter { it.startsWith("/mnt/media_rw/") && it.count { c -> c == '/' } == 3 && it.substringAfterLast('/') !in visible }
            .distinct().map(::File)
    }

    /** This process's mount points, from /proc/self/mountinfo; empty if unreadable. */
    fun mountPoints(): List<String> = try {
        File("/proc/self/mountinfo").readLines().mapNotNull { it.split(' ').getOrNull(4) }
    } catch (e: Exception) {
        emptyList()
    }

    /** UUIDs of volumes the app already has a /storage/<uuid> path for (SD cards), offered through that. */
    fun visibleVolumes(context: Context): Set<String> =
        context.getExternalFilesDirs(null).orEmpty().filterNotNull()
            .mapNotNull { it.path.removePrefix("/storage/").substringBefore('/').takeIf { u -> u != "emulated" } }.toSet()

    enum class UsbAccess { PERMISSION, RESTART }

    /** What a hidden drive still needs: All files access, then a new process for the external_storage group. */
    fun usbAccess(context: Context): UsbAccess? {
        if (android.os.Build.VERSION.SDK_INT < 30) return null
        val drives = hiddenUsb(context)
        return when {
            !android.os.Environment.isExternalStorageManager() -> UsbAccess.PERMISSION.takeIf { drives.any { !it.canWrite() } }
            // Readable but not writable is a read-only drive, which a restart does not fix.
            else -> UsbAccess.RESTART.takeIf { drives.any { !it.canRead() } }
        }
    }

    private fun onUsb(context: Context, dir: File): Boolean =
        dir.startsWith(UsbRootMount.path(context)) || hiddenUsb(context).any { dir.startsWith(it) }

    /** The USB drive by whichever route is on; the first one only. */
    fun usbDrive(context: Context): File? =
        if (SessionPrefs.usbRootEnabled(context)) UsbRootMount.path(context).takeIf { UsbRootMount.mounted(context) }
        else hiddenUsb(context).firstOrNull { it.canWrite() }

    fun usbLabel(context: Context): String =
        SessionPrefs.usbRootLabel(context).takeIf { it.isNotEmpty() && SessionPrefs.usbRootEnabled(context) } ?: context.getString(R.string.usb_root_volume)

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
            // The USB drive is a library of its own: a folder on it would be listed twice, or written to the bare mount point.
            onUsb(context, File(pref)) -> null
            else -> Option(SessionPrefs.gameStorageLabel(context), pref)
        }
    }

    const val X64_CARD_DIR = "x86-64"
    const val X64_CARD_GUEST = "/mnt/droiddeck-sd/$X64_CARD_DIR"

    fun x64CardLibrary(context: Context): File? = effective(context)?.let { File(it.path, X64_CARD_DIR) }

    class Library(val host: File, val guest: String, val label: String)

    fun gamesFolderLibraries(context: Context): List<Library> {
        if (SessionPrefs.gameStorage(context).isNotEmpty()) return emptyList()
        val card = effective(context)?.let { canonical(File(it.path)) }
        val sm = context.getSystemService(StorageManager::class.java)
        return AddedGames.roots(context).mapNotNull { root ->
            val host = canonical(root.host)
            if (!hasInstalledGames(root.host) || onUsb(context, root.host)) return@mapNotNull null
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
