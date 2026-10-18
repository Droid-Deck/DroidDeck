package com.droiddeck.launcher.gpu

import com.droiddeck.launcher.gpu.GpuInfo.Family
import org.json.JSONArray
import org.json.JSONObject

object AutoPicks {
    const val URL = "https://raw.githubusercontent.com/Droid-Deck/Drivers-CI/main/auto-drivers.json"
    const val SCHEMA = 1
    const val NEWEST_DD_TURNIP = DriverPairs.DD_TURNIP

    class Rule(
        val families: Set<Family>,
        val models: Set<Int> = emptySet(),
        val oneUi8Gen2: Boolean? = null,
        val minSdk: Int = 0,
        val blobMin: Int = 0,
        val blobMax: Int = Int.MAX_VALUE,
        val prefer: List<String>,
    ) {
        fun matches(gpu: GpuInfo, sdk: Int, blob: Int?): Boolean =
            gpu.family in families &&
                (models.isEmpty() || gpu.model in models) &&
                (oneUi8Gen2 == null || gpu.oneUi8Gen2 == oneUi8Gen2) &&
                sdk >= minSdk &&
                (blobMin == 0 && blobMax == Int.MAX_VALUE || blob != null && blob in blobMin..blobMax)
    }

    val BUILT_IN = listOf(
        Rule(setOf(Family.A8XX), models = setOf(840), prefer = listOf(NEWEST_DD_TURNIP, DriverPairs.WN_BALANCED)),
        Rule(setOf(Family.A8XX), prefer = listOf(DriverPairs.WN_BALANCED)),
        Rule(setOf(Family.A7XX_LOW), prefer = listOf(DriverPairs.BANNER_710)),
        Rule(setOf(Family.A7XX), oneUi8Gen2 = true, prefer = listOf(DriverPairs.BANNER_ONEUI)),
        Rule(setOf(Family.A7XX, Family.A6XX), prefer = listOf(DriverPairs.BANNER)),
        Rule(setOf(Family.ADRENO_UNKNOWN), minSdk = 36, prefer = listOf(DriverPairs.WN_BALANCED)),
        Rule(setOf(Family.ADRENO_UNKNOWN), prefer = listOf(DriverPairs.BANNER)),
    )

    fun parse(text: String?): List<Rule>? = runCatching {
        val json = JSONObject(text ?: return null)
        if (json.optInt("schemaVersion") != SCHEMA) return null
        val rules = json.getJSONArray("rules")
        (0 until rules.length()).mapNotNull { i -> rule(rules.getJSONObject(i)) }.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun rule(o: JSONObject): Rule? {
        val families = strings(o.optJSONArray("families")).mapNotNull { name -> Family.values().firstOrNull { it.name == name } }.toSet()
        val prefer = strings(o.optJSONArray("prefer"))
        if (families.isEmpty() || prefer.isEmpty()) return null
        val models = o.optJSONArray("models")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: emptySet()
        return Rule(
            families, models,
            if (o.has("oneUi8Gen2")) o.getBoolean("oneUi8Gen2") else null,
            o.optInt("minSdk", 0), o.optInt("blobMin", 0), o.optInt("blobMax", Int.MAX_VALUE), prefer,
        )
    }

    private fun strings(a: JSONArray?): List<String> = a?.let { (0 until it.length()).map { i -> it.getString(i) } }.orEmpty()

    fun preferences(gpu: GpuInfo, rules: List<Rule>, sdk: Int, blob: Int?): List<String> =
        rules.firstOrNull { it.matches(gpu, sdk, blob) }?.prefer.orEmpty()

    fun blobVersion(driverVersion: String?): Int? = driverVersion?.split('.')?.getOrNull(1)?.toIntOrNull()
}
