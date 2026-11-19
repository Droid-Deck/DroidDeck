package com.droiddeck.launcher.session

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.droiddeck.launcher.core.Downloader
import com.droiddeck.launcher.core.Hashes
import com.droiddeck.launcher.R
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipFile as CommonsZipFile
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import org.json.JSONArray
import org.json.JSONObject

object ExperimentalProtons {
    private const val TAG = "ExperimentalProtons"
    const val PREFIX = "Experimental-"
    private const val MARKER = ".droiddeck-experimental.json"
    private const val STAGING = ".droiddeck-experimental"
    private const val BACKUPS = ".droiddeck-experimental-old"
    private const val INCOMING = ".droiddeck-experimental-new"
    private const val GUEST_TOOLS = "/root/.local/share/Steam/compatibilitytools.d"
    private const val NEED_BYTES = 4L * 1024 * 1024 * 1024
    private const val USER_SETTINGS = "user_settings.py"
    private val TARBALL = Regex("(?i)\\.tar(\\.(gz|xz|zst|bz2))?$")
    private val DOWNLOAD_PREFIX = "droiddeck-"

    data class Installed(val dir: String, val label: String, val source: String, val releaseTag: String? = null)
    data class Release(val tag: String, val asset: String, val url: String, val sha512: String?, val size: Long, val name: String = tag)
    enum class ArchiveType { ZIP, SEVEN_Z, TAR, UNKNOWN }

    private fun toolsDir(context: Context) = File(LinuxRuntime.rootDir(context), GUEST_TOOLS.removePrefix("/"))

    fun installed(context: Context): List<Installed> =
        toolsDir(context).listFiles()
            ?.filter { it.isDirectory && File(it, MARKER).isFile }
            ?.map { dir ->
                val info = runCatching { JSONObject(File(dir, MARKER).readText()) }.getOrDefault(JSONObject())
                Installed(dir.name, info.optString("label").ifEmpty { dir.name }, info.optString("source"), info.optString("releaseTag").ifEmpty { null })
            }
            ?.sortedBy { it.dir }
            ?: emptyList()

    fun remove(context: Context, dir: String): Boolean {
        val tools = toolsDir(context)
        val target = File(tools, dir)
        if (!File(target, MARKER).isFile) return false
        val gone = deleteTree(target) && deleteTree(File(File(tools, BACKUPS), dir)) && deleteTree(File(File(tools, INCOMING), dir))
        if (gone) ComponentsManager.forgetProton(context, dir)
        return gone
    }

    internal fun dirName(tree: String, label: String?): String {
        val base = ComponentsManager.safeName(tree)
        val tag = label?.let(ComponentsManager::safeName)?.takeIf { it != "unnamed" && !base.contains(it, ignoreCase = true) }
        return PREFIX + base + (tag?.let { "-$it" } ?: "")
    }

    fun refresh(context: Context): List<Release>? {
        val tool = ProtonExtras.tools.first { it.id == ProtonExtras.OWN }
        val fetched = ProtonExtras.releasesForRemoval(tool) ?: return null
        val assets = ProtonExtras.assets(tool, fetched.releases, prerelease = true)
        val rows = assets.map { Release(it.tag, it.name, it.url, it.sha512, it.size, it.releaseName) }.distinctBy { it.tag }
        if (!SessionState.running) {
            reconcile(context, fetched)
            if (fetched.complete) removeStaleDownloads(context, fetched.releases.tags())
        }
        return rows
    }

    private fun JSONArray.tags(): Set<String> = (0 until length()).mapNotNull { optJSONObject(it)?.optString("tag_name")?.takeIf(String::isNotEmpty) }.toSet()

    internal fun removeStaleDownloads(context: Context, availableTags: Set<String>) {
        val downloads = File(context.filesDir, "proton-downloads")
        downloads.listFiles()?.filter { file ->
            file.isFile && file.name.startsWith(DOWNLOAD_PREFIX) && availableTags.none { tag -> file.name.startsWith("$DOWNLOAD_PREFIX${ComponentsManager.safeName(tag)}-") }
        }?.forEach { it.delete() }
    }

