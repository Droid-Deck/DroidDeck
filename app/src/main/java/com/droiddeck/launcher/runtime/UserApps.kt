package com.droiddeck.launcher.runtime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.system.Os
import android.util.Log
import com.caverock.androidsvg.SVG
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.Downloader
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.store.FlathubApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * The apps a user adds to the Desktop page: shell scripts, AppImages (from storage or a GitHub
 * release) and Flatpak apps. Each opens full screen under gamescope, like an emulator.
 *
 * A script lives at /opt/droiddeck-apps/<id>: its folder at `files`, the script's name in `entry`.
 * The folder is linked where it lies; shared storage is mounted noexec, so a folder holding Linux
 * programs is copied in instead. A linked folder outside internal storage is bound into the
 * session at its own path ([binds]), so the link resolves inside the guest.
 */
object UserApps {
    private const val TAG = "UserApps"
    const val GUEST_DIR = "/opt/droiddeck-apps"
    const val SCRIPT_LAUNCHER = "/usr/local/bin/bannerlator-script-run"
    private const val FLATPAK_OVERRIDES = ".flatpak"
    private const val ICON_SIZE = 256
    private const val ELF_SCAN_LIMIT = 4000
    private const val ICON_SUGGESTIONS = 7
    private const val ICON_FILE_LIMIT = 2L shl 20
    private val ICON_TYPES = setOf("png", "jpg", "jpeg", "webp")
    private val IGNORED_DIRS = Regex("(^|/)(node_modules|vendor|third[_-]?party|external|tests?|docs?/screenshots)/", RegexOption.IGNORE_CASE)
    private const val GITHUB_REPO = "github-repo"
    private const val GITHUB_TAG = "github-tag"
    private const val GITHUB_PUBLISHED = "github-published"

    enum class Kind { SCRIPT, APPIMAGE, FLATPAK }

    /**
     * [copied]: a script whose folder was copied in rather than linked. [repo]: the GitHub repository
     * an AppImage came from, [version] the release it is.
     */
    class App(
        val key: String, val kind: Kind, val name: String, val icon: Any?, val detail: String?,
        val program: String, val args: List<String>, val copied: Boolean = false,
        val repo: String? = null, val version: String? = null,
        val fex: String = LinuxFex.AUTO, val arch: String? = null,
    )

    class Release(val tag: String, val published: String, val asset: String, val url: String)

    sealed class UpdateCheck {
        class Current(val tag: String) : UpdateCheck()
        class Available(val release: Release) : UpdateCheck()
        class Failed(val message: String) : UpdateCheck()
    }

    sealed class Source {
        class Script(val file: File) : Source()
        class AppImage(val file: File) : Source()
        class GitHub(val repo: String) : Source()
        class Flatpak(val id: String) : Source()
    }

    /** [icon]: a picked file's path or a suggested icon's URL. */
    class Request(val source: Source, val name: String?, val icon: String?)

    /** How a script's folder reaches the session: linked in place, or copied in ([bytes] to copy). */
    class ScriptPlan(val folder: File, val copy: Boolean, val bytes: Long)

    private fun root(context: Context) = File(LinuxRuntime.rootDir(context), GUEST_DIR.substring(1))

    private fun overrides(context: Context, flatpakId: String) = File(root(context), "$FLATPAK_OVERRIDES/$flatpakId")

    private fun menuEntry(context: Context, id: String) =
        File(LinuxRuntime.rootDir(context), "usr/local/share/applications/droiddeck-app-$id.desktop")

