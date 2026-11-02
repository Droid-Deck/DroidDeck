package com.droiddeck.launcher.stores.xbox

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.stores.CatalogItem

object XboxLibraryFilter {
    const val ALL = "all"
    const val OWNED = "owned"
    const val GAME_PASS = "gamepass"
    const val CLOUD = "cloud"
    val ALL_FILTERS = listOf(ALL, OWNED, GAME_PASS, CLOUD)

    var current by mutableStateOf(ALL)
        private set

    fun load(context: Context) {
        current = context.getSharedPreferences("xbox", Context.MODE_PRIVATE).getString("libraryFilter", ALL)?.takeIf { it in ALL_FILTERS } ?: ALL
    }

    fun set(context: Context, filter: String) {
        current = filter
        context.getSharedPreferences("xbox", Context.MODE_PRIVATE).edit().putString("libraryFilter", filter).apply()
    }

    fun keeps(filter: String, item: CatalogItem): Boolean = when (filter) {
        OWNED -> item.owned
        GAME_PASS -> item.subscription
        CLOUD -> item.xboxCloud
        else -> true
    }

    fun label(filter: String): String = when (filter) {
        OWNED -> "Owned"
        GAME_PASS -> "Game Pass"
        CLOUD -> "Cloud"
        else -> "All"
    }
}
