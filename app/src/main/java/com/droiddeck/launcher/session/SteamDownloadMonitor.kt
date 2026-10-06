package com.droiddeck.launcher.session

import java.io.File
import java.io.RandomAccessFile

internal class SteamDownloadMonitor {
    private data class ManifestState(
        val bytesDownloaded: Long,
        val incomplete: Boolean,
    )

    private var previous = emptyMap<String, ManifestState>()
    private var lastProgressAt = 0L
    private var contentLogOffset = 0L
    private val activeContentDownloads = mutableSetOf<String>()
    private var lastContentActivityAt = 0L

    fun poll(runtimeRoot: File, now: Long = System.currentTimeMillis()): Boolean {
        val current = manifests(runtimeRoot).associate { it.path to read(it) }
        val progressed = current.any { (path, state) ->
            state.incomplete && state.bytesDownloaded > (previous[path]?.bytesDownloaded ?: state.bytesDownloaded)
        }
        if (progressed) lastProgressAt = now
        previous = current
        val manifestActive = progressed || lastProgressAt > 0L && now - lastProgressAt <= MANIFEST_PROGRESS_GRACE_MS &&
            current.values.any { it.incomplete }
        return manifestActive || readContentActivity(runtimeRoot, now)
    }

    fun reset() {
        previous = emptyMap()
        lastProgressAt = 0L
        contentLogOffset = 0L
        activeContentDownloads.clear()
        lastContentActivityAt = 0L
    }

    private fun readContentActivity(runtimeRoot: File, now: Long): Boolean {
        val log = File(runtimeRoot, "root/.local/share/Steam/logs/content_log.txt")
        if (!log.isFile) return false
        if (log.length() < contentLogOffset) {
            contentLogOffset = 0L
            activeContentDownloads.clear()
            lastContentActivityAt = 0L
        }
        runCatching {
            RandomAccessFile(log, "r").use { input ->
                input.seek(contentLogOffset)
                while (true) {
                    val line = input.readLine() ?: break
                    when {
                        "Client version:" in line -> {
                            activeContentDownloads.clear()
                            lastContentActivityAt = 0L
                        }
                        APP_UPDATE.find(line)?.let { match ->
                            val appId = match.groupValues[1]
                            val state = match.groupValues[2]
                            if ("Running Update" in state && "Stopping" !in state) {
                                activeContentDownloads += appId
                                lastContentActivityAt = now
                            } else {
                                activeContentDownloads -= appId
                            }
                            true
                        } == true -> Unit
                        CURRENT_RATE.find(line)?.groupValues?.get(1)?.toDoubleOrNull()?.let { it > 0.0 } == true ->
                            lastContentActivityAt = now
                        "Downloading " in line && " chunks for depot " in line ->
                            lastContentActivityAt = now
                    }
                }
                contentLogOffset = input.filePointer
            }
        }
        return activeContentDownloads.isNotEmpty() && lastContentActivityAt > 0L &&
            now - lastContentActivityAt <= CONTENT_ACTIVITY_GRACE_MS
    }

    private fun manifests(runtimeRoot: File): List<File> {
        val steamRoots = linkedSetOf(File(runtimeRoot, "root/.local/share/Steam"))
        val primary = steamRoots.first()
        val libraryFolders = File(primary, "steamapps/libraryfolders.vdf")
        runCatching {
            PATH.findAll(libraryFolders.readText()).forEach { match ->
                steamRoots += File(runtimeRoot, match.groupValues[1].removePrefix("/"))
            }
        }
        return steamRoots.flatMap { root ->
            File(root, "steamapps").listFiles { file ->
                file.isFile && file.name.startsWith("appmanifest_") && file.name.endsWith(".acf")
            }.orEmpty().toList()
        }
    }

    private fun read(file: File): ManifestState {
        val text = runCatching { file.readText() }.getOrDefault("")
        val flags = value(text, "StateFlags")?.toLongOrNull() ?: 0L
        val downloaded = value(text, "BytesDownloaded")?.toLongOrNull() ?: 0L
        val total = value(text, "BytesToDownload")?.toLongOrNull() ?: 0L
        return ManifestState(downloaded, flags != 4L && (total <= 0L || downloaded < total))
    }

    private fun value(text: String, key: String): String? =
        Regex("\"$key\"\\s+\"([^\"]*)\"").find(text)?.groupValues?.get(1)

    private companion object {
        const val MANIFEST_PROGRESS_GRACE_MS = 8_000L
        const val CONTENT_ACTIVITY_GRACE_MS = 45_000L
        val PATH = Regex("\"path\"\\s+\"([^\"]+)\"")
        val APP_UPDATE = Regex("""AppID (\d+) App update changed : (.*)""")
        val CURRENT_RATE = Regex("""Current download rate: ([\d.]+) Mbps""")
    }
}
