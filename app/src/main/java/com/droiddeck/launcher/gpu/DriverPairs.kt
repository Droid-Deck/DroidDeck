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

    /** The open non-Adreno drivers (The412Banner/droiddeck-nonadreno-drivers), one pair per driver. */
    const val NON_ADRENO = "nad"
    const val NAD_RADV_XCLIPSE = "nad:radv-xclipse"
    /** RADV-Xclipse rebased on JimVulkan's upstream: a manual choice, never Auto. */
    const val NAD_RADV_XCLIPSE_UPSTREAM = "nad:radv-xclipse-upstream"
    private val XCLIPSE_MODEL = Regex("""(?i)xclipse\s*(\d{3})\b""")
    const val NAD_MALI_JM = "nad:panvk-mali-jm"
    const val NAD_MALI_G710 = "nad:panvk-mali-g710"
    const val NAD_MALI_G615 = "nad:panvk-mali-g615"
    const val NAD_MALI_G720 = "nad:panvk-mali-g720"
    /** One PanVK kbase binary for Mali v9-v13; its display half needs Android 15. */
    const val NAD_MALI_KBASE = "nad:panvk-mali-kbase"
    const val MALI_KBASE_MIN_SDK = 35
    /** faux123's PanVK builds for three handhelds: manual choices, never Auto. */
    const val NAD_FAUX_RP4PRO = "nad:panvk-faux123-rp4pro"
    const val NAD_FAUX_ACE = "nad:panvk-faux123-ace"
    const val NAD_FAUX_RG556 = "nad:panvk-faux123-rg556"
    /** The one Mali a device-specific build is for, by the number in the GPU's name. */
    private val DEVICE_BUILDS = mapOf(NAD_FAUX_RP4PRO to 77, NAD_FAUX_ACE to 610, NAD_FAUX_RG556 to 57)
    /** "PanVK-Mali-G720" (the release asset's id) to its pair key, "nad:panvk-mali-g720". */
    fun nonAdreno(id: String) = "$NON_ADRENO:" + id.lowercase()
    /** True for a non-Adreno pair's key, or a bundle installed from one ("PanVK-Mali-G720"). */
    fun isNonAdreno(keyOrBundleId: String?): Boolean = keyOrBundleId != null &&
        (keyOrBundleId.startsWith("$NON_ADRENO:") || Regex("""^(?i)(RADV-Xclipse|PanVK-[A-Za-z0-9]+)(-[A-Za-z0-9]+)*$""").matches(keyOrBundleId))

    fun nonAdrenoName(key: String): String = describeNonAdreno(key).first

    private fun describeNonAdreno(key: String): Pair<String, Set<Family>> = when (key) {
        NAD_RADV_XCLIPSE -> "RADV · Samsung Xclipse" to setOf(Family.POWERVR)
        NAD_RADV_XCLIPSE_UPSTREAM -> "RADV upstream · Samsung Xclipse" to setOf(Family.POWERVR)
        NAD_MALI_JM -> "PanVK · Mali G52-G78" to setOf(Family.MALI_JM)
        NAD_MALI_G710 -> "PanVK · Mali-G610/G710" to setOf(Family.MALI_G610_G710)
        NAD_MALI_G615 -> "PanVK · Mali-G615" to setOf(Family.MALI_G615)
        NAD_MALI_G720 -> "PanVK · Mali-G720" to setOf(Family.MALI_G720)
        NAD_FAUX_RP4PRO -> "PanVK faux123 · Retroid Pocket 4 Pro (Mali-G77)" to setOf(Family.MALI_JM)
        NAD_FAUX_ACE -> "PanVK faux123 · GameForce Ace (Mali-G610)" to setOf(Family.MALI_G610_G710)
        NAD_FAUX_RG556 -> "PanVK faux123 · Anbernic RG556 (Mali-G57)" to setOf(Family.MALI_JM)
        NAD_MALI_KBASE -> "PanVK kbase · Mali G57-G925" to
            setOf(Family.MALI_JM, Family.MALI_G610_G710, Family.MALI_G615, Family.MALI_G720, Family.MALI_LATEST)
        else -> key.removePrefix("$NON_ADRENO:") to emptySet<Family>()
    }

    class DriverPair(
        val key: String,
        val pairKind: String,
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
        fun suits(gpu: GpuInfo) = when {
            // The system-driver family covers PowerVR and Xclipse; RADV is only for the Xclipse.
            pairKind == NAD_RADV_XCLIPSE || pairKind == NAD_RADV_XCLIPSE_UPSTREAM ->
                gpu.family == Family.POWERVR && gpu.name.contains("Xclipse", ignoreCase = true)
            // One binary for Mali v9-v13, not the older G52/G76 in the Job Manager family.
            pairKind == NAD_MALI_KBASE -> gpu.family.mali && GpuInfo.maliKbaseCovered(gpu.name)
            pairKind in DEVICE_BUILDS -> gpu.family.mali && GpuInfo.maliNumber(gpu.name) == DEVICE_BUILDS[pairKind]
            else -> gpu.family in families ||
                (gpu.family == Family.ADRENO_UNKNOWN && (Family.A7XX in families || Family.A8XX in families))
        }
    }

    private val ALL_ADRENO = setOf(Family.A6XX, Family.A7XX, Family.A7XX_LOW, Family.A8XX, Family.ADRENO_UNKNOWN)

    private fun describe(pairKind: String): Pair<String, Set<Family>> = when {
        pairKind.startsWith(DD_TURNIP) -> "DroidDeck · DD-Turnip" to ALL_ADRENO
        pairKind.startsWith("$NON_ADRENO:") -> describeNonAdreno(pairKind)
        else -> describeFixed(pairKind)
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
        val order = listOf(DD_TURNIP, BANNER, BANNER_A8XX, BANNER_ONEUI, BANNER_710, WN_BALANCED, WN_PERFORMANCE, NON_ADRENO)
        return assets.map { it.pair }.distinct()
            .sortedBy { key -> order.indexOf(key.substringBefore(':')).let { i -> if (i < 0) order.size else i } }
            .map { key ->
                val halves = assets.filter { it.pair == key }
                val pairKinds = halves.map { it.pairKind }.filter { it.isNotEmpty() }.distinct()
                val pairKind = pairKinds.singleOrNull()
                val (name, families) = pairKind?.let(::describe) ?: (key to emptySet())
                halves.firstOrNull { it.bundle }?.let {
                    return@map DriverPair(key, pairKind.orEmpty(), name, families, null, null, it)
                }
                val display = halves.firstOrNull { !it.linux }
                val linux = halves.firstOrNull { it.linux }
                val sameRelease = !key.startsWith(BANNER) || display?.tag == linux?.tag
                DriverPair(key, pairKind.orEmpty(), name, families, display, if (sameRelease) linux else null)
            }
    }

    /** 950 and 960 panic on the older build; a name without a model number is treated the same way. */
    private fun xclipseNeedsUpstream(name: String): Boolean =
        XCLIPSE_MODEL.find(name)?.groupValues?.get(1).let { it == null || it == "950" || it == "960" }

    fun recommendedKey(
        gpu: GpuInfo, pairs: List<DriverPair>,
        rules: List<AutoPicks.Rule> = AutoPicks.BUILT_IN, bad: Set<String> = emptySet(),
        sdk: Int = android.os.Build.VERSION.SDK_INT, blob: Int? = null,
    ): String? {
        when (gpu.family) {
            Family.POWERVR -> return when {
                !gpu.name.contains("Xclipse", ignoreCase = true) -> null
                xclipseNeedsUpstream(gpu.name) && pairs.any { it.key == NAD_RADV_XCLIPSE_UPSTREAM } -> NAD_RADV_XCLIPSE_UPSTREAM
                else -> NAD_RADV_XCLIPSE
            }
            Family.MALI_OTHER, Family.NOT_ADRENO -> return null
            Family.MALI_JM, Family.MALI_G610_G710, Family.MALI_G615, Family.MALI_G720, Family.MALI_LATEST ->
                return maliKey(gpu, pairs, sdk)
            else -> Unit
        }
        val keys = AutoPicks.preferences(gpu, rules, sdk, blob)
            .ifEmpty { AutoPicks.preferences(gpu, AutoPicks.BUILT_IN, sdk, blob) }
            .mapNotNull { if (it == AutoPicks.NEWEST_DD_TURNIP) newestDdTurnip(pairs) else it }
        return keys.firstOrNull { key -> pairs.any { it.key == key && it.complete && AutoDriverTrial.release(it) !in bad } }
            ?: keys.firstOrNull()
    }

    private fun maliKey(gpu: GpuInfo, pairs: List<DriverPair>, sdk: Int): String? {
        val family = when (gpu.family) {
            Family.MALI_JM -> NAD_MALI_JM
            Family.MALI_G610_G710 -> NAD_MALI_G710
            Family.MALI_G615 -> NAD_MALI_G615
            Family.MALI_G720 -> NAD_MALI_G720
            else -> null
        }
        val kbase = NAD_MALI_KBASE.takeIf { sdk >= MALI_KBASE_MIN_SDK && GpuInfo.maliKbaseCovered(gpu.name) }
        if (pairs.isEmpty()) return kbase ?: family
        fun listed(key: String?) = key?.takeIf { k -> pairs.any { it.key == k && it.complete } }
        return listed(kbase) ?: listed(family) ?: kbase ?: family
    }

    fun newestDdTurnip(pairs: List<DriverPair>): String? = pairs
        .filter { it.key.startsWith("$DD_TURNIP:") && it.complete }
        .mapNotNull { pair -> ddTurnipVersion(pair.assets.firstOrNull()?.pairKind ?: pair.key)?.let { pair to it } }
        .maxWithOrNull { first, second -> first.second.compareTo(second.second) }
        ?.first?.key
}
