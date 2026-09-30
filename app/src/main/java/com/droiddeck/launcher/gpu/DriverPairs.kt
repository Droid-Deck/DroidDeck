package com.droiddeck.launcher.gpu

import com.droiddeck.launcher.gpu.GpuInfo.Family

/**
 * The release drivers as matched pairs: a display build (the app's compositor, bionic) and a Linux
 * build (the runtime, glibc) that were made to run together. Mixing a runtime driver carrying a
 * patch with a display driver that lacks it is where the hard-to-read bugs come from, so the app
 * installs and selects both halves of one pair at once.
 *
 * - Banners-Turnip publishes each variant as a set in every release (`Turnip-<tag><variant>.zip`
 *   and `...-Linux.zip`), rebuilt from upstream Mesa as it moves: the pair is one variant of the
 *   newest release that carries both halves.
 * - WinNative ships its display (`v1.x`) and Linux (`linux-v0.x`) builds as two release lines with
 *   their own numbers: the pair is the newest of each, of one flavour (Balanced or Performance).
 * - DroidDeck's DD-Turnip ships both halves in one zip per release (DriverBundle): the pair is the
 *   newest bundle, and it can never be mixed.
 */
object DriverPairs {
    const val BANNER = "banner"
    const val BANNER_A8XX = "banner-a8xx"
    const val BANNER_710 = "banner-710-720-test"
    const val BANNER_ONEUI = "banner-8g2-oneui"
    const val WN_BALANCED = "wn-b"
    const val WN_PERFORMANCE = "wn-p"
    const val DD_TURNIP = "dd-turnip"

    class DriverPair(
        val key: String,
        /** "Banners-Turnip · Adreno 6xx/7xx", "WinNative · Balanced". */
        val name: String,
        /** The families the pair is built for; the list only offers it on those unless asked. */
        val families: Set<Family>,
        val display: TurnipReleases.Asset?,
        val linux: TurnipReleases.Asset?,
        /** Both halves in one zip, instead of [display] and [linux]. */
        val bundle: TurnipReleases.Asset? = null,
    ) {
        val complete: Boolean get() = bundle != null || (display != null && linux != null)
        /** The zips to download for the pair: the bundle, or the two halves. */
        val assets: List<TurnipReleases.Asset> get() = listOfNotNull(bundle ?: display, if (bundle == null) linux else null)
        /** "r4 · 1.19 + 0.1.2": the build of each half. */
        val version: String
            get() = listOfNotNull(bundle?.tag, display?.tag, linux?.tag).distinct().joinToString(" + ") { it.removePrefix("linux-").removePrefix("DD-Turnip-") }
        fun suits(gpu: GpuInfo) = gpu.family in families ||
            (gpu.family == Family.ADRENO_UNKNOWN && (Family.A7XX in families || Family.A8XX in families))
    }

    private val ALL_ADRENO = setOf(Family.A6XX, Family.A7XX, Family.A7XX_LOW, Family.A8XX, Family.ADRENO_UNKNOWN)

    private fun describe(key: String): Pair<String, Set<Family>> = when (key) {
        BANNER -> "Banners-Turnip · Adreno 6xx/7xx" to setOf(Family.A6XX, Family.A7XX)
        BANNER_A8XX -> "Banners-Turnip · Adreno 8xx" to setOf(Family.A8XX)
        BANNER_710 -> "Banners-Turnip · Adreno 710/720/722 (test)" to setOf(Family.A7XX_LOW)
        BANNER_ONEUI -> "Banners-Turnip · 8 Gen 2 on One UI" to setOf(Family.A7XX)
        WN_BALANCED -> "WinNative · Balanced" to ALL_ADRENO
        WN_PERFORMANCE -> "WinNative · Performance" to ALL_ADRENO
        DD_TURNIP -> "DroidDeck · DD-Turnip" to ALL_ADRENO
        else -> key to ALL_ADRENO
    }

    /** Every pair the last release check found, in the order the list shows them. */
    fun from(check: TurnipReleases.Check?): List<DriverPair> {
        val assets = check?.assets.orEmpty().filter { it.pair.isNotEmpty() }
        val order = listOf(DD_TURNIP, BANNER, BANNER_A8XX, BANNER_ONEUI, BANNER_710, WN_BALANCED, WN_PERFORMANCE)
        return assets.map { it.pair }.distinct()
            .sortedBy { order.indexOf(it).let { i -> if (i < 0) order.size else i } }
            .map { key ->
                val (name, families) = describe(key)
                val halves = assets.filter { it.pair == key }
                halves.firstOrNull { it.bundle }?.let { return@map DriverPair(key, name, families, null, null, it) }
                // The list keeps the newest release of each half; for Banners-Turnip the two halves
                // must come from the same release, so a set missing one half in its newest release
                // is offered incomplete rather than mixed with an older one.
                val display = halves.firstOrNull { !it.linux }
                val linux = halves.firstOrNull { it.linux }
                val sameRelease = !key.startsWith(BANNER) || display?.tag == linux?.tag
                DriverPair(key, name, families, display, if (sameRelease) linux else null)
            }
    }

    /**
     * The pair Auto sets for [gpu]: WinNative Balanced on the 8xx (Banners' A8xx builds still break
     * on some first boots), the OneUI build for an 8 Gen 2 under One UI, the patched test builds for
     * the 710/720/722, and upstream Banners-Turnip for the rest. Null when nothing fits (not Adreno).
     */
    fun recommendedKey(gpu: GpuInfo): String? = when (gpu.family) {
        Family.NOT_ADRENO -> null
        Family.A8XX -> WN_BALANCED
        Family.A7XX_LOW -> BANNER_710
        Family.A7XX -> if (gpu.oneUi8Gen2) BANNER_ONEUI else BANNER
        Family.A6XX -> BANNER
        // KGSL gave no model: Android 16 arrived with the 8xx, so a device that new is taken as one.
        Family.ADRENO_UNKNOWN -> if (android.os.Build.VERSION.SDK_INT >= 36) WN_BALANCED else BANNER
    }
}
