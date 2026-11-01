package com.droiddeck.launcher.gpu

import com.droiddeck.launcher.gpu.GpuInfo.Family

object DriverPairs {
    const val BANNER = "banner"
    const val BANNER_A8XX = "banner-a8xx"
    const val BANNER_710 = "banner-710-720-test"
    const val BANNER_ONEUI = "banner-8g2-oneui"
    const val WN_BALANCED = "wn-b"
    const val WN_PERFORMANCE = "wn-p"
    const val DD_TURNIP = "dd-turnip"
    fun ddTurnip(tag: String) = "$DD_TURNIP:$tag"

    internal data class DdTurnipVersion(val major: Long, val minor: Long, val patch: Long) : Comparable<DdTurnipVersion> {
        override fun compareTo(other: DdTurnipVersion): Int {
            major.compareTo(other.major).takeIf { it != 0 }?.let { return it }
            minor.compareTo(other.minor).takeIf { it != 0 }?.let { return it }
            return patch.compareTo(other.patch)
        }
    }

    private val DD_TURNIP_VERSION = Regex("""(\d+)\.(\d+)\.(\d+)$""")

    internal fun ddTurnipVersion(pairKind: String): DdTurnipVersion? {
        val match = DD_TURNIP_VERSION.find(pairKind) ?: return null
        val (major, minor, patch) = match.destructured
        return DdTurnipVersion(
            major.toLongOrNull() ?: return null,
            minor.toLongOrNull() ?: return null,
            patch.toLongOrNull() ?: return null,
        )
    }

    class DriverPair(
        val key: String,
        val name: String,
        val families: Set<Family>,
        val display: TurnipReleases.Asset?,
        val linux: TurnipReleases.Asset?,
        val bundle: TurnipReleases.Asset? = null,
    ) {
        val complete: Boolean get() = bundle != null || (display != null && linux != null)
        val assets: List<TurnipReleases.Asset> get() = listOfNotNull(bundle ?: display, if (bundle == null) linux else null)
        val version: String
            get() = listOfNotNull(bundle?.tag, display?.tag, linux?.tag).distinct().joinToString(" + ") { it.removePrefix("linux-").removePrefix("DD-Turnip-") }
        fun suits(gpu: GpuInfo) = gpu.family in families ||
            (gpu.family == Family.ADRENO_UNKNOWN && (Family.A7XX in families || Family.A8XX in families))
    }

    private val ALL_ADRENO = setOf(Family.A6XX, Family.A7XX, Family.A7XX_LOW, Family.A8XX, Family.ADRENO_UNKNOWN)

    private fun describe(key: String): Pair<String, Set<Family>> = when {
        key == DD_TURNIP || key.startsWith("$DD_TURNIP:") -> "DroidDeck · DD-Turnip" to ALL_ADRENO
        else -> describeFixed(key)
    }

    private fun describeFixed(key: String): Pair<String, Set<Family>> = when (key) {
        BANNER -> "Banners-Turnip · Adreno 6xx/7xx" to setOf(Family.A6XX, Family.A7XX)
        BANNER_A8XX -> "Banners-Turnip · Adreno 8xx" to setOf(Family.A8XX)
        BANNER_710 -> "Banners-Turnip · Adreno 710/720/722 (test)" to setOf(Family.A7XX_LOW)
        BANNER_ONEUI -> "Banners-Turnip · 8 Gen 2 on One UI" to setOf(Family.A7XX)
        WN_BALANCED -> "WinNative · Balanced" to ALL_ADRENO
        WN_PERFORMANCE -> "WinNative · Performance" to ALL_ADRENO
        else -> key to emptySet()
    }

    fun from(check: TurnipReleases.Check?): List<DriverPair> {
        val assets = check?.assets.orEmpty().filter { it.pair.isNotEmpty() }
        val order = listOf(DD_TURNIP, BANNER, BANNER_A8XX, BANNER_ONEUI, BANNER_710, WN_BALANCED, WN_PERFORMANCE)
        return assets.map { it.pair }.distinct()
            .sortedBy { key -> order.indexOf(key.substringBefore(':')).let { i -> if (i < 0) order.size else i } }
            .map { key ->
                val halves = assets.filter { it.pair == key }
                val kind = halves.map { it.pairKind }.distinct().singleOrNull().orEmpty()
                val (classifiedName, families) = describe(kind)
                val name = classifiedName.ifEmpty { key }
                halves.firstOrNull { it.bundle }?.let { return@map DriverPair(key, name, families, null, null, it) }
                val display = halves.firstOrNull { !it.linux }
                val linux = halves.firstOrNull { it.linux }
                val sameRelease = !kind.startsWith(BANNER) || display?.tag == linux?.tag
                DriverPair(key, name, families, display, if (sameRelease) linux else null)
            }
    }

    fun recommendedKey(
        gpu: GpuInfo, pairs: List<DriverPair>,
        rules: List<AutoPicks.Rule> = AutoPicks.BUILT_IN, bad: Set<String> = emptySet(),
        sdk: Int = android.os.Build.VERSION.SDK_INT, blob: Int? = null,
    ): String? {
        if (gpu.family == Family.NOT_ADRENO) return null
        val keys = AutoPicks.preferences(gpu, rules, sdk, blob)
            .ifEmpty { AutoPicks.preferences(gpu, AutoPicks.BUILT_IN, sdk, blob) }
            .mapNotNull { if (it == AutoPicks.NEWEST_DD_TURNIP) newestDdTurnip(pairs) else it }
        return keys.firstOrNull { key -> pairs.any { it.key == key && it.complete && AutoDriverTrial.release(it) !in bad } }
            ?: keys.firstOrNull()
    }

    fun newestDdTurnip(pairs: List<DriverPair>): String? = pairs
        .filter { it.key.startsWith("$DD_TURNIP:") && it.complete }
        .mapNotNull { pair ->
            ddTurnipVersion(pair.assets.firstOrNull()?.pairKind ?: pair.key)?.let { pair to it }
        }
        .maxWithOrNull { first, second -> first.second.compareTo(second.second) }
        ?.first?.key
}
