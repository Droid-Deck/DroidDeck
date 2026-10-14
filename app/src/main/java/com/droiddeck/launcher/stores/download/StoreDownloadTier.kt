package com.droiddeck.launcher.stores.download

import android.content.Context
import androidx.annotation.StringRes
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.SessionPrefs

enum class StoreDownloadTier(val id: String, @StringRes val label: Int, val networkWindow: Int, val decompressPerCore: Double) {
    BALANCED("balanced", R.string.stores_tier_balanced, 16, 0.4),
    FAST("fast", R.string.stores_tier_fast, 32, 0.5),
    MAX("max", R.string.stores_tier_max, 96, 0.8);

    val processWorkers: Int get() {
        val cores = Runtime.getRuntime().availableProcessors()
        return (cores * decompressPerCore).toInt().coerceIn(2, maxOf(2, minOf(32, cores - 2)))
    }

    companion object {
        val ALL: List<StoreDownloadTier> = entries
        fun byId(id: String?): StoreDownloadTier = entries.firstOrNull { it.id == id } ?: FAST
        fun current(context: Context): StoreDownloadTier = byId(SessionPrefs.gameStoresSpeedTier(context))
    }
}
