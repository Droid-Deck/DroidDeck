package com.droiddeck.launcher

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object HomeLibrary {
    private const val PREFS = "home_launcher"
    private const val ADDED = "library_added"
    private const val REMOVED = "library_removed"

    var added by mutableStateOf<Set<String>>(emptySet())
        private set
    var removed by mutableStateOf<Set<String>>(emptySet())
        private set
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        added = p.getStringSet(ADDED, emptySet()).orEmpty().toSet()
        removed = p.getStringSet(REMOVED, emptySet()).orEmpty().toSet()
    }

    fun includes(app: HomeApp.LaunchableApp, added: Set<String> = this.added, removed: Set<String> = this.removed): Boolean =
        app.key !in removed && (app.game || app.key in added)

    fun changed(added: Set<String>, removed: Set<String>, app: HomeApp.LaunchableApp, inLibrary: Boolean): Pair<Set<String>, Set<String>> =
        (if (inLibrary && !app.game) added + app.key else added - app.key) to
            (if (!inLibrary && app.game) removed + app.key else removed - app.key)

    fun set(context: Context, app: HomeApp.LaunchableApp, inLibrary: Boolean) {
        val (a, r) = changed(added, removed, app, inLibrary)
        added = a
        removed = r
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(ADDED, a).putStringSet(REMOVED, r).apply()
    }
}