    internal fun available(releases: List<Release>, installed: List<Installed>): List<Release> =
        releases.filter { release -> installed.none { it.releaseTag == release.tag } }

    internal fun shouldRemove(installedReleaseTag: String?, fetched: ProtonExtras.ReleaseFetch): Boolean {
        val tag = installedReleaseTag ?: return false
        val release = (0 until fetched.releases.length()).asSequence()
            .mapNotNull { fetched.releases.optJSONObject(it) }
            .firstOrNull { it.optString("tag_name") == tag }
        return if (release != null) !release.optBoolean("prerelease", false) else fetched.complete
    }

    internal fun reconcile(context: Context, fetched: ProtonExtras.ReleaseFetch) {
        if (SessionState.running) return
        installed(context).filter { shouldRemove(it.releaseTag, fetched) }.forEach { remove(context, it.dir) }
    }

    internal fun compatVdf(name: String): String = """"compatibilitytools"
{
	"compat_tools"
	{
		"$name"
		{
			"install_path" "."
			"display_name" "$name"
			"from_oslist" "windows"
			"to_oslist" "linux"
		}
	}
}
"""

    fun installFromFile(context: Context, file: File, onProgress: (String, Int) -> Unit): String? {
        preflight(context)?.let { return it }
        if (StatFs(LinuxRuntime.rootDir(context).path).availableBytes < NEED_BYTES + file.length()) {
            return context.getString(R.string.xproton_no_space)
        }
        return install(context, file, fileLabel(file.name), file.name, null, onProgress, false)
    }

    internal fun fileLabel(name: String): String =
        if (TARBALL.containsMatchIn(name)) name.replace(TARBALL, "") else name.substringBeforeLast('.')

    fun installRelease(context: Context, release: Release, onProgress: (String, Int) -> Unit): String? {
        preflight(context)?.let { return it }
        val downloads = File(context.filesDir, "proton-downloads").apply { mkdirs() }
        val archive = File(downloads, "droiddeck-${ComponentsManager.safeName(release.tag)}-${release.asset}")
        if (release.size > 0 && archive.length() > release.size) archive.delete()
        val missing = (release.size - archive.length()).coerceAtLeast(0L)
        val copyBytes = if (sameFileStore(archive, toolsDir(context))) 0L else release.size
        if (StatFs(LinuxRuntime.rootDir(context).path).availableBytes < NEED_BYTES + missing + copyBytes) return context.getString(R.string.xproton_no_space)
        val title = context.getString(R.string.pextra_downloading, "Proton", release.tag)
        onProgress(title, 0)
        if (!Downloader.downloadFile(release.url, archive, true) { fraction ->
                onProgress(title, if (fraction < 0) -1 else (fraction * 100f).toInt().coerceIn(0, 100))
            }) return context.getString(R.string.pextra_download_failed)
        if (release.size > 0 && archive.length() != release.size) return context.getString(R.string.pextra_incomplete)
        if (release.sha512 != null) {
            val expected = Downloader.downloadDirect(release.sha512)?.let(::checksumIn)
                ?: return context.getString(R.string.pextra_checksum_unreadable)
            if (!expected.equals(Hashes.sha512(archive), true)) {
                archive.delete()
                return context.getString(R.string.pextra_checksum_mismatch)
            }
        }
        return try { install(context, archive, release.tag, release.tag, release.tag, onProgress, true) }
        finally { archive.delete() }
    }

    private fun sameFileStore(file: File, directory: File): Boolean = runCatching {
        directory.mkdirs()
        Files.getFileStore(file.toPath()) == Files.getFileStore(directory.toPath())
    }.getOrDefault(false)

    private fun preflight(context: Context): String? = when {
        SessionState.running -> context.getString(R.string.pextra_stop_session)
        !LinuxRuntime.isInstalled(context) -> context.getString(R.string.user_apps_runtime_required)
        else -> null
    }

