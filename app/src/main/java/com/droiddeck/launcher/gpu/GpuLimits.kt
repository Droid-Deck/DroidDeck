package com.droiddeck.launcher.gpu

import java.io.File

object GpuLimits {
    class Found(val path: String, val mhz: List<Long>)

    private const val KGSL = "/sys/class/kgsl/kgsl-3d0"
    private const val QCOM_GPU = "/sys/kernel/gpu"

    fun stepSources(): List<String> = listOf(
        "$KGSL/gpu_available_frequencies", "$KGSL/freq_table_mhz", "$KGSL/devfreq/available_frequencies", "$QCOM_GPU/gpu_freq_table",
    ) + devfreq("available_frequencies")

    fun maxSources(): List<String> = listOf(
        "$KGSL/max_gpuclk", "$KGSL/max_clock_mhz", "$KGSL/devfreq/max_freq", "$QCOM_GPU/gpu_max_clock",
    ) + devfreq("max_freq")

    fun minSources(): List<String> = listOf(
        "$KGSL/min_clock_mhz", "$KGSL/devfreq/min_freq", "$QCOM_GPU/gpu_min_clock",
    ) + devfreq("min_freq")

    fun first(sources: List<String>): Found? =
        sources.firstNotNullOfOrNull { path -> read(path).takeIf { it.isNotEmpty() }?.let { Found(path, it) } }

    fun read(path: String): List<Long> = runCatching {
        Regex("\\d+").findAll(File(path).readText()).map { mhz(it.value.toLong()) }.filter { it > 0 }.distinct().sorted().toList()
    }.getOrDefault(emptyList())

    fun mhz(raw: Long): Long = when {
        raw > 10_000_000 -> raw / 1_000_000
        raw > 10_000 -> raw / 1_000
        else -> raw
    }

    private fun devfreq(file: String): List<String> =
        File("/sys/class/devfreq").listFiles()?.filter { f ->
            val n = f.name.lowercase()
            (n.contains("gpu") || n.contains("mali") || n.contains("kgsl-3d")) && listOf("bus", "bw", "memlat").none { n.contains(it) }
        }?.sortedBy { it.name }?.map { "${it.path}/$file" }.orEmpty()
}
