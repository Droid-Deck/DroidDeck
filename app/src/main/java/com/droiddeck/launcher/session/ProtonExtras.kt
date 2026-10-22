package com.droiddeck.launcher.session

import com.droiddeck.launcher.core.Hashes
import com.droiddeck.launcher.R
import android.content.Context
import android.os.StatFs
import android.util.Log
import com.droiddeck.launcher.core.Downloader
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.artifact.GitHubArtifacts
import com.droiddeck.launcher.artifact.RepositorySources
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

object ProtonExtras {
    private const val TAG = "ProtonExtras"
    private const val NEED_BYTES = 4L * 1024 * 1024 * 1024
    const val SOURCE_SCOPE = "proton"
    const val CHECK_EVERY_MS = 6L * 60 * 60 * 1000
    private const val SOURCE_CHECKS = "_sources"
    @Volatile
    var installInProgress = false
        private set

    class Tool(
        val id: String, val name: String, val prefix: String, val repo: String, val assetPattern: Regex,
        val sourceId: String = "", val baseId: String = id,
    )

    internal data class Asset(val tag: String, val name: String, val url: String, val sha512: String?, val size: Long,
                             val sha256: String? = null, val sourceId: String = "", val repository: String = "",
                             val repositoryId: Long = 0, val releaseId: Long = 0, val assetId: Long = 0,
                             val toolName: String = "", val toolPrefix: String = "")

    val tools = listOf(
        Tool("ge", "GE-Proton", "GE-Proton", "gloriouseggroll/proton-ge-custom", Regex("aarch64\\.tar\\.(gz|xz)$")),
        Tool("cachyos", "proton-cachyos", "proton-cachyos", "cachyos/proton-cachyos", Regex("arm64\\.tar\\.(gz|xz)$")),
        Tool("droiddeck", "DroidDeck-Proton", "DroidDeck-Proton", "droid-deck/droiddeck-proton", Regex("aarch64\\.tar\\.(gz|xz)$")),
    )

    @JvmStatic fun sourceBuiltIns(): List<String> = tools.map { it.repo }

    @JvmStatic fun sources(context: Context): List<RepositorySources.Source> =
        RepositorySources.list(context, SOURCE_SCOPE, sourceBuiltIns())

    @JvmStatic fun saveSources(context: Context, rows: List<RepositorySources.Source>): List<RepositorySources.Source> =
        RepositorySources.save(context, SOURCE_SCOPE, sourceBuiltIns(), rows)

    @JvmStatic fun availableTools(context: Context): List<Tool> {
        val visible = RepositorySources.visible(context, SOURCE_SCOPE, sourceBuiltIns()).associateBy { it.id }
        val known = tools.mapNotNull { tool ->
            visible.values.firstOrNull { it.repository == tool.repo }?.let { source ->
                Tool(tool.id, tool.name, tool.prefix, tool.repo, tool.assetPattern, source.id, tool.id)
            }
        }
        val cache = readJson(latestFile(context))
        val custom = cache.keys().asSequence().mapNotNull { id ->
            if (id == SOURCE_CHECKS) return@mapNotNull null
            val row = cache.optJSONObject(id) ?: return@mapNotNull null
            val source = visible[row.optString("sourceId")] ?: return@mapNotNull null
            if (source.repository in sourceBuiltIns()) return@mapNotNull null
            val baseId = row.optString("baseId")
            val base = tools.firstOrNull { it.id == baseId }
            if (base == null && baseId != "manifest") return@mapNotNull null
            Tool(id, row.optString("name", "${source.repository} · ${base?.name ?: "Proton"}"),
                row.optString("prefix", base?.prefix ?: ""), source.repository,
                base?.assetPattern ?: Regex("a^"), source.id, baseId)
        }.toList()
        return (known + custom).distinctBy { it.id }
    }

