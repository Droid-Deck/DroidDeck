package com.droiddeck.launcher.session

import com.droiddeck.launcher.core.Hashes
import com.droiddeck.launcher.R
import android.content.Context
import android.os.StatFs
import android.util.Log
import com.droiddeck.launcher.core.Downloader
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Immediate download and installation of the optional ARM64 Proton builds. */
object ProtonExtras {
    private const val TAG = "ProtonExtras"
    private const val NEED_BYTES = 4L * 1024 * 1024 * 1024
    private const val RELEASES = "https://api.github.com/repos/%s/releases?per_page=15"
    /** How long a release check stands before the next one goes to GitHub. */
    const val CHECK_EVERY_MS = 6L * 60 * 60 * 1000
    @Volatile
    var installInProgress = false
        private set

    class Tool(val id: String, val name: String, val prefix: String, val repo: String, val assetPattern: Regex)

    /** [sha256] from GitHub's asset digest; [sha512] the URL of a checksum file published beside it. */
    internal data class Asset(val tag: String, val name: String, val url: String, val sha512: String?, val size: Long,
                             val sha256: String? = null)

    val tools = listOf(
        Tool("ge", "GE-Proton", "GE-Proton", "GloriousEggroll/proton-ge-custom", Regex("aarch64\\.tar\\.(gz|xz)$")),
        Tool("cachyos", "proton-cachyos", "proton-cachyos", "CachyOS/proton-cachyos", Regex("arm64\\.tar\\.(gz|xz)$")),
        Tool("droiddeck", "DroidDeck-Proton", "DroidDeck-Proton", "The412Banner/DroidDeck-Proton", Regex("aarch64\\.tar\\.(gz|xz)$")),
    )

    private fun home(context: Context) = File(LinuxRuntime.rootDir(context), "root")
    private fun requests(context: Context) = File(home(context), ".bl-proton-extra")
    private fun toolsDir(context: Context) = File(home(context), ".local/share/Steam/compatibilitytools.d")
    /** The release tag each build was installed from, by directory name. */
    private fun releasesFile(context: Context) = File(context.filesDir, "proton-extras.json")
    /** The newest release seen per source, and when it was looked up. */
    private fun latestFile(context: Context) = File(context.filesDir, "proton-latest.json")

    /** The installed build's directory name (e.g. GE-Proton11-7), the newest when there are several, or null. */
    fun installed(context: Context, tool: Tool): String? = installedIn(toolsDir(context), tool).lastOrNull()

    /** Every installed build's directory name, oldest first. */
    fun installedAll(context: Context, tool: Tool): List<String> = installedIn(toolsDir(context), tool)

