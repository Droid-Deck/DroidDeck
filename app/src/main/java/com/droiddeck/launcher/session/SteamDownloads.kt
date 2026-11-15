package com.droiddeck.launcher.session

import java.io.File
import java.io.RandomAccessFile

data class SteamDownload(
    val appId: String,
    val name: String,
    val done: Long,
    val total: Long,
    val shader: Boolean = false,
    val paused: Boolean = false,
)

object SteamDownloads {
    fun read(runtimeRoot: File, mounts: Map<String, File> = emptyMap(), client: String? = null): List<SteamDownload> {
        val names = HashMap<String, String>()
        val apps = SteamDownloadMonitor.manifests(runtimeRoot, mounts).mapNotNull { file ->
            val text = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
            val id = SteamDownloadMonitor.value(text, "appid") ?: file.name.removePrefix("appmanifest_").removeSuffix(".acf")
            val name = SteamDownloadMonitor.value(text, "name") ?: id
            names[id] = name
            fromManifest(id, name, text)
        }.let(::onePerApp)
        val homes = client?.let { listOf(if (it == "x86_64") SteamDownloadMonitor.STEAM_HOMES[1] else SteamDownloadMonitor.STEAM_HOMES[0]) }
            ?: SteamDownloadMonitor.STEAM_HOMES
        val log = homes.map { File(runtimeRoot, "$it/logs/content_log.txt") }.filter { it.isFile }.maxByOrNull { it.lastModified() }
        val shaders = log?.let { runningShaderUpdates(tail(it)) }.orEmpty()
            .map { SteamDownload(it, names[it] ?: it, 0L, 0L, shader = true) }
        return apps + shaders
    }

    internal fun onePerApp(downloads: List<SteamDownload>): List<SteamDownload> =
        downloads.groupBy { it.appId }.values.map { same -> same.maxBy { it.done } }

    internal fun fromManifest(id: String, name: String, text: String): SteamDownload? {
        val flags = SteamDownloadMonitor.value(text, "StateFlags")?.toLongOrNull() ?: 0L
        if (flags == FULLY_INSTALLED) return null
        val done = SteamDownloadMonitor.value(text, "BytesDownloaded")?.toLongOrNull() ?: 0L
        val total = SteamDownloadMonitor.value(text, "BytesToDownload")?.toLongOrNull() ?: 0L
        if (total > 0L && done >= total) return null
        if ((flags and UPDATE_REQUIRED) == 0L && total <= 0L) return null
        return SteamDownload(id, name, done, total, paused = (flags and UPDATE_PAUSED) != 0L)
    }

    internal fun runningShaderUpdates(lines: Sequence<String>): List<String> {
        val state = LinkedHashMap<String, Boolean>()
        for (line in lines) {
            if ("Client version:" in line) state.clear()
            val match = SteamDownloadMonitor.CONTENT_UPDATE.find(line) ?: continue
            if (match.groupValues[2] != "Shader") continue
            val running = match.groupValues[3].let { "Running Update" in it && "Stopping" !in it }
            state[match.groupValues[1]] = running
        }
        return state.filterValues { it }.keys.toList()
    }

    private fun tail(file: File): Sequence<String> = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val start = (raf.length() - TAIL_BYTES).coerceAtLeast(0L)
            raf.seek(start)
            val bytes = ByteArray((raf.length() - start).toInt())
            raf.readFully(bytes)
            String(bytes, Charsets.ISO_8859_1).lineSequence().drop(if (start > 0L) 1 else 0).toList().asSequence()
        }
    }.getOrDefault(emptySequence())

    private const val FULLY_INSTALLED = 4L
    private const val UPDATE_REQUIRED = 2L
    private const val UPDATE_PAUSED = 512L
    private const val TAIL_BYTES = 256L * 1024L
}
