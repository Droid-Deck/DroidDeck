package com.droiddeck.launcher.gpu

import android.os.Build

object SocNames {
    private val MODELS = mapOf(
        "SM8150" to "Snapdragon 855", "SM8150-AC" to "Snapdragon 855+",
        "SM8250" to "Snapdragon 865", "SM8250-AC" to "Snapdragon 870",
        "SM8350" to "Snapdragon 888", "SM8350-AC" to "Snapdragon 888+",
        "SM8450" to "Snapdragon 8 Gen 1", "SM8475" to "Snapdragon 8+ Gen 1", "SM8475P" to "Snapdragon 8+ Gen 1",
        "SM8550" to "Snapdragon 8 Gen 2", "SM8550P" to "Snapdragon 8 Gen 2", "SM8550-AC" to "Snapdragon 8 Gen 2",
        "QCS8550" to "Snapdragon 8 Gen 2", "QCS8550P" to "Snapdragon 8 Gen 2",
        "SM8650" to "Snapdragon 8 Gen 3", "SM8650P" to "Snapdragon 8 Gen 3", "QCS8650" to "Snapdragon 8 Gen 3",
        "SM8635" to "Snapdragon 8s Gen 3", "SM8635P" to "Snapdragon 8s Gen 3",
        "SM8735" to "Snapdragon 8s Gen 4", "SM8735P" to "Snapdragon 8s Gen 4",
        "SM8750" to "Snapdragon 8 Elite", "SM8750P" to "Snapdragon 8 Elite",
        "SM8845" to "Snapdragon 8s Gen 5", "SM8845P" to "Snapdragon 8s Gen 5",
        "SM8850" to "Snapdragon 8 Elite Gen 5", "SM8850P" to "Snapdragon 8 Elite Gen 5",
        "SM8975" to "Snapdragon 8 Elite Extreme Gen 6", "SM8975P" to "Snapdragon 8 Elite Extreme Gen 6",
        "SG8150P" to "Snapdragon G3x Gen 1", "SG8275" to "Snapdragon G3x Gen 2", "SG8275P" to "Snapdragon G3x Gen 2",
        "SG6150P" to "Snapdragon G2 Gen 1", "SG4150P" to "Snapdragon G1 Gen 1",
        "CQ8725S" to "Snapdragon 8 Elite Gaming",
        "SM7125" to "Snapdragon 720G", "SM7150" to "Snapdragon 730",
        "SM7225" to "Snapdragon 750G", "SM7250" to "Snapdragon 765G",
        "SM7325" to "Snapdragon 778G", "SM7435" to "Snapdragon 7s Gen 2",
        "SM7450" to "Snapdragon 7 Gen 1", "SM7475" to "Snapdragon 7+ Gen 2", "SM7475P" to "Snapdragon 7+ Gen 2",
        "SM7550" to "Snapdragon 7 Gen 3", "SM7635" to "Snapdragon 7s Gen 3",
        "SM7675" to "Snapdragon 7+ Gen 3", "SM7675P" to "Snapdragon 7+ Gen 3",
        "SM6115" to "Snapdragon 662", "SM6125" to "Snapdragon 665", "SM6225" to "Snapdragon 680",
        "SM6375" to "Snapdragon 695", "SM6450" to "Snapdragon 6 Gen 1",
    )

    private val PLATFORMS = mapOf(
        "msmnile" to "Snapdragon 855/860", "kona" to "Snapdragon 865/870", "lahaina" to "Snapdragon 888",
        "taro" to "Snapdragon 8 Gen 1", "cape" to "Snapdragon 8+ Gen 1", "kalama" to "Snapdragon 8 Gen 2",
        "pineapple" to "Snapdragon 8 Gen 3", "cliff" to "Snapdragon 8s Gen 3", "tuna" to "Snapdragon 8s Gen 4",
        "sun" to "Snapdragon 8 Elite", "canoe" to "Snapdragon 8 Elite Gen 5",
        "cliffs" to "Snapdragon 7+ Gen 3", "volcano" to "Snapdragon 7s Gen 3",
        "crow" to "Snapdragon 7 Gen 3", "ukee" to "Snapdragon 7+ Gen 2",
        "yupik" to "Snapdragon 778G", "lito" to "Snapdragon 765G", "atoll" to "Snapdragon 720G",
        "parrot" to "Snapdragon 6 Gen 1", "holi" to "Snapdragon 695",
        "khaje" to "Snapdragon 680", "bengal" to "Snapdragon 662", "trinket" to "Snapdragon 665",
    )

    fun model(prop: (String) -> String = GpuInfo::systemProperty): String {
        val sdk = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeIf { it != Build.UNKNOWN }.orEmpty() else ""
        return listOf(prop("ro.soc.model"), prop("ro.vendor.qti.soc_model"), sdk, prop("ro.fota.platform"))
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.equals(Build.UNKNOWN, ignoreCase = true) && !Regex("(?i)^msm_\\d").containsMatchIn(it) }
            .orEmpty()
    }

    internal fun normalizedModel(model: String): String = model.trim().uppercase().removePrefix("QTI ").trim()

    fun name(model: String, platform: String = GpuInfo.systemProperty("ro.board.platform")): String? =
        MODELS[normalizedModel(model)] ?: PLATFORMS[platform.lowercase()]?.let { "$it family" }

    fun label(model: String = model(), platform: String = GpuInfo.systemProperty("ro.board.platform")): String {
        val name = name(model, platform)
        return when {
            name != null && model.isNotEmpty() -> "$name ($model)"
            name != null -> name
            else -> model
        }
    }
}