    @JvmStatic fun displayTools(context: Context): List<Tool> {
        val available = availableTools(context)
        val installedBuiltIns = tools.map { tool ->
            val source = RepositorySources.source(tool.repo)
            Tool(tool.id, tool.name, tool.prefix, source.repository, tool.assetPattern, source.id, tool.id)
        }.filter { installedAll(context, it).isNotEmpty() }
        val receipts = toolsDir(context).listFiles()?.mapNotNull { directory ->
            if (!directory.isDirectory) return@mapNotNull null
            val json = runCatching { JSONObject(FileUtils.readString(File(directory, ".droiddeck-source.json")) ?: return@runCatching null) }
                .getOrNull() ?: return@mapNotNull null
            val sourceId = json.optString("sourceId")
            val id = json.optString("toolId")
            val prefix = json.optString("toolPrefix")
            val repo = json.optString("repository")
            if (sourceId.isEmpty() || id.isEmpty() || prefix.isEmpty() || repo.isEmpty()) return@mapNotNull null
            val base = tools.firstOrNull { it.id == json.optString("baseId") }
            Tool(id, json.optString("toolName", repo), prefix, repo, base?.assetPattern ?: Regex("a^"),
                sourceId, json.optString("baseId", "manifest"))
        }.orEmpty()
        return (available + installedBuiltIns + receipts).distinctBy { it.id }
    }

    private fun home(context: Context) = File(LinuxRuntime.rootDir(context), "root")
    private fun requests(context: Context) = File(home(context), ".bl-proton-extra")
    private fun toolsDir(context: Context) = File(home(context), ".local/share/Steam/compatibilitytools.d")
    private fun releasesFile(context: Context) = File(context.filesDir, "proton-extras.json")
    private fun latestFile(context: Context) = File(context.filesDir, "proton-latest.json")

    fun installed(context: Context, tool: Tool): String? = installedIn(toolsDir(context), tool).lastOrNull()

    fun installedAll(context: Context, tool: Tool): List<String> = installedIn(toolsDir(context), tool)

    internal fun installedIn(dir: File, tool: Tool): List<String> =
        dir.listFiles()
            ?.filter { directory ->
                if (!directory.isDirectory || !File(directory, "toolmanifest.vdf").isFile) {
                    return@filter false
                }
                val receipt = runCatching { JSONObject(FileUtils.readString(File(directory, ".droiddeck-source.json")) ?: "{}") }
                    .getOrDefault(JSONObject())
                val recorded = receipt.optString("sourceId")
                if (recorded.isNotEmpty()) {
                    recorded == tool.sourceId && receipt.optString("toolId") == tool.id
                } else {
                    directory.name.startsWith(tool.prefix) && tool.repo in sourceBuiltIns()
                }
            }
            ?.map { it.name }
            ?.sortedWith { a, b -> compareVersions(a, b) }
            ?: emptyList()

    private val ARCH = Regex("(?i)aarch64|arm64|x86_64|x86-64|amd64")
    private val SOURCE_SUFFIX = Regex("--dd-[0-9a-f]{64}-[0-9a-f]{64}$")
    private val NUMBER = Regex("\\d+")

    internal fun versionKey(name: String): List<Long> =
        NUMBER.findAll(ARCH.replace(SOURCE_SUFFIX.replace(name, ""), " ")).mapNotNull { it.value.toLongOrNull() }.toList()

    internal fun compareVersions(a: String, b: String): Int {
        val x = versionKey(a)
        val y = versionKey(b)
        for (i in 0 until minOf(x.size, y.size)) if (x[i] != y[i]) return x[i].compareTo(y[i])
        return if (x.size != y.size) x.size.compareTo(y.size) else a.compareTo(b)
    }

    internal fun updateFor(installed: List<String>, latest: String?): String? {
        if (latest == null || installed.isEmpty()) return null
        val newest = installed.maxWithOrNull { a, b -> compareVersions(a, b) } ?: return null
        val x = versionKey(latest)
        val y = versionKey(newest)
        for (i in 0 until minOf(x.size, y.size)) if (x[i] != y[i]) return if (x[i] > y[i]) latest else null
        return if (x.size > y.size) latest else null
    }