    fun list(context: Context): List<App> {
        val scripts = root(context).listFiles()?.filter { File(it, "entry").isFile }?.map { dir ->
            App(
                "script:${dir.name}", Kind.SCRIPT, readName(dir) ?: dir.name, File(dir, "icon.png").takeIf { it.isFile },
                FileUtils.readString(File(dir, "source"))?.trim(), SCRIPT_LAUNCHER, listOf("$GUEST_DIR/${dir.name}"),
                copied = !java.nio.file.Files.isSymbolicLink(File(dir, "files").toPath()),
                fex = LinuxFex.mode(dir),
            )
        }.orEmpty()
        val images = AppImageManager.list(context).map { item ->
            val dir = AppImageManager.dir(context, item.id)
            App(
                "appimage:${item.id}", Kind.APPIMAGE, item.name, item.icon, item.comment, AppImageManager.LAUNCHER, listOf(item.guestDir),
                repo = FileUtils.readString(File(dir, GITHUB_REPO))?.trim(), version = FileUtils.readString(File(dir, GITHUB_TAG))?.trim(),
                fex = LinuxFex.mode(dir), arch = FileUtils.readString(File(dir, "arch"))?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
        val flatpaks = FlatpakManager.installedApps(context).map { app ->
            val custom = overrides(context, app.id)
            App(
                "flatpak:${app.id}", Kind.FLATPAK, readName(custom) ?: app.name,
                File(custom, "icon.png").takeIf { it.isFile } ?: app.icon ?: FlathubApi.iconUrl(app.id),
                app.summary, FlatpakManager.LAUNCHER, listOf(app.id),
            )
        }
        return (scripts + images + flatpaks).sortedBy { it.name.lowercase() }
    }

    private fun readName(dir: File) = FileUtils.readString(File(dir, "name"))?.trim()?.takeIf { it.isNotEmpty() }

    /** Adds [request]; null on success, else what went wrong. */
    fun add(context: Context, request: Request, onProgress: (String, Int) -> Unit): String? {
        if (!LinuxRuntime.isInstalled(context)) return context.getString(R.string.user_apps_runtime_required)
        val name = request.name?.trim()?.takeIf { it.isNotEmpty() }
        return withIcon(context, request.icon) { icon ->
            when (val s = request.source) {
                is Source.Script -> addScript(context, s.file, name, icon, onProgress)
                is Source.AppImage -> AppImageManager.import(context, s.file, name, icon) { onProgress(it, -1) }
                is Source.GitHub -> addFromGitHub(context, s.repo, name, icon, onProgress)
                is Source.Flatpak -> addFlatpak(context, s.id, name, icon, onProgress)
            }
        }
    }

    /** [icon] as a file for [use]: a path as it is, a URL downloaded for the while. */
    private inline fun <T> withIcon(context: Context, icon: String?, use: (File?) -> T): T {
        if (icon == null || !icon.startsWith("https://")) return use(icon?.let(::File))
        val download = File(context.cacheDir, "icon-${System.nanoTime()}")
        try {
            return use(download.takeIf { Downloader.downloadFile(icon, it, false) { } })
        } finally {
            download.delete()
        }
    }

    /**
     * Renames [app] and sets its icon: [icon] a path or URL, "" for its default, null to keep it.
     * The Linux desktop's menu entry follows.
     */
    fun edit(context: Context, app: App, name: String, icon: String?, fex: String? = null): String? {
        val id = app.key.substringAfter(':')
        if (id.isEmpty() || '/' in id || id.startsWith(".")) return null
        val dir = when (app.kind) {
            Kind.SCRIPT -> File(root(context), id)
            Kind.APPIMAGE -> AppImageManager.dir(context, id)
            Kind.FLATPAK -> overrides(context, id)
        }.apply { mkdirs() }
        FileUtils.writeString(File(dir, "name"), name.trim())
        val target = File(dir, "icon.png")
        when {
            icon == null -> {}
            icon.isEmpty() -> if (app.kind == Kind.APPIMAGE) AppImageManager.restoreIcon(dir) else target.delete()
            else -> withIcon(context, icon) { file -> if (file == null || !saveIcon(file, target)) return context.getString(R.string.user_apps_icon_failed) }
        }
        if (fex != null && app.kind != Kind.FLATPAK && !LinuxFex.setMode(dir, fex)) return context.getString(R.string.user_apps_failed)
        when (app.kind) {
            Kind.SCRIPT -> writeScriptEntry(context, dir)
            Kind.APPIMAGE -> AppImageManager.writeMenuEntry(context, dir)
            Kind.FLATPAK -> {}
        }
        return null
    }

    fun remove(context: Context, app: App, onProgress: (String, Int) -> Unit): String? {
        val id = app.key.substringAfter(':')
        if (id.isEmpty() || '/' in id || id.startsWith(".")) return null
        return when (app.kind) {
            Kind.SCRIPT -> {
                val dir = File(root(context), id)
                // The link goes, never the folder it points to.
                File(dir, "files").delete()
                FileUtils.delete(dir)
                menuEntry(context, id).delete()
                null
            }
            Kind.APPIMAGE -> { AppImageManager.remove(context, id); null }
            Kind.FLATPAK -> FlatpakManager.uninstall(context, id, onProgress).also {
                if (it == null) FileUtils.delete(overrides(context, id))
            }
        }
    }

    /** Folders linked from outside internal storage, bound into a session at their own paths. */
    fun binds(context: Context): List<String> {
        val internal = Environment.getExternalStorageDirectory().canonicalPath
        return root(context).listFiles().orEmpty().mapNotNull { dir ->
            val target = runCatching { Os.readlink(File(dir, "files").path) }.getOrNull() ?: return@mapNotNull null
            target.takeIf { !it.startsWith("$internal/") && File(it).isDirectory }
        }.distinct()
    }

    fun planScript(script: File): ScriptPlan {
        val folder = script.canonicalFile.parentFile ?: script.parentFile!!
        var scanned = 0
        val copy = folder.walkTopDown().maxDepth(4).filter { it.isFile }.takeWhile { scanned++ < ELF_SCAN_LIMIT }.any(::isElf)
        val bytes = if (copy) folder.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
        return ScriptPlan(folder, copy, bytes)
    }

    private fun isElf(f: File): Boolean = try {
        RandomAccessFile(f, "r").use { val b = ByteArray(4); it.read(b) == 4 && b[0] == 0x7f.toByte() && b[1] == 'E'.code.toByte() && b[2] == 'L'.code.toByte() && b[3] == 'F'.code.toByte() }
    } catch (e: Exception) { false }

    private fun addScript(context: Context, script: File, name: String?, icon: File?, onProgress: (String, Int) -> Unit): String? {
        if (!script.isFile) return context.getString(R.string.user_apps_file_gone)
        val plan = planScript(script)
        val base = root(context).apply { mkdirs() }
        if (plan.copy && StatFs(base.path).availableBytes < plan.bytes + (64L shl 20)) {
            return context.getString(R.string.user_apps_no_space, FileUtils.sizeToString(plan.bytes))
        }
        val title = name ?: script.nameWithoutExtension
        val id = AppImageManager.idFor(title, base.list()?.toSet() ?: emptySet())
        val dir = File(base, id)
        try {
            dir.mkdirs()
            val files = File(dir, "files")
            if (plan.copy) {
                onProgress(context.getString(R.string.user_apps_copying, plan.folder.name), -1)
                copyTree(plan.folder, files)
            } else {
                Os.symlink(plan.folder.path, files.path)
            }
            FileUtils.writeString(File(dir, "entry"), script.name)
            FileUtils.writeString(File(dir, "name"), title)
            FileUtils.writeString(File(dir, "source"), script.canonicalPath)
            if (icon != null && !saveIcon(icon, File(dir, "icon.png"))) Log.w(TAG, "icon ${icon.path} could not be read")
            writeScriptEntry(context, dir)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "script ${script.path}", e)
            File(dir, "files").takeIf { !plan.copy }?.delete()
            FileUtils.delete(dir)
            return e.message ?: context.getString(R.string.user_apps_failed)
        }
    }

    private fun writeScriptEntry(context: Context, dir: File) {
        val id = dir.name
        val icon = if (File(dir, "icon.png").isFile) "$GUEST_DIR/$id/icon.png" else "utilities-terminal"
        FileUtils.writeString(menuEntry(context, id),
            "[Desktop Entry]\nType=Application\nName=${readName(dir) ?: id}\nExec=$SCRIPT_LAUNCHER $GUEST_DIR/$id\nIcon=$icon\n" +
            "Terminal=false\nCategories=Game;\nX-DroidDeck-App=$id\n")
    }

    /** Copies [from] into [to], keeping Linux programs and scripts executable. */
    private fun copyTree(from: File, to: File) {
        from.walkTopDown().forEach { src ->
            val dst = File(to, src.relativeTo(from).path)
            if (src.isDirectory) dst.mkdirs()
            else {
                src.inputStream().use { input -> dst.outputStream().use { FileUtils.copy(input, it) } }
                if (isElf(dst) || dst.extension == "sh") dst.setExecutable(true, false)
            }
        }
    }

    /** [source] (PNG, JPEG, WebP or SVG) as the app's icon: a PNG no larger than [ICON_SIZE] on a side. */
    internal fun saveIcon(source: File, target: File): Boolean {
        val bitmap = (if (isSvg(source)) renderSvg(source) else decodeScaled(source)) ?: return false
        return try {
            target.parentFile?.mkdirs()
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: Exception) {
            Log.w(TAG, "icon ${source.path}", e); false
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeScaled(source: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= ICON_SIZE) sample *= 2
        val decoded = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = ICON_SIZE.toFloat() / maxOf(decoded.width, decoded.height)
        if (scale >= 1f) return decoded
        val scaled = Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun isSvg(f: File): Boolean = try {
        f.inputStream().use { val head = ByteArray(1024); val n = it.read(head); n > 0 && String(head, 0, n).contains("<svg") }
    } catch (e: Exception) { false }

    /** An SVG drawn at [ICON_SIZE], centred in its own proportions. */
    private fun renderSvg(source: File): Bitmap? = try {
        val svg = source.inputStream().use { SVG.getFromInputStream(it) }
        if (svg.documentViewBox == null && svg.documentWidth > 0 && svg.documentHeight > 0) {
            svg.setDocumentViewBox(0f, 0f, svg.documentWidth, svg.documentHeight)
        }
        svg.setDocumentWidth("100%")
        svg.setDocumentHeight("100%")
        Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888).also { svg.renderToCanvas(Canvas(it)) }
    } catch (e: Exception) {
        Log.w(TAG, "svg ${source.path}", e); null
    }

    /** "owner/repo" from what was typed: any link into the repository, its clone address, or the short form. */
    fun githubRepo(input: String): String? {
        val t = input.trim()
        val m = Regex("^(?:https?://)?(?:www\\.)?github\\.com[/:]([\\w.-]+)/([\\w.-]+)", RegexOption.IGNORE_CASE).find(t)
            ?: Regex("^git@github\\.com:([\\w.-]+)/([\\w.-]+)", RegexOption.IGNORE_CASE).find(t)
            ?: Regex("^([\\w.-]+)/([\\w.-]+)/?$").find(t) ?: return null
        val repo = m.groupValues[2].removeSuffix(".git")
        return if (repo.isEmpty() || repo == "." || repo == "..") null else "${m.groupValues[1]}/$repo"
    }

    /**
     * The AppImage among a release's files: named for aarch64/arm64, else for no other CPU, else the
     * x86_64 one, which runs through FEX.
     */
    internal fun pickAsset(names: List<String>): String? {
        val images = names.filter { it.endsWith(".appimage", ignoreCase = true) }
        return images.firstOrNull { Regex("aarch64|arm64", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            ?: images.firstOrNull { !Regex("x86_64|x86-64|amd64|x64|i[36]86|armhf|armv7", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            ?: images.firstOrNull { Regex("x86_64|x86-64|amd64|x64", RegexOption.IGNORE_CASE).containsMatchIn(it) }
    }

    /** The newest release of [repo] with an AppImage this device runs, preferring full releases; else what went wrong. */
    private fun latestRelease(context: Context, repo: String): Pair<Release?, String?> {
        val body = Downloader.downloadString("https://api.github.com/repos/$repo/releases?per_page=15")
        val releases = body?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?: return null to context.getString(R.string.user_apps_github_unreachable, repo)
        val candidates = (0 until releases.length()).mapNotNull { i ->
            val r = releases.getJSONObject(i)
            if (r.optBoolean("draft")) return@mapNotNull null
            val assets = r.optJSONArray("assets") ?: return@mapNotNull null
            val byName = (0 until assets.length()).associate { j -> assets.getJSONObject(j).let { it.getString("name") to it.getString("browser_download_url") } }
            pickAsset(byName.keys.toList())?.let { asset ->
                r.optBoolean("prerelease") to Release(r.optString("tag_name", asset), r.optString("published_at"), asset, byName.getValue(asset))
            }
        }
        val pick = (candidates.firstOrNull { !it.first } ?: candidates.firstOrNull())?.second
        return pick to if (pick == null) context.getString(R.string.user_apps_github_no_appimage, repo) else null
    }

    /** Downloads [release] and hands it to [use]; null on success, else what went wrong. */
    private fun withDownload(context: Context, release: Release, onProgress: (String, Int) -> Unit, use: (File) -> String?): String? {
        val download = File(context.cacheDir, "github-${release.asset}")
        try {
            val label = context.getString(R.string.user_apps_downloading, release.asset)
            val ok = Downloader.downloadFile(release.url, download, false) { f -> onProgress(label, if (f < 0) -1 else Math.round(f * 100f)) }
            return if (ok) use(download) else context.getString(R.string.user_apps_download_failed)
        } finally {
            download.delete()
        }
    }

    private fun githubMeta(repo: String, release: Release) =
        mapOf(GITHUB_REPO to repo, GITHUB_TAG to release.tag, GITHUB_PUBLISHED to release.published)

    private fun addFromGitHub(context: Context, repo: String, name: String?, icon: File?, onProgress: (String, Int) -> Unit): String? {
        onProgress(context.getString(R.string.user_apps_github_checking, repo), -1)
        val (release, problem) = latestRelease(context, repo)
        if (release == null) return problem
        return withDownload(context, release, onProgress) { file ->
            AppImageManager.import(context, file, name ?: repo.substringAfter('/'), icon, githubMeta(repo, release)) { onProgress(it, -1) }
        }
    }

    /**
     * Images in [repo] that look like its icon, best first, then its owner's picture: links to show
     * as suggestions and to download once one is picked.
     */
    fun githubIcons(repo: String): List<String> {
        val info = Downloader.downloadString("https://api.github.com/repos/$repo")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyList()
        val branch = info.optString("default_branch").ifEmpty { return emptyList() }
        val avatar = info.optJSONObject("owner")?.optString("avatar_url")?.takeIf { it.startsWith("https://") }
        val tree = Downloader.downloadString("https://api.github.com/repos/$repo/git/trees/${Uri.encode(branch)}?recursive=1")
            ?.let { runCatching { JSONObject(it).optJSONArray("tree") }.getOrNull() }
        val project = repo.substringAfter('/').lowercase().replace(Regex("[^a-z0-9]"), "")
        val images = (0 until (tree?.length() ?: 0)).mapNotNull { i ->
            val entry = tree!!.getJSONObject(i)
            val path = entry.optString("path")
            val size = entry.optLong("size")
            val file = path.substringAfterLast('/').lowercase()
            val stem = file.substringBeforeLast('.').replace(Regex("[^a-z0-9]"), "")
            if (entry.optString("type") != "blob" || size !in 512..ICON_FILE_LIMIT || file.substringAfterLast('.') !in ICON_TYPES) return@mapNotNull null
            if (IGNORED_DIRS.containsMatchIn(path)) return@mapNotNull null
            val score = listOf("icon" in stem, "logo" in stem, project.isNotEmpty() && project in stem, "icon" in path.lowercase()).count { it }
            if (score == 0) null else Triple(path, score, size)
        }.sortedWith(compareByDescending<Triple<String, Int, Long>> { it.second }.thenByDescending { it.third })
        val urls = images.take(ICON_SUGGESTIONS).map { (path, _, _) ->
            "https://raw.githubusercontent.com/$repo/${Uri.encode(branch)}/" + path.split('/').joinToString("/") { Uri.encode(it) }
        }
        return urls + listOfNotNull(avatar)
    }

    /** Whether [app], added from GitHub, has a newer release than the one it is. */
    fun checkUpdate(context: Context, app: App): UpdateCheck {
        val repo = app.repo ?: return UpdateCheck.Failed(context.getString(R.string.user_apps_update_failed))
        val (release, problem) = latestRelease(context, repo)
        if (release == null) return UpdateCheck.Failed(problem ?: context.getString(R.string.user_apps_update_failed))
        val published = FileUtils.readString(File(AppImageManager.dir(context, app.key.substringAfter(':')), GITHUB_PUBLISHED))?.trim().orEmpty()
        // Releases are compared by date, so a newest one without an ARM64 build never reads as a downgrade.
        val newer = if (published.isNotEmpty() && release.published.isNotEmpty()) release.published > published else release.tag != app.version
        return if (newer) UpdateCheck.Available(release) else UpdateCheck.Current(app.version ?: release.tag)
    }

    fun update(context: Context, app: App, release: Release, onProgress: (String, Int) -> Unit): String? {
        val repo = app.repo ?: return context.getString(R.string.user_apps_update_failed)
        return withDownload(context, release, onProgress) { file ->
            AppImageManager.replace(context, app.key.substringAfter(':'), file, githubMeta(repo, release)) { onProgress(it, -1) }
        }
    }

    /** A Flathub app ID from what was typed: the ID itself or its Flathub page. */
    fun flatpakId(input: String): String? {
        val t = input.trim().removeSuffix("/").substringAfterLast('/')
        return t.takeIf { Regex("^[A-Za-z][\\w-]*(\\.[A-Za-z0-9_][\\w-]*){2,}$").matches(it) }
    }

    private fun addFlatpak(context: Context, id: String, name: String?, icon: File?, onProgress: (String, Int) -> Unit): String? {
        if (!FlatpakManager.ready(context)) FlatpakManager.setup(context, onProgress)?.let { return it }
        FlatpakManager.install(context, id, onProgress)?.let { return it }
        val custom = overrides(context, id)
        if (name != null) FileUtils.writeString(File(custom.apply { mkdirs() }, "name"), name)
        if (icon != null && !saveIcon(icon, File(custom, "icon.png"))) Log.w(TAG, "icon ${icon.path} could not be read")
        return null
    }
}
