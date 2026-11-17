package com.droiddeck.launcher.session

import com.droiddeck.launcher.core.DeviceReport
import com.droiddeck.launcher.core.FileUtils
import java.io.File

object FexHost {
    internal fun kernelHidesCpuFeatures(cpuInfo: String): Boolean {
        val cores = DeviceReport.cpuFeatureLists(cpuInfo)
        return cores.isNotEmpty() && cores.any { "cpuid" !in it }
    }

    fun kernelHidesCpuFeatures(): Boolean =
        runCatching { kernelHidesCpuFeatures(FileUtils.readString(File("/proc/cpuinfo")).orEmpty()) }.getOrDefault(false)
}
