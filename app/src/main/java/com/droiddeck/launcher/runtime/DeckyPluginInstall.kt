package com.droiddeck.launcher.runtime

import androidx.annotation.StringRes
import com.droiddeck.launcher.R
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Offline equivalent of Decky's identity-based replacement and loader settings update. */
internal object DeckyPluginInstall {
    /** A failure the user is told about: [text] is its string resource. */
    class Failure(@StringRes val text: Int) : IllegalStateException()

    fun activate(staged: File, plugins: File, name: String, settingsFile: File, backupSuffix: String) {
        val settings = if (settingsFile.exists()) JSONObject(settingsFile.readText()) else JSONObject()
        val existing = plugins.listFiles().orEmpty().filter { folder ->
            folder.isDirectory && !folder.name.startsWith(".droiddeck-") && runCatching {
                JSONObject(File(folder, "plugin.json").readText()).getString("name") == name
            }.getOrDefault(false)
        }
        val target = File(plugins, staged.name)
        if (target.exists() && target !in existing) throw Failure(R.string.deckymgr_folder_taken)
        val order = settings.optJSONArray("pluginOrder") ?: JSONArray(plugins.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".droiddeck-") }
            .mapNotNull { runCatching { JSONObject(File(it, "plugin.json").readText()).getString("name") }.getOrNull() }
            .distinct())
        if ((0 until order.length()).none { order.optString(it) == name }) order.put(name)
        settings.put("pluginOrder", order)
        // Decky's reinstall removes these flags while retaining the plugin's order and data.
        for (key in listOf("frozenPlugins", "hiddenPlugins", "disabled_plugins")) {
            val values = settings.optJSONArray(key) ?: continue
            settings.put(key, JSONArray((0 until values.length()).map { values.get(it) }.filter { it != name }))
        }
        settingsFile.parentFile?.mkdirs()
        val pending = File(settingsFile.parentFile, "loader.json$backupSuffix")
        val backups = mutableListOf<Pair<File, File>>()
        var activated = false
        try {
            pending.writeText(settings.toString(4))
            for (old in existing) {
                val backup = File(plugins, ".droiddeck-backup-${old.name}$backupSuffix")
                if (!old.renameTo(backup)) throw Failure(R.string.deckymgr_preserve_failed)
                backups.add(old to backup)
            }
            if (!staged.renameTo(target)) throw Failure(R.string.deckymgr_activate_failed)
            activated = true
            if (!pending.renameTo(settingsFile)) throw Failure(R.string.deckymgr_settings_failed)
        } catch (error: Exception) {
            if (activated) target.deleteRecursively()
            for ((old, backup) in backups.asReversed()) if (!backup.renameTo(old)) throw Failure(R.string.deckymgr_restore_failed)
            throw error
        } finally {
            pending.delete()
        }
        backups.forEach { (_, backup) -> backup.deleteRecursively() }
    }
}