    private fun install(context: Context, archive: File, label: String, source: String, releaseTag: String?, onProgress: (String, Int) -> Unit, downloaded: Boolean): String? {
        val tools = toolsDir(context).apply { mkdirs() }
        val staging = File(tools, STAGING)
        val forget = { name: String -> ComponentsManager.forgetProton(context, name) }
        restoreInterrupted(tools, forget)
        deleteTree(staging)
        staging.mkdirs()
        try {
            LinuxRuntime.writeAccounts(context)
            SessionFiles.stage(context, LinuxRuntime.rootDir(context))
            onProgress(context.getString(R.string.xproton_unpacking), -1)
            val unpacked = File(staging, "unpacked").apply { mkdirs() }
            val type = archiveType(archive)
            if (SessionState.running) return context.getString(R.string.pextra_session_started)

            onProgress(context.getString(R.string.xproton_unpacking), -1)
            when (type) {
                ArchiveType.ZIP -> {
                    val tarball = extractTarball(archive, staging)
                    if (tarball == null) {
                        extractDirect(archive, unpacked, ArchiveType.ZIP)
                    } else {
                        val status = runGuest(context, listOf("tar", "-xf", "$GUEST_TOOLS/$STAGING/${tarball.name}", "-C", "$GUEST_TOOLS/$STAGING/unpacked"))
                        tarball.delete()
                        if (status != 0) return context.getString(R.string.xproton_untar_failed, status)
                    }
                }
                ArchiveType.SEVEN_Z -> extractDirect(archive, unpacked, ArchiveType.SEVEN_Z)
                ArchiveType.TAR -> {
                    val stagedArchive = File(staging, archive.name)
                    if (!archive.canonicalFile.equals(stagedArchive.canonicalFile) && !(downloaded && archive.renameTo(stagedArchive))) {
                        archive.copyTo(stagedArchive, overwrite = true)
                    }
                    val status = runGuest(context, listOf("tar", "-xf", "$GUEST_TOOLS/$STAGING/${stagedArchive.name}", "-C", "$GUEST_TOOLS/$STAGING/unpacked"))
                    if (status != 0) return context.getString(R.string.xproton_untar_failed, status)
                }
                ArchiveType.UNKNOWN -> return context.getString(R.string.xproton_archive_invalid)
            }

            val tree = buildTree(unpacked) ?: return context.getString(R.string.xproton_no_tree)
            val name = dirName(tree.name, label)
            ownFile(tree, "compatibilitytool.vdf").writeText(compatVdf(name))
            ownFile(tree, MARKER).writeText(
                JSONObject().put("label", name).put("source", source).putOpt("releaseTag", releaseTag).put("at", System.currentTimeMillis() / 1000).toString(),
            )
            val final = File(tools, name)
            val settings = File(final, USER_SETTINGS)
            if (Files.isRegularFile(settings.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                settings.copyTo(ownFile(tree, USER_SETTINGS))
            }
            if (!placeTree(tree, tools, name, forget)) return context.getString(R.string.xproton_place_failed)

            onProgress(context.getString(R.string.pextra_installing, name), -1)
            runGuest(context, listOf("steam-compatibility", "/root/.local/share/Steam"))
            return null
        } catch (e: Exception) {
            Log.e(TAG, "install $source", e)
            return if (e is UnsafeArchiveException) context.getString(R.string.xproton_archive_unsafe) else context.getString(R.string.xproton_archive_invalid)
        } finally {
            deleteTree(staging)
        }
    }

    internal fun buildTree(unpacked: File): File? {
        val root = unpacked.canonicalFile
        return unpacked.listFiles()?.sortedBy { it.name }?.firstOrNull {
            !Files.isSymbolicLink(it.toPath()) && it.isDirectory && it.canonicalFile.parentFile == root &&
                Files.isRegularFile(File(it, "toolmanifest.vdf").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
                ComponentsManager.isProtonTree(it)
        }
    }

    @OptIn(ExperimentalPathApi::class)
    internal fun deleteTree(dir: File): Boolean =
        runCatching { dir.toPath().deleteRecursively() }.onFailure { Log.w(TAG, "delete $dir", it) }.isSuccess && !Files.exists(dir.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)

    internal fun ownFile(tree: File, name: String): File {
        val file = File(tree, name)
        Files.deleteIfExists(file.toPath())
        return file
    }

    internal fun placeTree(tree: File, tools: File, name: String, forget: (String) -> Boolean): Boolean {
        val incoming = File(File(tools, INCOMING).apply { mkdirs() }, name)
        if (!deleteTree(incoming) || !tree.renameTo(incoming)) return false
        return finishPlacing(tools, name, forget)
    }

    private fun finishPlacing(tools: File, name: String, forget: (String) -> Boolean): Boolean {
        val incoming = File(File(tools, INCOMING), name)
        val final = File(tools, name)
        val backup = File(File(tools, BACKUPS).apply { mkdirs() }, name)
        val moved = !final.exists() || (deleteTree(backup) && final.renameTo(backup))
        val placed = moved && (!backup.exists() || forget(name)) && incoming.renameTo(final)
        if (!placed) {
            if (!final.exists()) backup.renameTo(final)
            deleteTree(incoming)
        }
        if (final.exists()) deleteTree(backup)
        return placed
    }

    internal fun restoreInterrupted(tools: File, forget: (String) -> Boolean) {
        File(tools, INCOMING).listFiles()?.forEach { finishPlacing(tools, it.name, forget) }
        File(tools, BACKUPS).listFiles()?.forEach { if (File(tools, it.name).exists()) deleteTree(it) else it.renameTo(File(tools, it.name)) }
    }

    internal fun archiveType(file: File): ArchiveType {
        val head = runCatching { file.inputStream().use { ByteArray(300).also { b -> it.read(b) } } }.getOrNull() ?: return ArchiveType.UNKNOWN
        fun at(i: Int, vararg bytes: Int) = bytes.indices.all { head.getOrNull(i + it) == bytes[it].toByte() }
        return when {
            at(0, 0x50, 0x4B) -> ArchiveType.ZIP
            at(0, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> ArchiveType.SEVEN_Z
            at(0, 0x1F, 0x8B) || at(0, 0x42, 0x5A, 0x68) || at(0, 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) || at(0, 0x28, 0xB5, 0x2F, 0xFD) || at(257, 0x75, 0x73, 0x74, 0x61, 0x72) -> ArchiveType.TAR
            else -> ArchiveType.UNKNOWN
        }
    }

    private fun extractTarball(zip: File, into: File): File? = ZipFile(zip).use { z ->
        val entries = z.entries().toList().filter { !it.isDirectory }
        val entry = entries.filter { TARBALL.containsMatchIn(it.name) }.maxByOrNull { it.size } ?: return null
        val name = File(entry.name).name
        val sums = checksumEntry(entries.map { it.name }, name)?.let { checksumName -> entries.first { it.name == checksumName } }
        val expected = sums?.let { e ->
            z.getInputStream(e).use { checksumIn(it.readBytes().decodeToString()) }
                ?: throw IllegalStateException("${File(e.name).name} holds no SHA-512 checksum")
        }
        val out = File(into, name)
        val digest = MessageDigest.getInstance("SHA-512")
        z.getInputStream(entry).use { input -> DigestOutputStream(FileOutputStream(out), digest).use { input.copyTo(it, 1 shl 16) } }
        if (expected != null && !expected.equals(digest.digest().joinToString("") { "%02x".format(it) }, ignoreCase = true)) {
            out.delete()
            throw IllegalStateException("Checksum mismatch in $name")
        }
        out
    }

    internal class UnsafeArchiveException : Exception()

    internal fun extractDirect(archive: File, into: File, type: ArchiveType) {
        val root = into.toPath().toAbsolutePath().normalize()
        fun target(name: String): File {
            val clean = name.replace('\\', '/')
            val parts = clean.split('/')
            if (clean.startsWith('/') || parts.any { it == ".." }) throw UnsafeArchiveException()
            val path = root.resolve(clean).normalize()
            if (!path.startsWith(root)) throw UnsafeArchiveException()
            var parent = path.parent
            while (parent != null && parent.startsWith(root) && parent != root) {
                if (Files.isSymbolicLink(parent)) throw UnsafeArchiveException()
                parent = parent.parent
            }
            return path.toFile()
        }
        fun applyMode(file: File, mode: Int) {
            val permissions = mutableSetOf<PosixFilePermission>()
            if (mode and 0x100 != 0) permissions += PosixFilePermission.OWNER_READ
            if (mode and 0x80 != 0) permissions += PosixFilePermission.OWNER_WRITE
            if (mode and 0x40 != 0) permissions += PosixFilePermission.OWNER_EXECUTE
            if (mode and 0x20 != 0) permissions += PosixFilePermission.GROUP_READ
            if (mode and 0x10 != 0) permissions += PosixFilePermission.GROUP_WRITE
            if (mode and 0x08 != 0) permissions += PosixFilePermission.GROUP_EXECUTE
            if (mode and 0x04 != 0) permissions += PosixFilePermission.OTHERS_READ
            if (mode and 0x02 != 0) permissions += PosixFilePermission.OTHERS_WRITE
            if (mode and 0x01 != 0) permissions += PosixFilePermission.OTHERS_EXECUTE
            if (permissions.isNotEmpty()) Files.setPosixFilePermissions(file.toPath(), permissions)
        }
        fun place(name: String, mode: Int, directory: Boolean, content: () -> InputStream) {
            val file = target(name)
            when {
                mode and 0xF000 == 0xA000 -> {
                    val link = content().use { it.readBytes().decodeToString() }
                    val parent = file.parentFile ?: throw UnsafeArchiveException()
                    val climbsLater = link.split('/').dropWhile { it == ".." }.any { it == ".." }
                    if (link.startsWith('/') || climbsLater || !parent.toPath().resolve(link).normalize().startsWith(root)) throw UnsafeArchiveException()
                    parent.mkdirs()
                    Files.deleteIfExists(file.toPath())
                    Files.createSymbolicLink(file.toPath(), java.nio.file.Paths.get(link))
                }
                directory -> {
                    file.mkdirs()
                    applyMode(file, mode)
                }
                else -> {
                    if (Files.isSymbolicLink(file.toPath())) throw UnsafeArchiveException()
                    file.parentFile?.mkdirs()
                    content().use { input -> file.outputStream().use { output -> input.copyTo(output, 1 shl 16) } }
                    applyMode(file, mode)
                }
            }
        }
        when (type) {
            ArchiveType.ZIP -> CommonsZipFile.builder().setFile(archive).get().use { zip ->
                for (entry in zip.entries) place(entry.name, entry.unixMode, entry.isDirectory) { zip.getInputStream(entry) }
            }
            ArchiveType.SEVEN_Z -> Files.newByteChannel(archive.toPath()).use { channel ->
                SevenZFile.builder().setSeekableByteChannel(channel).get().use { seven ->
                    for (entry in seven.entries) {
                        val unix = entry.hasWindowsAttributes && entry.windowsAttributes and 0x8000 != 0
                        place(entry.name, if (unix) entry.windowsAttributes ushr 16 else 0, entry.isDirectory) { seven.getInputStream(entry) }
                    }
                }
            }
            else -> error("Unsupported direct archive")
        }
    }

    internal fun checksumIn(text: String): String? = Regex("(?i)\\b[0-9a-f]{128}\\b").find(text)?.value

    internal fun checksumEntry(entries: List<String>, tarball: String): String? {
        val stem = tarball.substringBefore(".tar")
        return entries.firstOrNull {
            val name = File(it).name
            !TARBALL.containsMatchIn(name) && (name.startsWith("$tarball.") || name.startsWith("$stem.")) && name.contains("sha512", true)
        }
    }

    private fun runGuest(context: Context, command: List<String>): Int {
        val guest = listOf(
            "/usr/bin/env", "-i", "HOME=/root", "USER=root", "PATH=/usr/local/bin:/usr/bin:/bin",
            "LANG=C.UTF-8", "XDG_DATA_HOME=/root/.local/share",
        ) + command
        val child = ProtonExtras.guestProcess(context, guest).start()
        child.inputStream.bufferedReader().useLines { lines -> lines.forEach { Log.i(TAG, it) } }
        return child.waitFor()
    }
}
