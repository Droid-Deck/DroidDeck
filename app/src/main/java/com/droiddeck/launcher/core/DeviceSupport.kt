package com.droiddeck.launcher.core

import android.content.Context
import android.os.Build
import com.droiddeck.launcher.R
import com.droiddeck.launcher.gpu.SystemVulkanDriver
import java.io.File

object DeviceSupport {
    enum class GraphicsRoute(
        val id: String,
        val venus: Boolean = false,
        val bridge: Boolean = false,
        val systemVulkan: Boolean = false,
        val linuxVulkan: Boolean = false,
    ) {
        VENUS("venus", venus = true),
        VK_BRIDGE("vkbridge", bridge = true),
        SYSTEM_VULKAN("system-vulkan", systemVulkan = true),
        LINUX_VULKAN("linux-vulkan", linuxVulkan = true),
    }

    private val adrenoGpu by lazy {
        File("/sys/class/kgsl/kgsl-3d0").exists() || File("/vendor/lib64/hw/vulkan.adreno.so").exists()
    }

    fun adreno(): Boolean = adrenoGpu

    fun graphicsAvailable(): Boolean = graphicsAvailable(adreno(), SystemVulkanDriver.isAvailable())

    internal fun graphicsAvailable(adreno: Boolean, powerVrReady: Boolean): Boolean = adreno || powerVrReady

    fun vkBridge(context: Context): Boolean =
        bridgeUsable() && !com.droiddeck.launcher.session.VenusComponent.wanted(context) &&
            com.droiddeck.launcher.session.SessionPrefs.vkBridge(context)

    fun mali(): Boolean = isMali

    private val isMali by lazy {
        !adreno() && (File("/dev/mali0").exists() ||
            com.droiddeck.launcher.gpu.GpuInfo.detect().family.name.startsWith("MALI"))
    }

    fun bridgeUsable(): Boolean = !adreno() && !SystemVulkanDriver.isAvailable() && File("/dev/mali0").exists()

    fun graphicsRoute(context: Context, linuxDriverId: String): GraphicsRoute = graphicsRoute(
        venus = com.droiddeck.launcher.session.VenusComponent.wanted(context),
        bridge = bridgeUsable() && com.droiddeck.launcher.session.SessionPrefs.vkBridge(context),
        systemVulkan = SystemVulkanDriver.usesSystemVulkan(linuxDriverId),
    )

    internal fun graphicsRoute(venus: Boolean, bridge: Boolean, systemVulkan: Boolean): GraphicsRoute = when {
        venus -> GraphicsRoute.VENUS
        systemVulkan -> GraphicsRoute.SYSTEM_VULKAN
        bridge -> GraphicsRoute.VK_BRIDGE
        else -> GraphicsRoute.LINUX_VULKAN
    }

    internal fun needsSystemVulkanComponent(
        sessionRunning: Boolean,
        route: GraphicsRoute,
        available: Boolean,
        ready: Boolean,
    ): Boolean = !sessionRunning && route.systemVulkan && available && !ready

    fun openDriverActive(context: Context): Boolean {
        if (adreno() || graphicsRoute(context, com.droiddeck.launcher.session.SessionPrefs.linuxDriver(context)) != GraphicsRoute.LINUX_VULKAN) return false
        val linux = com.droiddeck.launcher.session.SessionPrefs.linuxDriver(context).ifEmpty { return false }
        return com.droiddeck.launcher.gpu.DriverPairs.isNonAdreno(com.droiddeck.launcher.gpu.DriverBundle.containing(context, linux, true)?.id)
    }

    fun canDraw(context: Context): Boolean = graphicsAvailable() || openDriverActive(context) || vkBridge(context) ||
        com.droiddeck.launcher.session.VenusComponent.wanted(context)

    fun graphicsReady(context: Context): Boolean {
        val choice = com.droiddeck.launcher.session.SessionPrefs.linuxDriver(context)
        return adreno() || graphicsRoute(context, choice) != GraphicsRoute.SYSTEM_VULKAN ||
            SystemVulkanDriver.isReady(context, choice)
    }

    fun gpuName(context: Context): String {
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } else null
        return soc?.let { "$it (${Build.HARDWARE})" } ?: Build.HARDWARE.ifBlank { context.getString(R.string.gpu_this_gpu) }
    }

    fun lowLatencyWifiSafe(): Boolean = Build.VERSION.SDK_INT < 31 || Build.SOC_MODEL != "CQ8725S"
}
