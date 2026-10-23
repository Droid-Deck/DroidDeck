package com.droiddeck.launcher.files

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import com.droiddeck.launcher.R
import java.io.File

data class StorageRoot(
    val label: String,
    val dir: File,
    val removable: Boolean,
    val readable: Boolean,
)

object StorageRoots {

    private const val EMULATED_PREFIX = "/storage/emulated"
    private const val INTERNAL_PATH = "/storage/emulated/0"

    private const val PRIMARY_KEY = "primary"

    private class Volume(val key: String) {
        var label: String? = null
        var removable: Boolean = key != PRIMARY_KEY
        val candidates = LinkedHashSet<File>()
        val seeds = LinkedHashSet<File>()
    }

    private val EXTERNAL_MOUNT_TYPES = setOf(
        "vfat", "exfat", "texfat", "ntfs", "ntfs3", "fuseblk", "fuse", "sdcardfs",
    )

    internal fun parseExternalMounts(mounts: String): List<File> {
        val out = ArrayList<File>()
        for (line in mounts.lineSequence()) {
            val parts = line.split(' ')
            if (parts.size < 3) continue
            if (parts[2].lowercase() !in EXTERNAL_MOUNT_TYPES) continue
            val target = parts[1].replace("\\040", " ")
            val name = when {
                target.startsWith("/storage/") -> target.removePrefix("/storage/")
                target.startsWith("/mnt/media_rw/") -> target.removePrefix("/mnt/media_rw/")
                else -> continue
            }
            if ('/' in name || name.isEmpty() || name == "emulated" || name == "self") continue
            out.add(File(target))
        }
        return out.distinct()
    }

    fun externalMounts(): List<File> =
        runCatching { parseExternalMounts(File("/proc/mounts").readText()) }.getOrDefault(emptyList())

    fun list(context: Context): List<StorageRoot> {
        val volumes = LinkedHashMap<String, Volume>()

        fun volumeOf(key: String) = volumes.getOrPut(key) { Volume(key) }

        volumeOf(PRIMARY_KEY).apply {
            label = context.getString(R.string.fm_drive_internal)
            removable = false
            candidates += File(INTERNAL_PATH)
        }

        val appDirs = appSpecificDirs(context)
        val storageManager = context.getSystemService(StorageManager::class.java)

        storageManager?.storageVolumes.orEmpty()
            .filter { present(it.state) }
            .forEach { volume ->
                val uuid = volume.uuid?.takeIf { it.isNotBlank() }
                val dirName = volume.directory?.name?.takeIf { it.isNotBlank() }
                val entry = volumeOf(if (volume.isPrimary) PRIMARY_KEY else uuid ?: dirName ?: return@forEach)
                entry.removable = !volume.isPrimary && volume.isRemovable
                if (entry.label == null) entry.label = labelFor(context, volume, uuid ?: dirName)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    volume.directory?.let { entry.candidates += it }
                }
                val name = uuid ?: dirName
                if (name != null) {
                    entry.candidates += File("/storage/$name")
                    entry.candidates += File("/mnt/media_rw/$name")
                }
                appDirs.filter { belongsTo(storageManager!!, it, volume) }.forEach { entry.seeds += it }
            }

        File("/storage").listFiles().orEmpty().forEach { child ->
            if (!child.isDirectory || child.name == "self" || child.name == "emulated") return@forEach
            volumeOf(child.name).candidates += child
        }
        File("/mnt/media_rw").listFiles().orEmpty().forEach { child ->
            if (child.isDirectory) volumeOf(child.name).candidates += child
        }
        // USB OTG drives do not always appear as storage volumes or under /storage for
        // third-party apps, but their kernel mounts are still visible in /proc/mounts.
        for (mount in externalMounts()) {
            val entry = volumeOf(mount.name)
            if (entry.label == null) entry.label = mount.name
            entry.candidates += mount
        }

