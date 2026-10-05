package com.droiddeck.launcher.runtime

import com.droiddeck.launcher.core.Hashes
import android.content.Context
import android.util.Log
import com.droiddeck.launcher.core.Downloader
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.core.ArchivePaths
import java.io.BufferedInputStream
import java.io.FileOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject

/** Optional Decky Loader installation for the ARM64 Linux guest. */
object DeckyManager {
    private const val TAG = "DeckyManager"
    const val REPOSITORY = "Droid-Deck/decky-loader"
    private const val RELEASES = "https://api.github.com/repos/$REPOSITORY/releases?per_page=30"
    private const val PATH = "root/homebrew/services/PluginLoader"
    private const val VERSION = "root/homebrew/services/.droiddeck-decky-version"
    private const val CEF_REMOTE_DEBUG_MARKER = "root/.local/share/Steam/.cef-enable-remote-debugging"

    data class Release(val tag: String, val prerelease: Boolean, val asset: String, val url: String, val digest: String?, val shaUrl: String?, val size: Long, val machine: Int)
    data class ReleaseChannels(val stable: List<Release>, val prerelease: List<Release>)

    fun installed(context: Context): String? {
        val loader = loader(context)
        if (!loader.isFile || !loader.canExecute()) return null
        return File(LinuxRuntime.rootDir(context), VERSION).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() } ?: "Installed"
    }

    fun loader(context: Context) = File(LinuxRuntime.rootDir(context), PATH)
    fun supervisorEnabled(context: Context) = File(LinuxRuntime.rootDir(context), "root/.droiddeck-decky-enabled").isFile

    /** Remove the legacy install-time debugger marker unless the user opted into Decky. */
    fun syncCefMarker(context: Context) {
        val marker = File(LinuxRuntime.rootDir(context), CEF_REMOTE_DEBUG_MARKER)
        if (!supervisorEnabled(context)) {
            marker.delete()
            return
        }
        runCatching {
            marker.parentFile?.mkdirs()
            marker.createNewFile()
        }
    }

    fun setSupervisorEnabled(context: Context, enabled: Boolean) {
        val marker = File(LinuxRuntime.rootDir(context), "root/.droiddeck-decky-enabled")
        val cefMarker = File(LinuxRuntime.rootDir(context), CEF_REMOTE_DEBUG_MARKER)
        if (enabled) {
            marker.parentFile?.mkdirs()
            marker.writeText("enabled\n")
            cefMarker.parentFile?.mkdirs()
            cefMarker.createNewFile()
        } else {
            marker.delete()
            cefMarker.delete()
        }
    }

    fun releases(context: Context, prerelease: Boolean): List<Release> {
        val channels = releaseChannels(context)
        return if (prerelease) channels.prerelease else channels.stable
    }

    fun releaseChannels(context: Context): ReleaseChannels {
        val body = Downloader.downloadString(RELEASES) ?: return ReleaseChannels(emptyList(), emptyList())
        return try {
            val array = JSONArray(body)
            val stable = mutableListOf<Release>()
            val prerelease = mutableListOf<Release>()
            for (i in 0 until array.length()) {
                val release = array.getJSONObject(i)
                if (release.optBoolean("draft", false)) continue
                val isPrerelease = release.optBoolean("prerelease", false)
                val assets = release.optJSONArray("assets") ?: continue
                val rows = (0 until assets.length()).map { assets.getJSONObject(it) }
                val arm = rows.firstOrNull { it.optString("name") == "PluginLoader-arm64" }
                val fexReady = LinuxFex.ready(context)
                val binary = arm ?: if (fexReady) rows.firstOrNull { it.optString("name") == "PluginLoader" } else null
                val name = binary?.optString("name") ?: continue
                val url = binary.optString("browser_download_url").takeIf { it.startsWith("https://") } ?: continue
                val checksum = rows.firstOrNull {
                    val n = it.optString("name")
                    (n == "$name.sha256" || n == "$name.sha256sum" || n == "$name.sha256.txt") && it.optString("browser_download_url").startsWith("https://")
                }?.optString("browser_download_url")?.takeIf { it.startsWith("https://") }
                val digest = binary.optString("digest").takeIf { Hashes.isGithubSha256(it) }
                if (digest == null && checksum == null) continue
                val size = binary.optLong("size", 0)
                if (size <= 0) continue
                val row = Release(release.optString("tag_name", "unknown"), isPrerelease, name, url, digest, checksum, size, if (name == "PluginLoader-arm64") 183 else 62)
                (if (isPrerelease) prerelease else stable).add(row)
            }
            ReleaseChannels(stable, prerelease)
        } catch (e: Exception) { Log.w(TAG, "release metadata", e); ReleaseChannels(emptyList(), emptyList()) }
    }

    /** Downloads to a sibling temp file and atomically renames only after size, digest and ELF checks. */
    fun install(context: Context, release: Release, progress: (String, Int) -> Unit): String? {
        if (SessionState.running) return "Stop the active session before installing Decky Loader"
        val target = loader(context)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "PluginLoader.download")
        val ok = Downloader.downloadFile(release.url, temp, false) { f -> progress("Downloading ${release.tag}", if (f < 0) -1 else (f * 100).toInt().coerceIn(0, 100)) }
        if (!ok) { temp.delete(); return "Decky Loader download failed" }
        if (temp.length() != release.size) { temp.delete(); return "Decky Loader size did not match the release metadata" }
        val expected = release.digest?.substringAfter(':') ?: release.shaUrl?.let { checksumUrl ->
            val checksumBody = Downloader.downloadString(checksumUrl) ?: run { temp.delete(); return "Could not download the release checksum" }
            Regex("(?i)\\b[0-9a-f]{64}\\b").find(checksumBody)?.value
        } ?: run { temp.delete(); return "Release checksum is missing" }
        if (!expected.matches(Regex("(?i)[0-9a-f]{64}"))) { temp.delete(); return "Release checksum is invalid" }
        if (!expected.equals(Hashes.sha256(temp), true)) { temp.delete(); return "Decky Loader checksum mismatch" }
        if (!validElf(temp, release.machine)) { temp.delete(); return "Release asset is not a compatible 64-bit Linux executable" }
        if (SessionState.running) { temp.delete(); return "A session started during the download; stop it before installing Decky Loader" }
        if (!temp.setExecutable(true, false)) { temp.delete(); return "Could not mark PluginLoader executable" }
        // Decky needs Steam's local CEF debugger only when its session supervisor is enabled.
        val cefMarker = File(LinuxRuntime.rootDir(context), CEF_REMOTE_DEBUG_MARKER)
        val cefReady = !supervisorEnabled(context) || cefMarker.isFile || runCatching {
            cefMarker.parentFile?.mkdirs()
            cefMarker.createNewFile() || cefMarker.isFile
        }.getOrDefault(false)
        if (!cefReady) { temp.delete(); return "Could not enable Steam CEF remote debugging" }
        val staged = File(target.parentFile, "PluginLoader.new")
        staged.delete()
        if (!temp.renameTo(staged)) { temp.delete(); return "Could not stage PluginLoader" }
        val old = File(target.parentFile, "PluginLoader.old")
        old.delete()
        if (target.exists() && !target.renameTo(old)) { staged.delete(); return "Could not preserve the existing PluginLoader" }
        if (!staged.renameTo(target)) {
            if (old.exists()) old.renameTo(target)
            staged.delete()
            return "Could not activate the new PluginLoader"
        }
        old.delete()
        File(target.parentFile, ".droiddeck-decky-version").writeText(release.tag + "\n")
        File(target.parentFile, ".droiddeck-decky-arch").writeText(if (release.machine == 183) "arm64\n" else "x86_64\n")
        if (!supervisorEnabled(context)) cefMarker.delete()
        return null
    }

    /** Imports a Decky distribution ZIP into homebrew/plugins for the next Steam session. */
    fun installPluginZip(context: Context, archive: File, progress: (String, Int) -> Unit): String? {
        if (SessionState.running) return "Stop the active session before installing a plugin"
        if (installed(context) == null) return "Install Decky Loader before importing a plugin"
        if (!archive.isFile || !archive.canRead()) return "Could not read the selected ZIP file"

        val plugins = File(LinuxRuntime.rootDir(context), "root/homebrew/plugins")
        if (!plugins.exists() && !plugins.mkdirs()) return "Could not create the Decky plugins directory"
        val staging = File(plugins, ".droiddeck-import-${UUID.randomUUID()}")
        val backupSuffix = ".droiddeck-backup-${UUID.randomUUID()}"
        try {
            staging.mkdirs()
            progress("Extracting plugin ZIP", -1)
            extractPluginZip(archive, staging)
            val roots = staging.listFiles()?.filter { it.isDirectory } ?: emptyList()
            if (roots.size != 1 || staging.listFiles()?.size != 1) return "Plugin ZIP must contain one top-level plugin folder"
            val plugin = roots.single()
            if (!File(plugin, "plugin.json").isFile || !File(plugin, "package.json").isFile || !File(plugin, "dist/index.js").isFile) {
                return "Plugin ZIP is missing plugin.json, package.json, or dist/index.js"
            }
            val name = JSONObject(File(plugin, "plugin.json").readText()).getString("name")
            require(name.isNotBlank()) { "Plugin name is missing" }
            val metadata = JSONObject(File(plugin, "package.json").readText())
            downloadRemoteBinaries(metadata, plugin, progress)
            if (SessionState.running) return "A session started during installation; stop it before installing a plugin"

            DeckyPluginInstall.activate(plugin, plugins, name,
                File(plugins.parentFile, "settings/loader.json"), backupSuffix)
            progress("Plugin installed · restart Steam to load it", 100)
            return null
        } catch (e: Exception) {
            Log.w(TAG, "plugin ZIP import", e)
            return e.message?.takeIf { it.isNotBlank() } ?: "Plugin ZIP installation failed"
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun extractPluginZip(archive: File, destination: File) {
        var totalBytes = 0L
        var entries = 0
        val seen = HashSet<String>()
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                require(entries <= 10_000) { "Plugin ZIP contains too many entries" }
                val relative = entry.name.replace('\\', '/')
                require(relative.isNotBlank() && !relative.startsWith('/')) { "Plugin ZIP contains an invalid path" }
                require(seen.add(relative)) { "Plugin ZIP contains duplicate paths" }
                val output = ArchivePaths.inside(destination, relative)
                    ?: throw IllegalArgumentException("Plugin ZIP contains an unsafe path")
                if (entry.isDirectory) {
                    require(output.mkdirs() || output.isDirectory) { "Could not create plugin folder" }
                } else {
                    require(output.parentFile?.let { it.mkdirs() || it.isDirectory } == true) { "Could not create plugin folder" }
                    FileOutputStream(output).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            totalBytes += read
                            require(totalBytes <= MAX_PLUGIN_ZIP_BYTES) { "Plugin ZIP expands beyond the 1 GiB limit" }
                            out.write(buffer, 0, read)
                        }
                    }
                    // Decky distributions place plugin executables under bin/. Java's ZIP reader
                    // does not expose Unix mode bits, so restore execution on those payload files.
                    if (relative.split('/').dropLast(1).any { it == "bin" } && !output.setExecutable(true, false)) {
                        throw IllegalStateException("Could not mark bundled plugin binary executable")
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun downloadRemoteBinaries(metadata: JSONObject, plugin: File, progress: (String, Int) -> Unit) {
        val binaries = metadata.optJSONArray("remote_binary") ?: return
        val bin = File(plugin, "bin")
        for (i in 0 until binaries.length()) {
            val binary = binaries.optJSONObject(i) ?: throw IllegalArgumentException("Invalid remote_binary entry")
            val name = binary.optString("name")
            val url = binary.optString("url")
            val hash = binary.optString("sha256hash")
            require(name.isNotBlank() && name != "." && name != ".." && !name.contains('/') && !name.contains('\\')) { "Invalid remote binary name" }
            require(url.startsWith("https://")) { "Remote binary URL must use HTTPS" }
            require(hash.matches(Regex("(?i)[0-9a-f]{64}"))) { "Remote binary $name has an invalid SHA-256 hash" }
            require(bin.mkdirs() || bin.isDirectory) { "Could not create plugin bin directory" }
            val temp = File(bin, ".$name.download")
            progress("Downloading plugin binary · $name", -1)
            if (!Downloader.downloadFile(url, temp, false) { fraction ->
                    progress("Downloading plugin binary · $name", if (fraction < 0) -1 else (fraction * 100).toInt().coerceIn(0, 100))
                }) {
                temp.delete()
                throw IllegalStateException("Could not download plugin binary $name")
            }
            if (!hash.equals(Hashes.sha256(temp), ignoreCase = true)) {
                temp.delete()
                throw IllegalStateException("Plugin binary checksum mismatch · $name")
            }
            if (!temp.setExecutable(true, false)) {
                temp.delete()
                throw IllegalStateException("Could not mark plugin binary executable · $name")
            }
            val target = File(bin, name)
            if (target.exists() && !target.delete()) {
                temp.delete()
                throw IllegalStateException("Could not replace plugin binary · $name")
            }
            if (!temp.renameTo(target)) {
                temp.delete()
                throw IllegalStateException("Could not install plugin binary · $name")
            }
        }
    }

    fun uninstall(context: Context, wipeData: Boolean) {
        val root = LinuxRuntime.rootDir(context)
        setSupervisorEnabled(context, false)
        File(root, PATH).delete()
        File(root, VERSION).delete()
        File(root, "root/homebrew/services/.droiddeck-decky-arch").delete()
        File(root, CEF_REMOTE_DEBUG_MARKER).delete()
        if (wipeData) File(root, "root/homebrew").deleteRecursively()
    }

    private fun validElf(file: File, machine: Int): Boolean = runCatching {
        file.inputStream().use { input ->
            val h = ByteArray(20)
            if (input.read(h) != h.size) return false
            h[0] == 0x7f.toByte() && h[1] == 'E'.code.toByte() && h[2] == 'L'.code.toByte() && h[3] == 'F'.code.toByte() &&
                h[4] == 2.toByte() && h[5] == 1.toByte() && (h[18].toInt() and 255) + ((h[19].toInt() and 255) shl 8) == machine
        }
    }.getOrDefault(false)

    private const val MAX_PLUGIN_ZIP_BYTES = 1024L * 1024L * 1024L
}
