package com.droiddeck.launcher.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.session.SessionPrefs

internal const val LIBRARY_FAVORITES = "favorites"

internal object LibraryFavorites {
    var keys by mutableStateOf<Set<String>>(emptySet())
        private set
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        keys = SessionPrefs.libraryFavorites(context)
    }

    fun toggle(context: Context, key: String) {
        load(context)
        keys = toggled(keys, key)
        SessionPrefs.setLibraryFavorites(context, keys)
    }
}

internal fun toggled(keys: Set<String>, key: String): Set<String> = if (key in keys) keys - key else keys + key

internal fun inLibrarySource(source: String, entry: LibraryEntry, favorites: Set<String>): Boolean = when (source) {
    LIBRARY_ALL -> true
    LIBRARY_FAVORITES -> entry.favoriteKey in favorites
    else -> entry.source == source
}
