package com.droiddeck.launcher.session

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.droiddeck.launcher.R
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import org.json.JSONObject

object ExperimentalProtons {
    private const val TAG = "ExperimentalProtons"
    const val PREFIX = "Experimental-"
    private const val MARKER = ".droiddeck-experimental.json"
    private const val STAGING = ".droiddeck-experimental"
    private const val BACKUPS = ".droiddeck-experimental-old"
    private const val GUEST_TOOLS = "/root/.local/share/Steam/compatibilitytools.d"
    private const val NEED_BYTES = 4L * 1024 * 1024 * 1024
    private const val USER_SETTINGS = "user_settings.py"
    private val TARBALL = Regex("(?i)\\.tar(\\.(gz|xz|zst|bz2))?$")

    data class Installed(val dir: String, val label: String, val source: String)

    private fun toolsDir(context: Context) = File(LinuxRuntime.rootDir(context), GUEST_TOOLS.removePrefix("/"))

    fun installed(context: Context): List<Installed> =
        toolsDir(context).listFiles()
            ?.filter { it.isDirectory && File(it, MARKER).isFile }
            ?.map { dir ->
                val info = runCatching { JSONObject(File(dir, MARKER).readText()) }.getOrDefault(JSONObject())
                Installed(dir.name, info.optString("label").ifEmpty { dir.name }, info.optString("source"))
            }
            ?.sortedBy { it.dir }
            ?: emptyList()

    fun remove(context: Context, dir: String): Boolean {
        val tools = toolsDir(context)
        val target = File(tools, dir)
        if (!File(target, MARKER).isFile) return false
        val gone = deleteTree(target) && deleteTree(File(File(tools, BACKUPS), dir))
        if (gone) ComponentsManager.forgetProton(context, dir)
        return gone
    }

    internal fun dirName(tree: String, label: String?): String {
        val base = ComponentsManager.safeName(tree)
        val tag = label?.let(ComponentsManager::safeName)?.takeIf { it != "unnamed" && !base.contains(it, ignoreCase = true) }
        return PREFIX + base + (tag?.let { "-$it" } ?: "")
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

    fun installFromFile(context: Context, zip: File, onProgress: (String, Int) -> Unit): String? {
        preflight(context)?.let { return it }
        if (StatFs(LinuxRuntime.rootDir(context).path).availableBytes < NEED_BYTES + zip.length()) {
            return context.getString(R.string.xproton_no_space)
        }
        return install(context, zip, zip.name.substringBeforeLast('.'), zip.name, onProgress)
    }

    private fun preflight(context: Context): String? = when {
        SessionState.running -> context.getString(R.string.pextra_stop_session)
        !LinuxRuntime.isInstalled(context) -> context.getString(R.string.user_apps_runtime_required)
        else -> null
    }

    private fun install(context: Context, zip: File, label: String, source: String, onProgress: (String, Int) -> Unit): String? {
        val tools = toolsDir(context).apply { mkdirs() }
        val staging = File(tools, STAGING)
        val backups = File(tools, BACKUPS)
        restoreInterrupted(tools, backups)
        deleteTree(staging)
        staging.mkdirs()
        try {
            LinuxRuntime.writeAccounts(context)
            SessionFiles.stage(context, LinuxRuntime.rootDir(context))
            onProgress(context.getString(R.string.xproton_unzipping), -1)
            val tarball = runCatching { extractTarball(zip, staging) }
                .getOrElse { return it.message ?: context.getString(R.string.xproton_bad_zip) }
                ?: return context.getString(R.string.xproton_bad_zip)
            if (SessionState.running) return context.getString(R.string.pextra_session_started)

            onProgress(context.getString(R.string.xproton_unpacking), -1)
            val unpacked = File(staging, "unpacked").apply { mkdirs() }
            val status = runGuest(context, listOf("tar", "-xf", "$GUEST_TOOLS/$STAGING/${tarball.name}", "-C", "$GUEST_TOOLS/$STAGING/unpacked"))
            tarball.delete()
            if (status != 0) return context.getString(R.string.xproton_untar_failed, status)

            val tree = buildTree(unpacked) ?: return context.getString(R.string.xproton_no_tree)
            val name = dirName(tree.name, label)
            ownFile(tree, "compatibilitytool.vdf").writeText(compatVdf(name))
            ownFile(tree, MARKER).writeText(
                JSONObject().put("label", name).put("source", source).put("at", System.currentTimeMillis() / 1000).toString(),
            )
            val final = File(tools, name)
            val settings = File(final, USER_SETTINGS)
            if (Files.isRegularFile(settings.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                settings.copyTo(ownFile(tree, USER_SETTINGS))
            }
            val replaced = final.exists()
            if (!placeTree(tree, final, backups)) return context.getString(R.string.xproton_place_failed)
            if (replaced) ComponentsManager.forgetProton(context, name, replaced = true)

            onProgress(context.getString(R.string.pextra_installing, name), -1)
            runGuest(context, listOf("steam-compatibility", "/root/.local/share/Steam"))
            return null
        } catch (e: Exception) {
            Log.e(TAG, "install $source", e)
            return e.message ?: context.getString(R.string.xproton_bad_zip)
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

    internal fun placeTree(tree: File, final: File, backups: File): Boolean {
        val backup = File(backups.apply { mkdirs() }, final.name)
        if (final.exists() && !final.renameTo(backup)) return false
        if (!tree.renameTo(final)) {
            backup.renameTo(final)
            return false
        }
        if (backup.exists()) deleteTree(backup)
        return true
    }

    internal fun restoreInterrupted(tools: File, backups: File) {
        backups.listFiles()?.forEach { if (File(tools, it.name).exists()) deleteTree(it) else it.renameTo(File(tools, it.name)) }
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
