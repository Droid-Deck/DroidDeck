package com.droiddeck.launcher.session

import java.io.File
import java.io.FileInputStream

internal class SteamDownloadMonitor {
    private data class ManifestState(
        val bytesDownloaded: Long,
        val incomplete: Boolean,
    )

    private var previous = emptyMap<String, ManifestState>()
    private var lastProgressAt = 0L
    private var contentLogOffset = 0L
    private var contentLog: File? = null
    private val activeContentUpdates = mutableSetOf<String>()
    private var lastContentActivityAt = 0L
    private var lastReceivedBytes = -1L
    private var lastReceivedAt = 0L

    fun poll(runtimeRoot: File, now: Long = System.currentTimeMillis(), receivedBytes: Long = -1L, client: String? = null,
             mounts: Map<String, File> = emptyMap()): Boolean {
        val current = manifests(runtimeRoot, mounts).associate { it.path to read(it) }
        val progressed = current.any { (path, state) ->
            state.incomplete && state.bytesDownloaded > (previous[path]?.bytesDownloaded ?: state.bytesDownloaded)
        }
        if (progressed) lastProgressAt = now
        previous = current
        val manifestActive = progressed || lastProgressAt > 0L && now - lastProgressAt <= MANIFEST_PROGRESS_GRACE_MS &&
            current.values.any { it.incomplete }
        if (receiving(receivedBytes, now)) lastContentActivityAt = now
        return manifestActive || readContentActivity(runtimeRoot, now, client)
    }

    private fun receiving(receivedBytes: Long, now: Long): Boolean {
        val previousBytes = lastReceivedBytes
        val previousAt = lastReceivedAt
        lastReceivedBytes = receivedBytes
        lastReceivedAt = now
        if (receivedBytes < 0L || previousBytes < 0L || receivedBytes < previousBytes || now <= previousAt) return false
        return (receivedBytes - previousBytes) * 1000L / (now - previousAt) >= MIN_RECEIVE_BYTES_PER_SEC
    }

    fun reset() {
        previous = emptyMap()
        lastProgressAt = 0L
        contentLogOffset = 0L
        contentLog = null
        activeContentUpdates.clear()
        lastContentActivityAt = 0L
        lastReceivedBytes = -1L
        lastReceivedAt = 0L
    }

    private fun readContentActivity(runtimeRoot: File, now: Long, client: String?): Boolean {
        val homes = client?.let { listOf(if (it == "x86_64") STEAM_HOMES[1] else STEAM_HOMES[0]) } ?: STEAM_HOMES
        val log = homes.map { File(runtimeRoot, "$it/logs/content_log.txt") }.maxByOrNull { it.lastModified() }!!
        if (!log.isFile) return false
        if (log != contentLog || log.length() < contentLogOffset) {
            contentLog = log
            contentLogOffset = 0L
            activeContentUpdates.clear()
            lastContentActivityAt = 0L
        }
        runCatching {
            FileInputStream(log).use { input ->
                input.channel.position(contentLogOffset)
                val reader = input.bufferedReader(Charsets.ISO_8859_1)
                while (true) {
                    val line = reader.readLine() ?: break
                    when {
                        "Client version:" in line -> {
                            activeContentUpdates.clear()
                            lastContentActivityAt = 0L
                        }
                        " update changed : " in line && CONTENT_UPDATE.find(line)?.let { match ->
                            val appId = match.groupValues[1]
                            val updateType = match.groupValues[2]
                            val state = match.groupValues[3]
                            val updateId = "$updateType:$appId"
                            if ("Running Update" in state && "Stopping" !in state) {
                                activeContentUpdates += updateId
                                lastContentActivityAt = now
                            } else {
                                activeContentUpdates -= updateId
                            }
                            true
                        } == true -> Unit
                        "Current download rate: " in line &&
                            CURRENT_RATE.find(line)?.groupValues?.get(1)?.toDoubleOrNull()?.let { it > 0.0 } == true ->
                            lastContentActivityAt = now
                        "Downloading " in line && " chunks for depot " in line ->
                            lastContentActivityAt = now
                    }
                    if (activeContentUpdates.isNotEmpty()) lastContentActivityAt = now
                }
                contentLogOffset = input.channel.position()
            }
        }
        return activeContentUpdates.isNotEmpty() && lastContentActivityAt > 0L &&
            now - lastContentActivityAt <= CONTENT_ACTIVITY_GRACE_MS
    }

    private fun manifests(runtimeRoot: File, mounts: Map<String, File>): List<File> {
        val steamRoots = linkedSetOf<File>()
        for (home in STEAM_HOMES) {
            val primary = File(runtimeRoot, home)
            steamRoots += primary
            val libraryFolders = File(primary, "steamapps/libraryfolders.vdf")
            runCatching {
                PATH.findAll(libraryFolders.readText()).forEach { match ->
                    steamRoots += hostPath(runtimeRoot, match.groupValues[1], mounts)
                }
            }
        }
        return steamRoots.flatMap { root ->
            File(root, "steamapps").listFiles { file ->
                file.isFile && file.name.startsWith("appmanifest_") && file.name.endsWith(".acf")
            }.orEmpty().toList()
        }
    }

    private fun hostPath(runtimeRoot: File, guest: String, mounts: Map<String, File>): File {
        val path = guest.trimEnd('/')
        for ((mount, host) in mounts) {
            if (path == mount) return host
            if (path.startsWith("$mount/")) return File(host, path.removePrefix("$mount/"))
        }
        return File(runtimeRoot, path.removePrefix("/"))
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

    companion object {
        fun clientInstallPending(runtimeRoot: File): Boolean {
            val home = File(runtimeRoot, "root")
            val steam = File(home, ".local/share/Steam")
            val stamped = File(steam, "package/droiddeck-installed").isFile ||
                File(steam, "package/bannerlator-installed").isFile
            return !(stamped && File(steam, "steamrtarm64/steam").isFile) || File(home, ".bl-steam-repair").exists()
        }

        private const val MANIFEST_PROGRESS_GRACE_MS = 8_000L
        private const val CONTENT_ACTIVITY_GRACE_MS = 90_000L
        private const val MIN_RECEIVE_BYTES_PER_SEC = 64L * 1024L
        private val PATH = Regex("\"path\"\\s+\"([^\"]+)\"")
        private val STEAM_HOMES = listOf("root/.local/share/Steam", "root/.droiddeck-x64/.local/share/Steam")
        private val CONTENT_UPDATE = Regex("""AppID (\d+) (App|Workshop|Shader) update changed : (.*)""")
        private val CURRENT_RATE = Regex("""Current download rate: ([\d.]+) Mbps""")
    }
}
