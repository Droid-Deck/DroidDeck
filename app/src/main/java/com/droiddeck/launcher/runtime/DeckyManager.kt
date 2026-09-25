package com.droiddeck.launcher.runtime

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.core.Downloader
import com.droiddeck.launcher.session.SessionState
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import org.json.JSONArray

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
                val guest = LinuxRuntime.rootDir(context)
                val fexReady = File(guest, "usr/share/guestos/fex-mesa").isDirectory && File(guest, "usr/bin/FEX").canExecute()
                val binary = arm ?: if (fexReady) rows.firstOrNull { it.optString("name") == "PluginLoader" } else null
                val name = binary?.optString("name") ?: continue
                val url = binary.optString("browser_download_url").takeIf { it.startsWith("https://") } ?: continue
                val checksum = rows.firstOrNull {
                    val n = it.optString("name")
                    (n == "$name.sha256" || n == "$name.sha256sum" || n == "$name.sha256.txt") && it.optString("browser_download_url").startsWith("https://")
                }?.optString("browser_download_url")?.takeIf { it.startsWith("https://") }
                val digest = binary.optString("digest").takeIf { it.matches(Regex("(?i)sha256:[0-9a-f]{64}")) }
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
        if (!expected.equals(sha256(temp), true)) { temp.delete(); return "Decky Loader checksum mismatch" }
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

    fun uninstall(context: Context, wipeData: Boolean) {
        val root = LinuxRuntime.rootDir(context)
        setSupervisorEnabled(context, false)
        File(root, PATH).delete()
        File(root, VERSION).delete()
        File(root, "root/homebrew/services/.droiddeck-decky-arch").delete()
        File(root, CEF_REMOTE_DEBUG_MARKER).delete()
        if (wipeData) File(root, "root/homebrew").deleteRecursively()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun validElf(file: File, machine: Int): Boolean = runCatching {
        file.inputStream().use { input ->
            val h = ByteArray(20)
            if (input.read(h) != h.size) return false
            h[0] == 0x7f.toByte() && h[1] == 'E'.code.toByte() && h[2] == 'L'.code.toByte() && h[3] == 'F'.code.toByte() &&
                h[4] == 2.toByte() && h[5] == 1.toByte() && (h[18].toInt() and 255) + ((h[19].toInt() and 255) shl 8) == machine
        }
    }.getOrDefault(false)
}
