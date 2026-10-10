package com.droiddeck.launcher.core

import android.content.Context
import android.os.Build
import com.droiddeck.launcher.R
import java.io.File

/**
 * Whether this device can draw a Linux session at all.
 *
 * The runtime draws with Turnip, an Adreno driver. On Mali, Xclipse and PowerVR the compositor
 * gets no usable Vulkan device and a session comes up as sound over a black screen - a failure
 * with nothing in it to read. The app cannot fix that, so it says so before the download rather
 * than after it. Adreno is recognised by what only Qualcomm's stack has: the KGSL node, or the
 * vendor's own Vulkan driver at its usual path.
 */
object DeviceSupport {
    fun adreno(): Boolean =
        File("/sys/class/kgsl/kgsl-3d0").exists() || File("/vendor/lib64/hw/vulkan.adreno.so").exists()

    /** The chip as the device names it, for the card that explains the refusal. */
    fun gpuName(context: Context): String {
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } else null
        return soc?.let { "$it (${Build.HARDWARE})" } ?: Build.HARDWARE.ifBlank { context.getString(R.string.gpu_this_gpu) }
    }

    /** False on the Odin 3 (CQ8725S): a low-latency Wi-Fi lock makes its WLAN firmware hang, and
     *  that firmware reboots the whole device instead of restarting Wi-Fi. */
    fun lowLatencyWifiSafe(): Boolean = Build.VERSION.SDK_INT < 31 || Build.SOC_MODEL != "CQ8725S"
}
