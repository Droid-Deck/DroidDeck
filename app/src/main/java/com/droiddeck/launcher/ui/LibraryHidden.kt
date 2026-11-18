package com.droiddeck.launcher.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.frontend.SteamHidden
import com.droiddeck.launcher.session.SessionPrefs

internal const val LIBRARY_HIDDEN = "hidden"

internal object LibraryHidden {
    var keys by mutableStateOf<Set<String>>(emptySet())
        private set
    var steam by mutableStateOf<Set<Long>>(emptySet())
        private set

    fun load(context: Context) {
        keys = SessionPrefs.libraryHidden(context)
        steam = SteamHidden.read(SessionPrefs.activeSteamRoot(context))
    }

    fun steamId(game: Library.SteamGame?): Long? = game?.takeIf { Library.fromSteamClient(it) }?.appId?.toLong()

    fun isHidden(key: String, game: Library.SteamGame?): Boolean = key in keys || steamId(game)?.let { it in steam } == true

    fun isHidden(entry: LibraryEntry): Boolean = isHidden(entry.favoriteKey, entry.game)

    fun set(context: Context, key: String, game: Library.SteamGame?, hidden: Boolean) {
        val id = steamId(game)
        val synced = id != null && SteamHidden.write(SessionPrefs.activeSteamRoot(context), id, hidden)
        if (synced && id != null) steam = if (hidden) steam + id else steam - id
        keys = if (hidden && !synced) keys + key else keys - key
        SessionPrefs.setLibraryHidden(context, keys)
    }
}