    fun installedTags(context: Context, tool: Tool): List<String> {
        val recorded = readJson(releasesFile(context))
        return installedAll(context, tool).map { recorded.optString(it).ifEmpty { it } }
    }

    fun update(context: Context, tool: Tool): String? = updateFor(installedTags(context, tool), cachedLatest(context, tool))

    fun cachedLatest(context: Context, tool: Tool): String? =
        readJson(latestFile(context)).optJSONObject(tool.id)?.optString("tag")?.ifEmpty { null }

    private val checkLock = Any()
    private const val RETRY_MS = 15L * 60 * 1000
    private var lastAttempt = 0L

    fun checkLatest(context: Context, force: Boolean = false, now: Long = System.currentTimeMillis()): Boolean {
        synchronized(checkLock) {
            val file = latestFile(context)
            val cache = readJson(file)
            val visible = RepositorySources.visible(context, SOURCE_SCOPE, sourceBuiltIns())
            val candidates = visible.flatMap { source ->
                tools.filter { it.repo == source.repository }.map {
                    Tool(it.id, it.name, it.prefix, source.repository, it.assetPattern, source.id, it.id)
                }.ifEmpty {
                    tools.map { base ->
                        Tool("${source.id.take(12)}-${base.id}", "${source.repository} · ${base.name}", base.prefix,
                            source.repository, base.assetPattern, source.id, base.id)
                    } + Tool("${source.id.take(12)}-manifest", source.repository, "", source.repository,
                        Regex("a^"), source.id, "manifest")
                }
            }
            val sourceChecks = cache.optJSONObject(SOURCE_CHECKS) ?: JSONObject()
            val due = candidates.groupBy { it.sourceId }.filterValues { sourceTools ->
                val sourceId = sourceTools.first().sourceId
                val checkedAt = sourceChecks.optLong(sourceId, 0L).takeIf { it > 0L }
                    ?: sourceTools.minOfOrNull { cache.optJSONObject(it.id)?.optLong("at", 0L) ?: 0L }
                    ?: 0L
                force || (now - checkedAt) !in 0 until CHECK_EVERY_MS
            }
            if (due.isEmpty() || !force && (now - lastAttempt) in 0 until RETRY_MS) return false
            lastAttempt = now
            var refreshError: Throwable? = null
            for ((sourceId, sourceTools) in due) {
                val source = visible.firstOrNull { it.id == sourceId } ?: continue
                val generation = RepositorySources.generation(context, SOURCE_SCOPE, sourceBuiltIns())
                val releases = runCatching {
                    GitHubArtifacts.refreshReleases(context, SOURCE_SCOPE, sourceBuiltIns(), source)
                }.onFailure {
                    Log.w(TAG, "release check for ${source.repository}", it)
                    if (refreshError == null || GitHubArtifacts.isRateLimited(it)) refreshError = it
                }.getOrNull() ?: continue
                val bound = RepositorySources.visible(context, SOURCE_SCOPE, sourceBuiltIns())
                    .firstOrNull { it.id == sourceId } ?: continue
                runCatching {
                    val selections = sourceTools.associateWith { tool ->
                        if (tool.baseId == "manifest") pickManifest(context, bound, releases)
                        else pickAsset(tool, bound, releases)
                    }
                    RepositorySources.whileCurrent(context, SOURCE_SCOPE, sourceBuiltIns(), sourceId, generation) {
                        for ((tool, asset) in selections) {
                            if (asset == null) {
                                if (bound.repository !in sourceBuiltIns()) cache.remove(tool.id)
                                continue
                            }
                            cache.put(tool.id, JSONObject().put("tag", asset.tag).put("at", now)
                                .put("sourceId", tool.sourceId).put("baseId", tool.baseId)
                                .put("name", asset.toolName.ifEmpty { tool.name })
                                .put("prefix", asset.toolPrefix.ifEmpty { tool.prefix }))
                        }
                        sourceChecks.put(sourceId, now)
                        cache.put(SOURCE_CHECKS, sourceChecks)
                        RepositorySources.atomicWrite(file, cache.toString().toByteArray(Charsets.UTF_8))
                    }
                }.onFailure {
                    Log.w(TAG, "release check for ${source.repository}", it)
                    if (refreshError == null || GitHubArtifacts.isRateLimited(it)) refreshError = it
                }
            }
            if (force) refreshError?.let { throw it }
            return true
        }
    }

