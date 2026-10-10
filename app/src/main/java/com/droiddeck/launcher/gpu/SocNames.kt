package com.droiddeck.launcher.gpu

import android.os.Build

/**
 * Which chip this is, as a person would say it. Handhelds often leave Android's SoC model blank
 * (the AYANEO Pocket FIT does), so the model is looked for where vendors put it, the same order
 * ClusterTune uses; then it is named from a table of models we are sure of, or from the platform
 * code name as a family ("Snapdragon 8 Gen 3 family") when the model itself is a vendor variant.
 */
object SocNames {
    /** Model codes whose product name is certain. */
    private val MODELS = mapOf(
        "SM8150" to "Snapdragon 855", "SM8250" to "Snapdragon 865", "SM8250-AC" to "Snapdragon 870",
        "SM8350" to "Snapdragon 888", "SM8450" to "Snapdragon 8 Gen 1", "SM8475" to "Snapdragon 8+ Gen 1",
        "SM8550" to "Snapdragon 8 Gen 2", "QCS8550" to "Snapdragon 8 Gen 2", "SM8650" to "Snapdragon 8 Gen 3",
        "SM8750" to "Snapdragon 8 Elite", "SM8850" to "Snapdragon 8 Elite Gen 5",
        "SM7325" to "Snapdragon 778G", "SM7450" to "Snapdragon 7 Gen 1", "SM7550" to "Snapdragon 7 Gen 3",
        "SM6375" to "Snapdragon 695",
        "SC8380XP" to "Snapdragon X Elite", "X1E80100" to "Snapdragon X Elite", "X1E-80-100" to "Snapdragon X Elite",
        "X1E84100" to "Snapdragon X Elite", "X1E-84-100" to "Snapdragon X Elite",
        "X1E78100" to "Snapdragon X Elite", "X1E-78-100" to "Snapdragon X Elite",
        "X1P64100" to "Snapdragon X Plus", "X1P-64-100" to "Snapdragon X Plus",
        "X1P42100" to "Snapdragon X Plus", "X1P-42-100" to "Snapdragon X Plus",
    )

    /** Platform code names: every chip of one platform shares its CPU and GPU design. */
    private val PLATFORMS = mapOf(
        "msmnile" to "Snapdragon 855/860", "kona" to "Snapdragon 865/870", "lahaina" to "Snapdragon 888",
        "taro" to "Snapdragon 8 Gen 1", "cape" to "Snapdragon 8+ Gen 1", "kalama" to "Snapdragon 8 Gen 2",
        "pineapple" to "Snapdragon 8 Gen 3", "sun" to "Snapdragon 8 Elite",
        "hamoa" to "Snapdragon X Elite", "purwa" to "Snapdragon X Plus",
        "sc8380xp" to "Snapdragon X Elite", "x1e80100" to "Snapdragon X Elite",
    )

    /** The chip's model code ("QCS8550", "SG8350P"), or "" when the phone does not say. */
    fun model(prop: (String) -> String = GpuInfo::systemProperty): String {
        val sdk = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeIf { it != Build.UNKNOWN }.orEmpty() else ""
        return listOf(prop("ro.soc.model"), prop("ro.vendor.qti.soc_model"), sdk, prop("ro.fota.platform"))
            .map { it.trim() }
            // ro.fota.platform is often a branch name ("MSM_13.0"), not a chip.
            .firstOrNull { it.isNotEmpty() && !Regex("(?i)^msm_\\d").containsMatchIn(it) }.orEmpty()
    }

    /** "Snapdragon 8 Gen 2", "Snapdragon 8 Gen 3 family", or null when neither the model nor the platform is known. */
    fun name(model: String, platform: String = GpuInfo.systemProperty("ro.board.platform")): String? =
        MODELS[model.uppercase()] ?: PLATFORMS[platform.lowercase()]?.let { "$it family" }

    /** For the GPU card: "Snapdragon 8 Gen 2 (QCS8550)", the bare model, or "". */
    fun label(model: String = model(), platform: String = GpuInfo.systemProperty("ro.board.platform")): String {
        val name = name(model, platform)
        return when {
            name != null && model.isNotEmpty() -> "$name ($model)"
            name != null -> name
            else -> model
        }
    }
}