    internal fun installedIn(dir: File, tool: Tool): List<String> =
        dir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(tool.prefix) && File(it, "toolmanifest.vdf").isFile }
            ?.map { it.name }
            ?.sortedWith { a, b -> compareVersions(a, b) }
            ?: emptyList()

    // Architecture names carry digits (aarch64, x86_64) that are not part of a version.
    private val ARCH = Regex("(?i)aarch64|arm64|x86_64|x86-64|amd64")
    private val NUMBER = Regex("\\d+")

    /** The numbers in a release tag or build folder, in order: GE-Proton11-10 is 11, 10. */
    internal fun versionKey(name: String): List<Long> =
        NUMBER.findAll(ARCH.replace(name, " ")).mapNotNull { it.value.toLongOrNull() }.toList()

    /** Orders by those numbers, so 11-10 is after 11-9; the name itself only breaks a tie. */
    internal fun compareVersions(a: String, b: String): Int {
        val x = versionKey(a)
        val y = versionKey(b)
        for (i in 0 until minOf(x.size, y.size)) if (x[i] != y[i]) return x[i].compareTo(y[i])
        return if (x.size != y.size) x.size.compareTo(y.size) else a.compareTo(b)
    }

    /** [latest] when it is newer than every installed release, else null. Nothing installed is not an update. */
    internal fun updateFor(installed: List<String>, latest: String?): String? {
        if (latest == null || installed.isEmpty()) return null
        val newest = installed.maxWithOrNull { a, b -> compareVersions(a, b) } ?: return null
        val x = versionKey(latest)
        val y = versionKey(newest)
        for (i in 0 until minOf(x.size, y.size)) if (x[i] != y[i]) return if (x[i] > y[i]) latest else null
        return if (x.size > y.size) latest else null
    }

    /** The release each installed build came from: the tag recorded at install, else its folder name. */
    fun installedTags(context: Context, tool: Tool): List<String> {
        val recorded = readJson(releasesFile(context))
        return installedAll(context, tool).map { recorded.optString(it).ifEmpty { it } }
    }

    /** A newer release than the installed one, from the last check (no network), or null. */
    fun update(context: Context, tool: Tool): String? = updateFor(installedTags(context, tool), cachedLatest(context, tool))

    fun cachedLatest(context: Context, tool: Tool): String? =
        readJson(latestFile(context)).optJSONObject(tool.id)?.optString("tag")?.ifEmpty { null }

    /** Its own lock, not the install's: a check must not wait behind a download. */
    private val checkLock = Any()
    /** A failed look-up (offline) is not repeated on every resume, only after this long. */
    private const val RETRY_MS = 15L * 60 * 1000
    private var lastAttempt = 0L

    /**
     * Looks up each source's newest release unless it was looked up within [CHECK_EVERY_MS] (or
     * [force]). Offline or rate limited, the last answer stands. True when anything was asked.
     */
    fun checkLatest(context: Context, force: Boolean = false, now: Long = System.currentTimeMillis()): Boolean {
        synchronized(checkLock) {
            val file = latestFile(context)
            val cache = readJson(file)
            val due = tools.filter { tool ->
                val at = cache.optJSONObject(tool.id)?.optLong("at", 0L) ?: 0L
                force || (now - at) !in 0 until CHECK_EVERY_MS
            }
            if (due.isEmpty() || !force && (now - lastAttempt) in 0 until RETRY_MS) return false
            lastAttempt = now
            for (tool in due) {
                val tag = findLatestAsset(tool)?.tag ?: continue
                cache.put(tool.id, JSONObject().put("tag", tag).put("at", now))
            }
            runCatching { file.writeText(cache.toString()) }
            return true
        }
    }

    private fun record(context: Context, tool: Tool, dir: String?, tag: String) {
        runCatching {
            val latest = readJson(latestFile(context))
            val known = latest.optJSONObject(tool.id)?.optString("tag")
            if (known.isNullOrEmpty() || compareVersions(tag, known) >= 0) {
                latest.put(tool.id, JSONObject().put("tag", tag).put("at", System.currentTimeMillis()))
                latestFile(context).writeText(latest.toString())
            }
            if (dir != null) {
                val releases = readJson(releasesFile(context))
                val present = toolsDir(context).list()?.toSet() ?: emptySet()
                releases.keys().asSequence().toList().filter { it !in present }.forEach { releases.remove(it) }
                releasesFile(context).writeText(releases.put(dir, tag).toString())
            }
        }.onFailure { Log.w(TAG, "record ${tool.id} $tag", it) }
    }

    private fun readJson(file: File): JSONObject =
        runCatching { JSONObject(FileUtils.readString(file) ?: "{}") }.getOrDefault(JSONObject())

    /** A request left by an earlier app version, still consumed by the session shim. */
    fun queued(context: Context, tool: Tool): Boolean =
        requestLines(context).any { it.trim() == tool.id || it.trim().startsWith(tool.id + " ") }

    fun unqueue(context: Context, tool: Tool) {
        val lines = requestLines(context).filterNot { it.trim() == tool.id || it.trim().startsWith(tool.id + " ") }
        val file = requests(context)
        if (lines.isEmpty()) file.delete()
        else file.writeText(lines.joinToString("\n") + "\n")
    }

    /** Deletes one installed build ([dir], else the newest); Steam discovers its absence on its next start. */
    fun remove(context: Context, tool: Tool, dir: String? = null) {
        val name = if (dir != null) dir.takeIf { it in installedAll(context, tool) } ?: return else installed(context, tool) ?: return
        File(toolsDir(context), name).deleteRecursively()
    }

    /** Deletes every build but the newest. */
    fun removeOlder(context: Context, tool: Tool) {
        installedAll(context, tool).dropLast(1).forEach { File(toolsDir(context), it).deleteRecursively() }
    }

    /** Downloads, verifies and registers the latest build without waiting for another session. */
    @Synchronized
    fun install(context: Context, tool: Tool, onProgress: (String, Int) -> Unit): String? {
        if (installInProgress) return context.getString(R.string.pextra_busy)
        installInProgress = true
        return try { installNow(context, tool, onProgress) } finally { installInProgress = false }
    }

    private fun installNow(context: Context, tool: Tool, onProgress: (String, Int) -> Unit): String? {
        if (SessionState.running) return context.getString(R.string.pextra_stop_session)
        if (!LinuxRuntime.isInstalled(context)) return context.getString(R.string.user_apps_runtime_required)

        val asset = findLatestAsset(tool) ?: return context.getString(R.string.pextra_no_release, tool.name)
        val downloads = File(context.filesDir, "proton-downloads").apply { mkdirs() }
        val archive = File(downloads, "${tool.id}-${asset.name}")
        if (asset.size > 0 && archive.length() > asset.size) archive.delete()
        val missing = (asset.size - archive.length()).coerceAtLeast(0L)
        if (StatFs(LinuxRuntime.rootDir(context).path).availableBytes < NEED_BYTES + missing) {
            return context.getString(R.string.pextra_no_space, tool.name)
        }

        val downloading = context.getString(R.string.pextra_downloading, tool.name, asset.tag)
        onProgress(downloading, 0)
        var downloaded = false
        for (attempt in 0 until 5) {
            downloaded = Downloader.downloadFile(asset.url, archive, true) { fraction ->
                onProgress(downloading, if (fraction < 0) -1 else (fraction * 100f).toInt().coerceIn(0, 100))
            }
            if (downloaded) break
            if (attempt < 4) {
                onProgress(context.getString(R.string.pextra_retrying, attempt + 2, 5), -1)
                try { Thread.sleep(3_000) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return context.getString(R.string.pextra_interrupted)
                }
            }
        }
        if (!downloaded) return context.getString(R.string.pextra_download_failed)
        if (asset.size > 0 && archive.length() != asset.size) {
            if (archive.length() > asset.size) {
                archive.delete()
                return context.getString(R.string.pextra_too_big)
            }
            return context.getString(R.string.pextra_incomplete)
        }

        // One checksum has to be found and has to match: GitHub's sha256 digest, else the
        // release's own sha512 file. A download neither vouches for is not installed.
        onProgress(context.getString(R.string.pextra_verifying), -1)
        val verified = when {
            asset.sha256 != null -> asset.sha256.equals(Hashes.sha256(archive), ignoreCase = true)
            asset.sha512 != null -> {
                val expected = Downloader.downloadString(asset.sha512)?.let { Regex("(?i)\\b[0-9a-f]{128}\\b").find(it)?.value }
                    ?: return context.getString(R.string.pextra_checksum_unreadable)
                expected.equals(Hashes.sha512(archive), ignoreCase = true)
            }
            else -> {
                archive.delete()
                return context.getString(R.string.pextra_no_checksum, tool.name, asset.tag)
            }
        }
        if (!verified) {
            archive.delete()
            return context.getString(R.string.pextra_checksum_mismatch)
        }

        if (SessionState.running) return context.getString(R.string.pextra_session_started)
        val installing = context.getString(R.string.pextra_installing, tool.name)
        onProgress(installing, -1)
        return try {
            val root = LinuxRuntime.rootDir(context)
            LinuxRuntime.writeAccounts(context)
            com.droiddeck.launcher.session.SessionFiles.stage(context, root)
            val runtimeDir = File(context.filesDir, ".proton-install-rt").apply { mkdirs() }
            val guest = listOf(
                "/usr/bin/env", "-i", "HOME=/root", "USER=root", "PATH=/usr/local/bin:/usr/bin:/bin",
                "LANG=C.UTF-8", "XDG_DATA_HOME=/root/.local/share",
                "/usr/local/bin/droiddeck-proton-extra", "/root/.local/share/Steam", archive.absolutePath,
            )
            val command = LinuxRuntime.command(context, null, runtimeDir, null, guest)
            val process = ProcessBuilder(command)
                .directory(root)
                .redirectErrorStream(true)
            val hostEnv = process.environment()
            hostEnv["PROOT_LOADER"] = LinuxRuntime.prootLoader(context).path
            hostEnv["PROOT_TMP_DIR"] = context.cacheDir.path
            LinuxRuntime.prootLibraryPath(context).takeIf { it.isNotEmpty() }?.let { hostEnv["LD_LIBRARY_PATH"] = it }
            val child = process.start()
            var placed: String? = null
            child.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    Log.i(TAG, line)
                    // "droiddeck-proton-extra: installed <folder>", the folder as the tarball names it.
                    line.substringAfter("droiddeck-proton-extra: installed ", "").takeIf { it.isNotEmpty() }?.let { placed = it }
                    if (line.contains("unpacking", ignoreCase = true) || line.contains("adopted", ignoreCase = true)) {
                        onProgress(installing, -1)
                    }
                }
            }
            val status = child.waitFor()
            if (status != 0) context.getString(R.string.pextra_install_failed, tool.name, status)
            else {
                archive.delete()
                unqueue(context, tool)
                record(context, tool, placed, asset.tag)
                if (EsyncPacks.enabled(context)) {
                    onProgress(context.getString(R.string.esync_fetching), -1)
                    try {
                        EsyncPacks.fetchWanted(context, root, onProgress)
                    } catch (t: Throwable) {
                        Log.w(TAG, "sync pack for ${tool.name}", t)
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "install ${tool.id}", e)
            e.message ?: context.getString(R.string.pextra_could_not_install, tool.name)
        }
    }

    private fun findLatestAsset(tool: Tool): Asset? {
        val releases = Downloader.downloadString(RELEASES.format(tool.repo)) ?: return null
        return try {
            pickAsset(tool, JSONArray(releases))
        } catch (e: Exception) {
            Log.w(TAG, "release metadata for ${tool.id}", e)
            null
        }
    }

    /** The newest full release in GitHub's list (newest first) that carries [tool]'s ARM64 archive. */
    internal fun pickAsset(tool: Tool, array: JSONArray): Asset? {
        for (i in 0 until array.length()) {
            val release = array.getJSONObject(i)
            if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) continue
            val assets = release.optJSONArray("assets") ?: continue
            val entries = (0 until assets.length()).map { assets.getJSONObject(it) }
            val archive = entries.sortedBy { it.optString("name") }.firstOrNull { tool.assetPattern.containsMatchIn(it.optString("name")) } ?: continue
            val name = archive.optString("name")
            val stem = name.substringBefore(".tar")
            val checksum = entries.firstOrNull {
                it.optString("name").startsWith(stem) && it.optString("name").contains("sha512", ignoreCase = true)
            }?.optString("browser_download_url")?.takeIf { it.startsWith("http") }
            return Asset(
                release.optString("tag_name"), name,
                archive.optString("browser_download_url").takeIf { it.startsWith("http") } ?: continue, checksum,
                archive.optLong("size", 0L),
                Hashes.githubSha256(archive.optString("digest")),
            )
        }
        return null
    }

    private fun requestLines(context: Context): List<String> =
        FileUtils.readString(requests(context))?.lines()?.filter { it.isNotBlank() } ?: emptyList()
}
