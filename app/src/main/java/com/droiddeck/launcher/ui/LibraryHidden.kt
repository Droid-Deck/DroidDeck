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
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.frontend.SteamHidden
import com.droiddeck.launcher.session.SessionPrefs

internal const val LIBRARY_HIDDEN = "hidden"

internal object LibraryHidden {
    var keys by mutableStateOf<Set<String>>(emptySet())
        private set
    private var cachedRoot: java.io.File? = null
    var steam by mutableStateOf<Set<Long>>(emptySet())
        private set

    fun load(context: Context) {
        keys = SessionPrefs.libraryHidden(context)
        refreshSteam(context)
    }

    fun refreshSteam(context: Context) {
        val root = SessionPrefs.activeSteamRoot(context)
        if (root != cachedRoot) {
            cachedRoot = root
            steam = emptySet()
        }
        SteamHidden.readOrNull(root)?.let { steam = it }
    }

    fun steamId(game: Library.SteamGame?): Long? = game?.takeIf { Library.fromSteamClient(it) }?.appId?.toLong()

    fun isHidden(key: String, game: Library.SteamGame?): Boolean = key in keys || steamId(game)?.let { it in steam } == true

    fun isHidden(entry: LibraryEntry): Boolean = isHidden(entry.favoriteKey, entry.game)

    fun set(context: Context, key: String, game: Library.SteamGame?, hidden: Boolean): Boolean {
        val id = steamId(game)
        val root = SessionPrefs.activeSteamRoot(context)
        val written = id?.let { SteamHidden.write(root, it, hidden) }
        if (id != null) {
            cachedRoot = root
            steam = written ?: SteamHidden.readOrNull(root) ?: steam
        }
        keys = if (hidden && written == null) keys + key else keys - key
        SessionPrefs.setLibraryHidden(context, keys)
        return id == null || written != null
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
