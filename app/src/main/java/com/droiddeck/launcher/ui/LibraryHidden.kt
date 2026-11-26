package com.droiddeck.launcher.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.frontend.AddedGames
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.frontend.SteamHidden
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.Store

internal const val LIBRARY_HIDDEN = "hidden"
internal val HIDDEN_DOT = androidx.compose.ui.graphics.Color(0xFF9AA0A6)

internal object LibraryHidden {
    var keys by mutableStateOf<Set<String>>(emptySet())
        private set
    var account by mutableStateOf<java.io.File?>(null)
        private set
    var steam by mutableStateOf<Set<Long>>(emptySet())
        private set

    fun load(context: Context) {
        keys = SessionPrefs.libraryHidden(context)
        refreshSteam(context)
    }

    fun refreshSteam(context: Context) {
        val root = SessionPrefs.activeSteamRoot(context)
        adopt(root)
        SteamHidden.readOrNull(root)?.let { steam = it }
    }

    private fun adopt(root: java.io.File) {
        val resolved = AddedGames.accountConfig(root)
        if (resolved != account) {
            account = resolved
            steam = emptySet()
        }
    }

    /** The Steam client's own id for a game it lists, or for an owned Steam game it has not installed ([key] `steamstore:<appid>`). */
    fun steamId(game: Library.SteamGame?, key: String = ""): Long? =
        game?.takeIf { Library.fromSteamClient(it) }?.appId?.toLong()
            ?: key.takeIf { game == null && it.startsWith("${Store.STEAM.id}:") }?.substringAfter(':')?.toLongOrNull()

    fun isHidden(key: String, game: Library.SteamGame?): Boolean = key in keys || steamId(game, key)?.let { it in steam } == true

    fun isHidden(entry: LibraryEntry): Boolean =
        isHidden(entry.favoriteKey, entry.game) || entry.item?.takeIf { it.store == Store.STEAM }?.let { it.key in keys || it.id.toLongOrNull()?.let { id -> id in steam } == true } == true

    fun set(context: Context, key: String, game: Library.SteamGame?, hidden: Boolean): Boolean {
        val id = steamId(game, key)
        val root = SessionPrefs.activeSteamRoot(context)
        val written = id?.let { SteamHidden.write(root, it, hidden) }
        if (id != null) {
            adopt(root)
            steam = written?.hidden ?: SteamHidden.readOrNull(root) ?: steam
        }
        keys = if (hidden && (id == null || written == null)) keys + key else keys - key
        SessionPrefs.setLibraryHidden(context, keys)
        return id == null || written?.queued == true
    }
}

@Composable
internal fun rememberHiddenSync() {
    val ctx = LocalContext.current
    remember { LibraryHidden.load(ctx) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) LibraryHidden.refreshSteam(ctx) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}
