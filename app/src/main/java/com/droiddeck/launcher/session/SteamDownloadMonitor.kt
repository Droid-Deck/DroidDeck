package com.droiddeck.launcher.session

import java.io.File

internal class SteamDownloadMonitor {
    private data class ManifestState(
        val bytesDownloaded: Long,
        val incomplete: Boolean,
    )

    private var previous = emptyMap<String, ManifestState>()
    private var lastProgressAt = 0L

    fun poll(runtimeRoot: File, now: Long = System.currentTimeMillis()): Boolean {
        val current = manifests(runtimeRoot).associate { it.path to read(it) }
        val progressed = current.any { (path, state) ->
            state.incomplete && state.bytesDownloaded > (previous[path]?.bytesDownloaded ?: state.bytesDownloaded)
        }
        if (progressed) lastProgressAt = now
        previous = current
        return progressed || lastProgressAt > 0L && now - lastProgressAt <= PROGRESS_GRACE_MS &&
            current.values.any { it.incomplete }
    }

    fun reset() {
        previous = emptyMap()
        lastProgressAt = 0L
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
        const val PROGRESS_GRACE_MS = 8_000L
        val PATH = Regex("\"path\"\\s+\"([^\"]+)\"")
    }
}
