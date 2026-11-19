package com.droiddeck.launcher.session

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.droiddeck.launcher.R
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import java.io.FileOutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.json.JSONObject

object ExperimentalProtons {
    private const val TAG = "ExperimentalProtons"
    const val PREFIX = "Experimental-"
    private const val MARKER = ".droiddeck-experimental.json"
    private const val STAGING = ".droiddeck-experimental"
    private const val GUEST_TOOLS = "/root/.local/share/Steam/compatibilitytools.d"
    private const val NEED_BYTES = 4L * 1024 * 1024 * 1024
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
        val target = File(toolsDir(context), dir)
        if (!File(target, MARKER).isFile) return false
        val gone = target.deleteRecursively()
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
        staging.deleteRecursively()
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

            val tree = unpacked.listFiles()?.firstOrNull { it.isDirectory && File(it, "toolmanifest.vdf").isFile }
                ?: return context.getString(R.string.xproton_no_tree)
            val name = dirName(tree.name, label)
            File(tree, "compatibilitytool.vdf").writeText(compatVdf(name))
            File(tree, MARKER).writeText(
                JSONObject().put("label", name).put("source", source).put("at", System.currentTimeMillis() / 1000).toString(),
            )
            val final = File(tools, name)
            if (final.exists()) {
                final.deleteRecursively()
                ComponentsManager.forgetProton(context, name)
            }
            if (!tree.renameTo(final)) return context.getString(R.string.xproton_place_failed)

            onProgress(context.getString(R.string.pextra_installing, name), -1)
            runGuest(context, listOf("steam-compatibility", "/root/.local/share/Steam"))
            return null
        } catch (e: Exception) {
            Log.e(TAG, "install $source", e)
            return e.message ?: context.getString(R.string.xproton_bad_zip)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun extractTarball(zip: File, into: File): File? = ZipFile(zip).use { z ->
        val entries = z.entries().toList().filter { !it.isDirectory }
        val entry = entries.filter { TARBALL.containsMatchIn(it.name) }.maxByOrNull { it.size } ?: return null
        val name = File(entry.name).name
        val expected = entries.firstOrNull { File(it.name).name.startsWith(name.substringBefore(".tar")) && it.name.contains("sha512", true) }
            ?.let { e -> z.getInputStream(e).use { Regex("(?i)\\b[0-9a-f]{128}\\b").find(it.readBytes().decodeToString())?.value } }
        val out = File(into, name)
        val digest = MessageDigest.getInstance("SHA-512")
        z.getInputStream(entry).use { input -> DigestOutputStream(FileOutputStream(out), digest).use { input.copyTo(it, 1 shl 16) } }
        if (expected != null && !expected.equals(digest.digest().joinToString("") { "%02x".format(it) }, ignoreCase = true)) {
            out.delete()
            throw IllegalStateException("Checksum mismatch in $name")
        }
        out
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
