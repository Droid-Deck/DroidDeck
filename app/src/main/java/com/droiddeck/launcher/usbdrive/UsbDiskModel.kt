package com.droiddeck.launcher.usbdrive

import org.json.JSONArray
import org.json.JSONObject

data class UsbPartition(
    val index: Int,
    val start: Long,
    val blocks: Long,
    val fs: String,
    val label: String,
) {
    val isNtfs: Boolean get() = fs == FS_NTFS
}

data class UsbDiskInfo(
    val vendor: String,
    val product: String,
    val blockSize: Int,
    val blocks: Long,
    val partitions: List<UsbPartition>,
) {
    val sizeBytes: Long get() = blocks * blockSize

    fun bytesOf(partition: UsbPartition): Long = partition.blocks * blockSize

    val name: String get() = listOf(vendor, product).filter { it.isNotBlank() }.joinToString(" ")
}

data class NtfsVolumeInfo(
    val label: String,
    val size: Long,
    val free: Long,
    val dirty: Boolean,
)

data class NtfsEntry(
    val name: String,
    val isDirectory: Boolean,
    val hidden: Boolean,
    val size: Long,
    val modified: Long,
)

internal const val FS_NTFS = "ntfs"
internal const val FS_EXFAT = "exfat"
internal const val FS_FAT = "fat"

internal object UsbDriveJson {
    fun disk(bytes: ByteArray): UsbDiskInfo {
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        val partitions = json.getJSONArray("partitions")
        return UsbDiskInfo(
            vendor = json.optString("vendor"),
            product = json.optString("product"),
            blockSize = json.getInt("blockSize"),
            blocks = json.getLong("blocks"),
            partitions = (0 until partitions.length()).map { i ->
                val p = partitions.getJSONObject(i)
                UsbPartition(
                    index = p.getInt("index"),
                    start = p.getLong("start"),
                    blocks = p.getLong("blocks"),
                    fs = p.optString("fs"),
                    label = p.optString("label"),
                )
            },
        )
    }

    fun volume(bytes: ByteArray): NtfsVolumeInfo {
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        return NtfsVolumeInfo(
            label = json.optString("label"),
            size = json.optLong("size"),
            free = json.optLong("free"),
            dirty = json.optBoolean("dirty"),
        )
    }

    fun entries(bytes: ByteArray): List<NtfsEntry> {
        val array = JSONArray(String(bytes, Charsets.UTF_8))
        return (0 until array.length()).map { i ->
            val e = array.getJSONObject(i)
            NtfsEntry(
                name = e.getString("name"),
                isDirectory = e.getBoolean("dir"),
                hidden = e.optBoolean("hidden"),
                size = e.optLong("size"),
                modified = e.optLong("mtime"),
            )
        }
    }
}

internal object NtfsPaths {
    const val ROOT = "/"

    fun child(parent: String, name: String): String = if (parent == ROOT) "/$name" else "$parent/$name"

    fun parent(path: String): String? {
        if (path == ROOT) return null
        val cut = path.lastIndexOf('/')
        return if (cut <= 0) ROOT else path.substring(0, cut)
    }

    fun name(path: String): String = path.substring(path.lastIndexOf('/') + 1)

    fun segments(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }
}

internal val ntfsEntryOrder: Comparator<NtfsEntry> =
    compareBy<NtfsEntry> { if (it.isDirectory) 0 else 1 }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
