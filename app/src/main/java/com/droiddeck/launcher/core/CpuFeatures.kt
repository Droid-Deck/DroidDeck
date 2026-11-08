package com.droiddeck.launcher.core

import java.io.File

object CpuFeatures {
    internal fun lacksAtomics(cpuInfo: String): Boolean {
        val cores = cpuInfo.lineSequence()
            .filter { it.substringBefore(':').trim() == "Features" }
            .map { it.substringAfter(':').trim().split(Regex("\\s+")) }
            .toList()
        return cores.isNotEmpty() && cores.any { "atomics" !in it }
    }

    val lacksAtomics: Boolean by lazy {
        runCatching { lacksAtomics(File("/proc/cpuinfo").readText()) }.getOrDefault(false)
    }
}
