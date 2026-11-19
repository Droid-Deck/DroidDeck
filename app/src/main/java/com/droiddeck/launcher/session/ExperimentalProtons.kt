package com.droiddeck.launcher.session

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.Downloader
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import java.io.FileOutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject

object ExperimentalProtons {
    private const val TAG = "ExperimentalProtons"
    const val PREFIX = "Experimental-"
    private const val MARKER = ".droiddeck-experimental.json"
    private const val STAGING = ".droiddeck-experimental"
    private const val GUEST_TOOLS = "/root/.local/share/Steam/compatibilitytools.d"
    private const val NEED_BYTES = 4L * 1024 * 1024 * 1024
    private val TARBALL = Regex("(?i)\\.tar(\\.(gz|xz|zst|bz2))?$")
    private val NOT_BUILDS = Regex("(?i)^(make-log|build-info|state-|logs?\\b)")
    private val RUN_URL = Regex("(?i)github\\.com/([\\w.-]+)/([\\w.-]+)/actions/runs/(\\d+)(?:/artifacts/(\\d+))?")

    data class Installed(val dir: String, val label: String, val source: String)

    internal data class RunLink(val owner: String, val repo: String, val run: Long, val artifact: Long?)

    internal data class Artifact(val id: Long, val name: String, val size: Long)

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

    internal fun parseRunLink(url: String): RunLink? {
        val m = RUN_URL.find(url.trim()) ?: return null
        return RunLink(m.groupValues[1], m.groupValues[2], m.groupValues[3].toLong(), m.groupValues[4].toLongOrNull())
    }

    internal fun pickArtifact(artifacts: JSONArray, wanted: Long?): Artifact? {
        val all = (0 until artifacts.length()).map { artifacts.getJSONObject(it) }
            .filter { !it.optBoolean("expired", false) }
            .map { Artifact(it.optLong("id"), it.optString("name"), it.optLong("size_in_bytes")) }
        if (wanted != null) return all.firstOrNull { it.id == wanted }
        return all.filter { !NOT_BUILDS.containsMatchIn(it.name) }
            .sortedWith(compareByDescending<Artifact> { it.name.contains("proton", ignoreCase = true) }.thenByDescending { it.size })
            .firstOrNull()
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

    fun installFromGitHub(context: Context, url: String, onProgress: (String, Int) -> Unit): String? {
        val link = parseRunLink(url) ?: return context.getString(R.string.xproton_bad_link)
        preflight(context)?.let { return it }
        onProgress(context.getString(R.string.xproton_looking), -1)
        val api = "https://api.github.com/repos/${link.owner}/${link.repo}/actions/runs/${link.run}"
        val branch = runCatching { JSONObject(Downloader.downloadString(api) ?: "{}").optString("head_branch") }.getOrDefault("")
        val listing = Downloader.downloadString("$api/artifacts?per_page=100")
            ?: return context.getString(R.string.xproton_run_unreadable)
        val artifact = runCatching { pickArtifact(JSONObject(listing).optJSONArray("artifacts") ?: JSONArray(), link.artifact) }.getOrNull()
            ?: return context.getString(R.string.xproton_no_artifact)
        if (StatFs(LinuxRuntime.rootDir(context).path).availableBytes < NEED_BYTES + artifact.size * 2) {
            return context.getString(R.string.xproton_no_space)
        }
        val downloads = File(context.filesDir, "proton-downloads").apply { mkdirs() }
        val zip = File(downloads, "experimental-${link.run}-${artifact.id}.zip")
        if (artifact.size > 0 && zip.length() > artifact.size) zip.delete()
        val source = "https://nightly.link/${link.owner}/${link.repo}/actions/runs/${link.run}/${artifact.name}.zip"
        val downloading = context.getString(R.string.xproton_downloading, artifact.name)
        onProgress(downloading, 0)
        var ok = false
        for (attempt in 0 until 3) {
            ok = Downloader.downloadFile(source, zip, true) { f -> onProgress(downloading, if (f < 0) -1 else (f * 100f).toInt().coerceIn(0, 100)) }
            if (ok) break
        }
        if (!ok) return context.getString(R.string.pextra_download_failed)
        return try {
            install(context, zip, branch.ifEmpty { "run-${link.run}" }, "${link.owner}/${link.repo} run ${link.run}", onProgress)
        } finally {
            zip.delete()
        }
    }

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