        appDirs.forEach { dir ->
            val root = volumeRootOf(dir) ?: return@forEach
            val entry = volumeOf(if (isEmulated(root)) PRIMARY_KEY else root.name)
            entry.candidates += root
            entry.seeds += dir
        }

        val roots = volumes.values.map { it.toRoot(context) }
        val internal = roots.filter { !it.removable }
        val removable = roots.filter { it.removable }.sortedBy { it.label }
        return disambiguate(internal + removable)
    }

    private fun Volume.toRoot(context: Context): StorageRoot {
        val dir = candidates.firstOrNull(::canBrowse)
            ?: candidates.firstNotNullOfOrNull { root -> seeds.firstNotNullOfOrNull { deepestReadable(root, it) } }
            ?: seeds.firstOrNull(::canBrowse)
            ?: candidates.firstOrNull()
            ?: File("/storage/$key")

        return StorageRoot(
            label = label ?: defaultLabel(context),
            dir = dir,
            removable = removable,
            readable = canBrowse(dir),
        )
    }

    private fun Volume.defaultLabel(context: Context): String = if (removable) context.getString(R.string.fm_drive_sd) else key

    private fun disambiguate(roots: List<StorageRoot>): List<StorageRoot> {
        val counts = roots.groupingBy { it.label }.eachCount()
        return roots.map { root ->
            if (counts.getValue(root.label) == 1) root else root.copy(label = "${root.label} (${root.dir.name})")
        }
    }

    private fun deepestReadable(root: File, seed: File): File? {
        var current: File? = seed.absoluteFile
        var best: File? = null
        while (current != null && isWithin(current, root)) {
            if (canBrowse(current)) best = current
            current = current.parentFile
        }
        return best
    }

    private fun volumeRootOf(appDir: File): File? =
        generateSequence(appDir.absoluteFile) { it.parentFile }
            .firstOrNull { it.name.equals("Android", ignoreCase = true) }
            ?.parentFile

    private fun appSpecificDirs(context: Context): List<File> =
        context.getExternalFilesDirs(null)
            .orEmpty()
            .filterNotNull()
            .filter { isMounted(Environment.getExternalStorageState(it)) }

    private fun belongsTo(storageManager: StorageManager, dir: File, volume: StorageVolume): Boolean {
        val owner = storageManager.getStorageVolume(dir) ?: return false
        if (owner.isPrimary != volume.isPrimary) return false
        val ownerUuid = owner.uuid
        val volumeUuid = volume.uuid
        return if (!ownerUuid.isNullOrBlank() || !volumeUuid.isNullOrBlank()) {
            ownerUuid == volumeUuid
        } else {
            true
        }
    }

    private fun labelFor(context: Context, volume: StorageVolume, uuid: String?): String {
        if (volume.isPrimary) return context.getString(R.string.fm_drive_internal)
        val description = volume.getDescription(context)?.trim()
        if (!description.isNullOrBlank() && !description.equals("android", ignoreCase = true)) return description
        if (volume.isRemovable) return context.getString(R.string.fm_drive_sd)
        return uuid
            ?: context.getString(R.string.fm_drive_storage)
    }

    private fun isEmulated(dir: File): Boolean = dir.absolutePath.startsWith(EMULATED_PREFIX)

    private fun present(state: String?): Boolean =
        isMounted(state) || state == Environment.MEDIA_UNMOUNTED || state == Environment.MEDIA_CHECKING

    private fun isMounted(state: String?): Boolean =
        state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY

    private fun canBrowse(dir: File?): Boolean =
        dir != null && dir.isDirectory && dir.canRead() && dir.listFiles() != null

    private fun isWithin(child: File, ancestor: File): Boolean {
        val c = canonicalPath(child)
        val a = canonicalPath(ancestor)
        return c == a || c.startsWith("$a/")
    }

    private fun canonicalPath(file: File): String =
        try {
            file.canonicalPath
        } catch (e: Exception) {
            file.absolutePath
        }.trimEnd('/')
}
