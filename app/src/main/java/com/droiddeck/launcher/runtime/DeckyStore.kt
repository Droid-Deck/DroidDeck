package com.droiddeck.launcher.runtime

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.Downloader
import com.droiddeck.launcher.session.SessionState
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

object DeckyStore {
    private const val TAG = "DeckyStore"
    private const val LISTING = "https://plugins.deckbrew.xyz/plugins"
    private const val ARTIFACTS = "https://cdn.tzatzikiweeb.moe/file/steam-deck-homebrew/versions/"

    data class Version(val name: String, val hash: String)
    data class Plugin(val name: String, val author: String, val description: String, val tags: List<String>, val downloads: Long, val versions: List<Version>) {
        val latest: Version? get() = versions.firstOrNull()
    }
    data class Installed(val name: String, val folder: String, val author: String, val version: String)

    fun parseListing(body: String): List<Plugin> {
        val array = JSONArray(body)
        return (0 until array.length()).mapNotNull { i ->
            val row = array.optJSONObject(i) ?: return@mapNotNull null
            val name = row.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val versions = row.optJSONArray("versions")?.let { list ->
                (0 until list.length()).mapNotNull { v ->
                    val version = list.optJSONObject(v) ?: return@mapNotNull null
                    val hash = version.optString("hash")
                    if (!hash.matches(Regex("(?i)[0-9a-f]{64}"))) null else Version(version.optString("name"), hash.lowercase())
                }
            }.orEmpty()
            if (versions.isEmpty()) return@mapNotNull null
            val tags = row.optJSONArray("tags")?.let { list -> (0 until list.length()).map { list.optString(it) } }.orEmpty()
            val downloads = row.optJSONArray("versions")?.let { list -> (0 until list.length()).sumOf { list.optJSONObject(it)?.optLong("downloads") ?: 0L } } ?: 0L
            Plugin(name, row.optString("author"), row.optString("description"), tags, downloads, versions)
        }
    }

    fun fetch(): List<Plugin>? {
        val body = Downloader.downloadString(LISTING) ?: return null
        return runCatching { parseListing(body) }.onFailure { Log.w(TAG, "store listing", it) }.getOrNull()
    }

    fun pluginsDir(context: Context) = File(LinuxRuntime.rootDir(context), "root/homebrew/plugins")

    fun installed(context: Context): List<Installed> =
        pluginsDir(context).listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".droiddeck-") }
            .mapNotNull { folder ->
                runCatching {
                    val meta = JSONObject(File(folder, "plugin.json").readText())
                    val version = runCatching { JSONObject(File(folder, "package.json").readText()).optString("version") }.getOrDefault("")
                    Installed(meta.getString("name"), folder.name, meta.optString("author"), version)
                }.getOrNull()
            }
            .sortedBy { it.name.lowercase() }

    fun updateAvailable(installed: Installed, plugin: Plugin?): Boolean {
        val latest = plugin?.latest?.name ?: return false
        return installed.version.isNotEmpty() && latest != installed.version
    }

    fun install(context: Context, plugin: Plugin, version: Version, progress: (String, Int) -> Unit): String? {
        if (SessionState.running) return context.getString(R.string.deckymgr_stop_session_plugin)
        if (DeckyManager.installed(context) == null) return context.getString(R.string.deckymgr_loader_required)
        val cache = File(context.cacheDir, "decky-store").apply { mkdirs() }
        val zip = File(cache, "${version.hash}.zip")
        try {
            val label = context.getString(R.string.decky_store_downloading, plugin.name)
            progress(label, -1)
            val ok = Downloader.downloadFile("$ARTIFACTS${version.hash}.zip", zip, true, version.hash) { fraction ->
                progress(label, if (fraction < 0) -1 else (fraction * 100).toInt().coerceIn(0, 100))
            }
            if (!ok) return context.getString(R.string.decky_store_download_failed, plugin.name)
            return DeckyManager.installPluginZip(context, zip, progress)
        } finally {
            zip.delete()
        }
    }

    fun remove(context: Context, plugin: Installed): String? {
        if (SessionState.running) return context.getString(R.string.deckymgr_stop_session_plugin)
        val plugins = pluginsDir(context)
        val folder = File(plugins, plugin.folder)
        if (folder.parentFile?.canonicalFile != plugins.canonicalFile) return context.getString(R.string.decky_store_remove_failed)
        if (!folder.deleteRecursively()) return context.getString(R.string.decky_store_remove_failed)
        val settings = File(plugins.parentFile, "settings/loader.json")
        runCatching {
            val json = JSONObject(settings.readText())
            for (key in listOf("pluginOrder", "frozenPlugins", "hiddenPlugins", "disabled_plugins")) {
                val values = json.optJSONArray(key) ?: continue
                json.put(key, JSONArray((0 until values.length()).map { values.get(it) }.filter { it != plugin.name }))
            }
            settings.writeText(json.toString(4))
        }
        return null
    }
}
