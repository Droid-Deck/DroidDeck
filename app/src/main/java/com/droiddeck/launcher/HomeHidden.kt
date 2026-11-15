package com.droiddeck.launcher

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object HomeHidden {
    private const val PREFS = "home_launcher"
    private const val KEY = "hidden"

    var keys by mutableStateOf<Set<String>>(emptySet())
        private set
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        keys = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()).orEmpty().toSet()
    }

    fun set(context: Context, key: String, hidden: Boolean) {
        keys = if (hidden) keys + key else keys - key
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, keys).apply()
    }
}
