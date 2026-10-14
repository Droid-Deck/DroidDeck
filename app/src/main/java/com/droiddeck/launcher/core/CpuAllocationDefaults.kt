package com.droiddeck.launcher.core

import com.droiddeck.launcher.gpu.GpuInfo
import com.droiddeck.launcher.gpu.SocNames

object CpuAllocationDefaults {
    data class Allocation(val client: String, val game: String)

    val device: Allocation? by lazy {
        forDevice(SocNames.model(), GpuInfo.systemProperty("ro.board.platform"),
            CpuCores.all.associateWith(CpuCores::maxGhz))
    }

    fun forDevice(model: String, platform: String, frequencies: Map<Int, Double?>): Allocation? {
        val chip = model.trim().uppercase().substringBefore('-')
        val gen2 = chip in setOf("SM8550", "QCS8550") ||
            (chip.isEmpty() && platform.trim().equals("kalama", ignoreCase = true))
        if (!gen2 || frequencies.size != 8 || frequencies.keys.any { it < 0 } ||
            frequencies.values.any { it == null || !it.isFinite() || it <= 0.0 }) return null
        val clusters = frequencies.entries.groupBy { it.value }.toSortedMap(compareBy { it ?: 0.0 }).values.toList()
        if (clusters.map { it.size } != listOf(3, 4, 1)) return null
        return Allocation(
            client = CpuCores.format(clusters[2].map { it.key }.toSet()),
            game = CpuCores.format(clusters[1].map { it.key }.toSet()),
        )
    }
}
