package com.droiddeck.launcher.stores.download

import android.content.Context
import androidx.annotation.StringRes
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.SessionPrefs

/**
 * How hard the native download engine may pull: the ceiling of chunk requests it keeps in flight,
 * and the inflate / hash / write threads behind them. Three tiers, one app-wide setting (Setup ›
 * Stores and the Downloads page), read when a download starts so a change applies to the next one.
 *
 * The windows are the ones the engine was tuned with (16 / 32 / 96 requests): it ramps towards the
 * ceiling only while throughput keeps rising, so a slow link settles well below any of them.
 */
enum class StoreDownloadTier(val id: String, @StringRes val label: Int, val networkWindow: Int, val decompressPerCore: Double) {
    BALANCED("balanced", R.string.stores_tier_balanced, 16, 0.4),
    FAST("fast", R.string.stores_tier_fast, 32, 0.5),
    MAX("max", R.string.stores_tier_max, 96, 0.8);

    /** Inflate / hash / write workers for this device, at least two so a fetch never waits on a single writer. */
    /** Inflate / hash / write threads: by the tier, but two cores always left for the UI (at least 2 workers). */
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
