package com.droiddeck.launcher.core

import android.content.Context
import android.os.Build
import com.droiddeck.launcher.R
import java.io.File

object DeviceSupport {
    private val adrenoGpu by lazy {
        File("/sys/class/kgsl/kgsl-3d0").exists() || File("/vendor/lib64/hw/vulkan.adreno.so").exists()
    }

    private val maliGpu by lazy { !adrenoGpu && File("/dev/mali0").exists() }

    fun adreno(): Boolean = adrenoGpu

    fun mali(): Boolean = maliGpu

    fun vkBridge(context: Context): Boolean =
        !adreno() && com.droiddeck.launcher.session.SessionPrefs.vkBridge(context)

    fun canDraw(context: Context): Boolean = adreno() || vkBridge(context)

    fun gpuName(context: Context): String {
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } else null
        return soc?.let { "$it (${Build.HARDWARE})" } ?: Build.HARDWARE.ifBlank { context.getString(R.string.gpu_this_gpu) }
    }

    fun lowLatencyWifiSafe(): Boolean = Build.VERSION.SDK_INT < 31 || Build.SOC_MODEL != "CQ8725S"
}