    private fun record(context: Context, tool: Tool, dir: String?, tag: String) {
        runCatching {
            val latest = readJson(latestFile(context))
            val known = latest.optJSONObject(tool.id)?.optString("tag")
            if (known.isNullOrEmpty() || compareVersions(tag, known) >= 0) {
                latest.put(tool.id, JSONObject().put("tag", tag).put("at", System.currentTimeMillis())
                    .put("sourceId", tool.sourceId).put("baseId", tool.baseId).put("name", tool.name)
                    .put("prefix", tool.prefix))
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

    fun queued(context: Context, tool: Tool): Boolean =
        requestLines(context).any { it.trim() == tool.id || it.trim().startsWith(tool.id + " ") }

    fun unqueue(context: Context, tool: Tool) {
        val lines = requestLines(context).filterNot { it.trim() == tool.id || it.trim().startsWith(tool.id + " ") }
        val file = requests(context)
        if (lines.isEmpty()) file.delete()
        else file.writeText(lines.joinToString("\n") + "\n")
    }

    fun remove(context: Context, tool: Tool, dir: String? = null) {
        val name = if (dir != null) dir.takeIf { it in installedAll(context, tool) } ?: return else installed(context, tool) ?: return
        File(toolsDir(context), name).deleteRecursively()
    }

    fun removeOlder(context: Context, tool: Tool) {
        installedAll(context, tool).dropLast(1).forEach { File(toolsDir(context), it).deleteRecursively() }
    }

    @Synchronized
    fun install(context: Context, tool: Tool, onProgress: (String, Int) -> Unit): String? {
        if (installInProgress) return context.getString(R.string.pextra_busy)
        installInProgress = true
        return try { installNow(context, tool, onProgress) }
        catch (error: Exception) {
            Log.w(TAG, "install ${tool.id}", error)
            GitHubArtifacts.failureMessage(context, error)
        } finally { installInProgress = false }
    }

    private fun installNow(context: Context, tool: Tool, onProgress: (String, Int) -> Unit): String? {
        if (SessionState.running) return context.getString(R.string.pextra_stop_session)
        if (!LinuxRuntime.isInstalled(context)) return context.getString(R.string.user_apps_runtime_required)

        val asset = findLatestAsset(context, tool) ?: return context.getString(R.string.pextra_no_release, tool.name)
        if (asset.size !in 1..(2L * 1024 * 1024 * 1024)) return "Proton archive has an invalid size"
        val sourceGeneration = RepositorySources.generation(context, SOURCE_SCOPE, sourceBuiltIns())
        val downloads = File(context.filesDir, "proton-downloads").apply { mkdirs() }
        val archive = File(downloads, "${asset.sha256}-${asset.sourceId}.tar")
        if (asset.size > 0 && archive.length() > asset.size) archive.delete()
        val missing = (asset.size - archive.length()).coerceAtLeast(0L)
        if (StatFs(LinuxRuntime.rootDir(context).path).availableBytes < NEED_BYTES + missing) {
            return context.getString(R.string.pextra_no_space, tool.name)
        }

        val downloading = context.getString(R.string.pextra_downloading, tool.name, asset.tag)
        onProgress(downloading, 0)
        var downloaded = false
        for (attempt in 0 until 5) {
            downloaded = Downloader.downloadFile(asset.url, archive, true, asset.sha256) { fraction ->
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

        onProgress(context.getString(R.string.pextra_verifying), -1)
        val verified = when {
            asset.sha256 != null -> asset.sha256.equals(Hashes.sha256(archive), ignoreCase = true)
            asset.sha512 != null -> {
                val expected = Downloader.downloadDirect(asset.sha512)?.let { Regex("(?i)\\b[0-9a-f]{128}\\b").find(it)?.value }
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
        if (!RepositorySources.isCurrent(context, SOURCE_SCOPE, sourceBuiltIns(), asset.sourceId, sourceGeneration)) {
            archive.delete()
            return "Proton source changed while downloading"
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
                "--identity", "${asset.sourceId}-${asset.sha256}", "--prefix", tool.prefix,
            )
            val command = LinuxRuntime.command(context, null, runtimeDir, null, guest)
            val process = ProcessBuilder(command)
                .directory(root)
                .redirectErrorStream(true)
            val hostEnv = process.environment()
            hostEnv["PROOT_LOADER"] = LinuxRuntime.prootLoader(context).path
            hostEnv["PROOT_TMP_DIR"] = LinuxRuntime.prootTempDir(context).path
            LinuxRuntime.prootLibraryPath(context).takeIf { it.isNotEmpty() }?.let { hostEnv["LD_LIBRARY_PATH"] = it }
            val child = process.start()
            var placed: String? = null
            child.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    Log.i(TAG, line)
                    line.substringAfter("droiddeck-proton-extra: installed ", "").takeIf { it.isNotEmpty() }?.let { placed = it }
                    if (line.contains("unpacking", ignoreCase = true) || line.contains("adopted", ignoreCase = true)) {
                        onProgress(installing, -1)
                    }
                }
            }
            val status = child.waitFor()
            if (status != 0) context.getString(R.string.pextra_install_failed, tool.name, status)
            else if (!RepositorySources.isCurrent(context, SOURCE_SCOPE, sourceBuiltIns(), asset.sourceId, sourceGeneration)) {
                placed?.let { File(toolsDir(context), it).deleteRecursively() }
                "Proton source changed while installing"
            }
            else {
                archive.delete()
                unqueue(context, tool)
                placed?.let {
                    try {
                        writeProvenance(context, it, tool, asset)
                    } catch (error: Throwable) {
                        File(toolsDir(context), it).deleteRecursively()
                        throw error
                    }
                }
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

    private fun findLatestAsset(context: Context, tool: Tool): Asset? {
        val source = RepositorySources.visible(context, SOURCE_SCOPE, sourceBuiltIns()).firstOrNull {
            (tool.sourceId.isNotEmpty() && it.id == tool.sourceId) || (tool.sourceId.isEmpty() && it.repository == tool.repo)
        } ?: return null
        return try {
            val releases = GitHubArtifacts.refreshReleases(context, SOURCE_SCOPE, sourceBuiltIns(), source)
            val bound = RepositorySources.visible(context, SOURCE_SCOPE, sourceBuiltIns()).single { it.id == source.id }
            if (tool.baseId == "manifest") pickManifest(context, bound, releases) else pickAsset(tool, bound, releases)
        } catch (e: Exception) {
            Log.w(TAG, "release metadata for ${tool.id}", e)
            if (GitHubArtifacts.isRateLimited(e)) throw e
            null
        }
    }

    private fun pickAsset(
        tool: Tool,
        source: RepositorySources.Source,
        releases: List<GitHubArtifacts.Release>,
    ): Asset? {
        for (release in releases) {
            if (release.prerelease) continue
            if (source.repository !in sourceBuiltIns() && release.assets.any { it.name in MANIFEST_NAMES }) continue
            val archive = release.assets.sortedBy { it.name }.firstOrNull { matchesAsset(tool, it.name) } ?: continue
            return Asset(release.tag, archive.name, archive.url, null, archive.size, archive.sha256,
                source.id, source.repository, source.repositoryId ?: 0, release.id, archive.id)
        }
        return null
    }

    private val MANIFEST_NAMES = setOf("droiddeck-component-proton.json", "droiddeck-proton.json")

    private fun matchesAsset(tool: Tool, name: String): Boolean =
        tool.assetPattern.containsMatchIn(name) &&
            (tool.repo in sourceBuiltIns() || name.startsWith(tool.prefix))

    private fun pickManifest(
        context: Context,
        source: RepositorySources.Source,
        releases: List<GitHubArtifacts.Release>,
    ): Asset? {
        for (release in releases) {
            if (release.prerelease) continue
            val sidecar = release.assets.singleOrNull { it.name in MANIFEST_NAMES } ?: continue
            val json = JSONObject(GitHubArtifacts.readReleaseAsset(
                context = context,
                key = SOURCE_SCOPE, builtIns = sourceBuiltIns(), source = source,
                tag = release.tag, asset = sidecar, maxBytes = 256 * 1024,
            )
                .toString(Charsets.UTF_8))
            require(json.getInt("schema") == 1) { "Unsupported Proton manifest" }
            if (sidecar.name == "droiddeck-component-proton.json") {
                require(json.getString("component") == "proton") { "Proton manifest component does not match" }
                require(json.getString("variant").matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) { "Invalid Proton variant" }
            }
            val name = json.getString("name")
            val prefix = json.getString("prefix")
            require(name.isNotBlank() && name.length <= 100 && Regex("[A-Za-z0-9._+ -]{1,100}").matches(prefix)) {
                "Invalid Proton tool identity"
            }
            val artifact = json.getJSONObject("artifact")
            val artifactName = artifact.getString("name")
            require(Regex("(?i).+\\.tar\\.(gz|xz)").matches(artifactName)) { "Proton artifact must be a tar.gz or tar.xz" }
            val archive = release.assets.singleOrNull { it.name == artifactName }
                ?: throw IllegalArgumentException("Proton manifest artifact is missing or ambiguous")
            val declaredSha = artifact.getString("sha256")
            require(Regex("[0-9a-f]{64}").matches(declaredSha) && declaredSha == archive.sha256) {
                "Proton manifest digest does not match"
            }
            require(artifact.getLong("size") == archive.size) { "Proton manifest size does not match" }
            return Asset(release.tag, archive.name, archive.url, null, archive.size, archive.sha256,
                source.id, source.repository, source.repositoryId ?: 0, release.id, archive.id, name, prefix)
        }
        return null
    }

    private fun writeProvenance(context: Context, directory: String, tool: Tool, asset: Asset) {
        val installed = File(toolsDir(context), directory)
        if (!installed.isDirectory) return
        val receipt = JSONObject().put("schema", 1).put("sourceId", asset.sourceId)
            .put("repository", asset.repository).put("repositoryId", asset.repositoryId)
            .put("releaseId", asset.releaseId).put("tag", asset.tag).put("assetId", asset.assetId)
            .put("name", asset.name).put("sha256", asset.sha256).put("toolId", tool.id)
            .put("baseId", tool.baseId).put("toolName", tool.name).put("toolPrefix", tool.prefix)
        val file = File(installed, ".droiddeck-source.json")
        val tmp = File(installed, ".droiddeck-source.json.tmp")
        tmp.writeText(receipt.toString())
        check(tmp.renameTo(file)) { "Could not record Proton source" }
    }

    internal fun pickAsset(tool: Tool, array: JSONArray): Asset? {
        for (i in 0 until array.length()) {
            val release = array.getJSONObject(i)
            if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) continue
            val assets = release.optJSONArray("assets") ?: continue
            val entries = (0 until assets.length()).map { assets.getJSONObject(it) }
            val archive = entries.sortedBy { it.optString("name") }.firstOrNull { matchesAsset(tool, it.optString("name")) } ?: continue
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
