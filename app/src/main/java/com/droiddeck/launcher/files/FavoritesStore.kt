package com.droiddeck.launcher.files

import android.content.Context
import org.json.JSONArray

object FavoritesStore {

    private const val PREFS_NAME = "file_manager_prefs"
    private const val KEY = "file_manager_favorites"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun list(context: Context): List<String> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList { for (i in 0 until arr.length()) add(arr.getString(i)) }
        }.getOrDefault(emptyList())
    }

    fun isFavorite(context: Context, path: String): Boolean = list(context).contains(path)

    fun add(context: Context, path: String) {
        val current = list(context)
        if (current.contains(path)) return
        save(context, current + path)
    }

    fun remove(context: Context, path: String) {
        val current = list(context)
        if (!current.contains(path)) return
        save(context, current.filterNot { it == path })
    }

    fun toggle(context: Context, path: String): Boolean =
        if (isFavorite(context, path)) {
            remove(context, path); false
        } else {
            add(context, path); true
        }

    private fun save(context: Context, paths: List<String>) {
        val arr = JSONArray()
        paths.forEach { arr.put(it) }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }
}
