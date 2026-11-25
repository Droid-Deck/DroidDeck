package com.droiddeck.launcher.core

import android.content.Context
import android.os.Build
import com.droiddeck.launcher.R
import com.droiddeck.launcher.gpu.GpuInfo
import com.droiddeck.launcher.gpu.SocNames
import com.droiddeck.launcher.gpu.SystemVulkanDriver
import java.io.File

object DeviceSupport {
    private val adrenoGpu by lazy { GpuInfo.adrenoHardwarePresent() }

    fun adreno(): Boolean = adrenoGpu

    fun graphicsAvailable(): Boolean = graphicsAvailable(adreno(), SystemVulkanDriver.isAvailable())

    internal fun graphicsAvailable(adreno: Boolean, powerVrReady: Boolean): Boolean = adreno || powerVrReady

    fun vkBridge(context: Context): Boolean =
        bridgeUsable() && !com.droiddeck.launcher.session.VenusComponent.wanted(context) &&
            com.droiddeck.launcher.session.SessionPrefs.vkBridge(context)

    fun mali(): Boolean = isMali

    private val isMali by lazy {
        isMali(adreno(), File("/dev/mali0").exists(), GpuInfo.detect().family)
    }

    internal fun isMali(adreno: Boolean, maliNode: Boolean, family: GpuInfo.Family): Boolean =
        !adreno && (maliNode || family.mali)

    fun bridgeUsable(): Boolean = bridgeUsable(adreno(), SystemVulkanDriver.isAvailable())

    internal fun bridgeUsable(adreno: Boolean, systemDriverReady: Boolean): Boolean = !adreno && !systemDriverReady

    fun openDriverActive(context: Context): Boolean {
        if (adreno()) return false
        if (com.droiddeck.launcher.session.VenusComponent.wanted(context)) return false
        if (bridgeUsable() && com.droiddeck.launcher.session.SessionPrefs.vkBridge(context)) return false
        val linux = com.droiddeck.launcher.session.SessionPrefs.linuxDriver(context).ifEmpty { return false }
        return com.droiddeck.launcher.gpu.DriverPairs.isNonAdreno(com.droiddeck.launcher.gpu.DriverBundle.containing(context, linux, true)?.id)
    }

    fun canDraw(context: Context): Boolean = graphicsAvailable() || openDriverActive(context) || vkBridge(context) ||
        com.droiddeck.launcher.session.VenusComponent.wanted(context)

    fun gpuName(context: Context): String {
        val soc = if (Build.VERSION.SDK_INT >= 31) SocNames.model().ifEmpty { null } else null
        return soc?.let { "$it (${Build.HARDWARE})" } ?: Build.HARDWARE.ifBlank { context.getString(R.string.gpu_this_gpu) }
    }

    fun lowLatencyWifiSafe(): Boolean = Build.VERSION.SDK_INT < 31 || Build.SOC_MODEL != "CQ8725S"
}
