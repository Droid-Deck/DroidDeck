package com.droiddeck.launcher

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object HomeFolders {
    data class Folder(val id: String, val name: String, val apps: List<String>)

    private const val PREFS = "home_launcher"
    private const val KEY = "folders"

    var folders by mutableStateOf<List<Folder>>(emptyList())
        private set
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        folders = runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                val apps = o.getJSONArray("apps")
                Folder(o.getString("id"), o.getString("name"), (0 until apps.length()).map { apps.getString(it) })
            }
        }.getOrDefault(emptyList())
    }

    fun create(context: Context, name: String, appKey: String): Folder {
        val folder = Folder(UUID.randomUUID().toString(), name, listOf(appKey))
        update(context, without(appKey) + folder)
        return folder
    }

    fun add(context: Context, folderId: String, appKey: String) =
        update(context, without(appKey).map { if (it.id == folderId) it.copy(apps = it.apps + appKey) else it })

    fun remove(context: Context, appKey: String) = update(context, without(appKey))

    fun rename(context: Context, folderId: String, name: String) =
        update(context, folders.map { if (it.id == folderId) it.copy(name = name) else it })

    fun delete(context: Context, folderId: String) = update(context, folders.filterNot { it.id == folderId })

    fun migrateMembers(context: Context, keyFor: (String) -> String?) {
        if (folders.none { f -> f.apps.any { '/' !in it } }) return
        update(context, folders.map { f -> f.copy(apps = f.apps.map { if ('/' in it) it else keyFor(it) ?: it }.distinct()) })
    }

    private fun without(appKey: String): List<Folder> =
        folders.map { it.copy(apps = it.apps - appKey) }.filter { it.apps.isNotEmpty() }

    private fun update(context: Context, next: List<Folder>) {
        folders = next
        val array = JSONArray()
        for (f in next) array.put(JSONObject().put("id", f.id).put("name", f.name).put("apps", JSONArray(f.apps)))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }
}
