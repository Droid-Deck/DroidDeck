package com.droiddeck.launcher.session

import android.content.Context
import android.os.Build
import android.util.Log
import com.droiddeck.launcher.BuildConfig
import java.io.File

/** Device-detected defaults and launch evidence for experimental Quest compatibility. */
object QuestExperimental {
    private const val TAG = "QuestExperimental"
    const val DRIVER = "turnip25.1.0-quest1"

    // Quest 2 has an A6xx GPU and cannot use this bundled A7xx driver.
    internal fun supported(model: String, device: String, manufacturer: String): Boolean =
        model.equals("Quest 3", ignoreCase = true) || model.equals("Quest 3S", ignoreCase = true) ||
            ((manufacturer.equals("Oculus", ignoreCase = true) || manufacturer.equals("Meta", ignoreCase = true)) &&
                (device == "eureka" || device == "panther"))

    fun isSupported(): Boolean = supported(Build.MODEL, Build.DEVICE, Build.MANUFACTURER)

    fun initialize(context: Context) {
        if (!isSupported()) return
        val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("questDefaultsV1", false)) {
            // Auto would download an unwrapped display driver and lose the compatibility path.
            // Apply once so deliberate changes made during headset testing remain possible.
            prefs.edit()
                .putString("gpuDriverMode", SessionPrefs.GPU_DRIVERS_MANUAL)
                .putString("androidDriver", DRIVER)
                .putString("linuxDriver", "")
                // The existing HDR option uses SurfaceControl presentation. This is a
                // corruption workaround from upstream issue #245, not a claim of HDR accuracy.
                .putBoolean("hdr.steam", true)
                .putBoolean("questDefaultsV1", true)
                .apply()
        }
        val report = buildString {
            appendLine("DroidDeck Quest Experimental ${BuildConfig.VERSION_NAME}")
            appendLine("package=${context.packageName}")
            appendLine("manufacturer=${Build.MANUFACTURER} model=${Build.MODEL}")
            appendLine("device=${Build.DEVICE} board=${Build.BOARD} hardware=${Build.HARDWARE}")
            appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
            appendLine("abis=${Build.SUPPORTED_ABIS.joinToString()}")
            for (name in listOf("gpu_model", "gpu_chipid")) {
                val value = runCatching { File("/sys/class/kgsl/kgsl-3d0/$name").readText().trim() }
                    .getOrDefault("unavailable")
                appendLine("$name=$value")
            }
            appendLine("driverMode=${SessionPrefs.gpuDriverMode(context)}")
            appendLine("androidDriver=${SessionPrefs.androidDriver(context)}")
            appendLine("steamSurfaceControlWorkaround=${SessionPrefs.hdr(context, "steam")}")
            appendLine("GPUOBJ fallback installed in Android driver and ARM64 libblsession")
            appendLine("Experimental Quest 3/3S compatibility; 2D panel application")
        }
        Log.i(TAG, report)
        runCatching { File(context.filesDir, "quest-startup.txt").writeText(report) }
            .onFailure { Log.w(TAG, "Could not write startup report", it) }
    }
}
