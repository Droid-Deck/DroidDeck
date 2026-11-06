package com.droiddeck.launcher.core

import android.content.Context
import android.os.Build
import com.droiddeck.launcher.R
import com.droiddeck.launcher.gpu.SystemVulkanDriver
import java.io.File

object DeviceSupport {
    enum class GraphicsRoute(
        val id: String,
        val bridge: Boolean = false,
        val systemVulkan: Boolean = false,
        val linuxVulkan: Boolean = false,
    ) {
        VK_BRIDGE("vkbridge", bridge = true),
        SYSTEM_VULKAN("system-vulkan", systemVulkan = true),
        LINUX_VULKAN("linux-vulkan", linuxVulkan = true),
    }

    private val adrenoGpu by lazy {
        File("/sys/class/kgsl/kgsl-3d0").exists() || File("/vendor/lib64/hw/vulkan.adreno.so").exists()
    }

    private val maliGpu by lazy { !adrenoGpu && File("/dev/mali0").exists() }

    fun adreno(): Boolean = adrenoGpu

    fun mali(): Boolean = maliGpu

    fun vkBridge(context: Context): Boolean =
        !adreno() && com.droiddeck.launcher.session.SessionPrefs.vkBridge(context)

    fun graphicsRoute(context: Context, linuxDriverId: String): GraphicsRoute = graphicsRoute(
        bridge = vkBridge(context),
        systemVulkan = SystemVulkanDriver.usesDefault(linuxDriverId),
    )

    internal fun graphicsRoute(bridge: Boolean, systemVulkan: Boolean): GraphicsRoute = when {
        bridge -> GraphicsRoute.VK_BRIDGE
        systemVulkan -> GraphicsRoute.SYSTEM_VULKAN
        else -> GraphicsRoute.LINUX_VULKAN
    }

    internal fun needsSystemVulkanComponent(
        sessionRunning: Boolean,
        route: GraphicsRoute,
        available: Boolean,
        ready: Boolean,
    ): Boolean = !sessionRunning && route.systemVulkan && available && !ready

    fun canDraw(context: Context): Boolean = graphicsAvailable() || vkBridge(context)

    fun graphicsAvailable(): Boolean = graphicsAvailable(adreno(), SystemVulkanDriver.isAvailable())

    fun graphicsReady(context: Context): Boolean = adreno() || SystemVulkanDriver.isReady(context)

    internal fun graphicsAvailable(adreno: Boolean, systemVulkanReady: Boolean): Boolean = adreno || systemVulkanReady

    fun gpuName(context: Context): String {
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } else null
        return soc?.let { "$it (${Build.HARDWARE})" } ?: Build.HARDWARE.ifBlank { context.getString(R.string.gpu_this_gpu) }
    }

    fun lowLatencyWifiSafe(): Boolean = Build.VERSION.SDK_INT < 31 || Build.SOC_MODEL != "CQ8725S"
}
