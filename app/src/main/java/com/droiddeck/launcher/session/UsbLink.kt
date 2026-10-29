package com.droiddeck.launcher.session

import android.content.Context
import android.os.storage.StorageManager
import com.droiddeck.launcher.frontend.AddedGames
import java.io.File

object UsbLink {
    data class Link(val device: String, val mbps: Double, val name: String, val portMbps: Double)

    class SlowDrive(val key: String, val name: String, val mbps: Double, val portMbps: Double) {
        val portLimited get() = portMbps < FAST_MBPS
    }

    const val FAST_MBPS = 5000.0

    private val shown = mutableSetOf<String>()

    fun replugged() = synchronized(shown) { shown.clear() }

    fun markShown(drive: SlowDrive) = synchronized(shown) { shown += drive.key }

    fun slowLibrary(context: Context): SlowDrive? {
        if (SessionPrefs.slowUsbWarningOff(context)) return null
        val mounts = runCatching { File("/proc/self/mounts").readText() }.getOrNull() ?: return null
        val sm = context.getSystemService(StorageManager::class.java)
        for (path in libraries(context)) {
            val link = forPath(path, mounts) ?: continue
            if (link.mbps >= FAST_MBPS) continue
            val key = "${link.device}@${link.mbps}"
            if (synchronized(shown) { key in shown }) continue
            val volume = runCatching { sm?.getStorageVolume(File(path))?.getDescription(context) }.getOrNull()
            return SlowDrive(key, link.name.ifBlank { volume.orEmpty() }.ifBlank { File(path).name }, link.mbps, link.portMbps)
        }
        return null
    }

    private fun libraries(context: Context): List<String> {
        val pref = SessionPrefs.gameStorage(context)
        val storage = when (pref) {
            SessionPrefs.GAME_STORAGE_OFF -> null
            "" -> GameStorage.effective(context)?.path
            else -> pref
        }
        val folders = runCatching { AddedGames.roots(context).map { it.host.absolutePath } }.getOrDefault(emptyList())
        return (listOfNotNull(storage) + folders).filter { external(it) }.distinct()
    }

    internal fun external(path: String) = path.startsWith("/storage/") && !path.startsWith("/storage/emulated/") && !path.startsWith("/storage/self/")

    fun forPath(path: String, mounts: String, sys: File = File("/sys")): Link? {
        val device = mountDevice(rawPath(path), mounts) ?: return null
        val block = blockDir(device, sys) ?: return null
        val usb = usbDevice(block, sys) ?: return null
        val mbps = readSpeed(usb) ?: return null
        val name = listOf("manufacturer", "product").mapNotNull { read(File(usb, it)) }.joinToString(" ").trim()
        return Link(device, mbps, name, portSpeed(usb, sys) ?: mbps)
    }

    internal fun rawPath(path: String) = if (external(path)) "/mnt/media_rw/" + path.removePrefix("/storage/") else path

    internal fun mountDevice(path: String, mounts: String): String? {
        var best: Pair<String, String>? = null
        for (line in mounts.lineSequence()) {
            val parts = line.split(' ')
            if (parts.size < 2) continue
            val point = parts[1].replace("\\040", " ")
            if (path != point && !path.startsWith("${point.trimEnd('/')}/")) continue
            if (best == null || point.length > best.first.length) best = point to parts[0]
        }
        val source = best?.second ?: return null
        Regex(":(\\d+),(\\d+)$").find(source)?.let { return "${it.groupValues[1]}:${it.groupValues[2]}" }
        return if (source.startsWith("/dev/block/") || source.startsWith("/dev/sd")) "name:" + source.substringAfterLast('/') else null
    }

    internal fun blockDir(device: String, sys: File): File? {
        if (device.startsWith("name:")) return File(sys, "class/block/${device.removePrefix("name:")}").takeIf { it.exists() }?.canonicalFile
        File(sys, "dev/block/$device").takeIf { it.exists() }?.let { return it.canonicalFile }
        return File(sys, "class/block").listFiles()?.firstOrNull { read(File(it, "dev")) == device }?.canonicalFile
    }

    internal fun usbDevice(block: File, sys: File): File? {
        val top = sys.canonicalFile
        var dir: File? = block
        while (dir != null && dir != top) {
            if (File(dir, "speed").isFile && File(dir, "idVendor").isFile) return dir
            dir = dir.parentFile
        }
        return null
    }

    private fun portSpeed(usb: File, sys: File): Double? {
        val bus = read(File(usb, "busnum")) ?: return null
        val hub = File(sys, "bus/usb/devices/usb$bus").takeIf { it.exists() }?.canonicalFile ?: return null
        val controller = hub.parentFile ?: return null
        return controller.listFiles { f -> f.name.matches(Regex("usb\\d+")) }?.mapNotNull { readSpeed(it) }?.maxOrNull()
    }

    private fun readSpeed(dir: File) = read(File(dir, "speed"))?.toDoubleOrNull()

    private fun read(file: File) = runCatching { file.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    fun generation(mbps: Double) = when {
        mbps <= 12 -> "USB 1.1"
        mbps < FAST_MBPS -> "USB 2.0"
        mbps < 10000 -> "USB 3.2 Gen 1"
        mbps < 20000 -> "USB 3.2 Gen 2"
        else -> "USB 3.2 Gen 2x2"
    }

    fun megabytesPerSecond(mbps: Double) = (mbps / 8 * 0.7).toInt()
}
