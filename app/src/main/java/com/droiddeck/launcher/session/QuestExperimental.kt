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
    const val DRIVER_A6XX = "turnip26.3.0-quest2"

    internal fun quest2(model: String, device: String, manufacturer: String): Boolean =
        model.equals("Quest 2", ignoreCase = true) ||
            ((manufacturer.equals("Oculus", ignoreCase = true) || manufacturer.equals("Meta", ignoreCase = true)) &&
                device == "hollywood")

    internal fun supported(model: String, device: String, manufacturer: String): Boolean =
        quest2(model, device, manufacturer) || model.equals("Quest 3", ignoreCase = true) || model.equals("Quest 3S", ignoreCase = true) ||
            ((manufacturer.equals("Oculus", ignoreCase = true) || manufacturer.equals("Meta", ignoreCase = true)) &&
                (device == "eureka" || device == "panther"))

    fun isSupported(): Boolean = supported(Build.MODEL, Build.DEVICE, Build.MANUFACTURER)
    fun isQuest2(): Boolean = quest2(Build.MODEL, Build.DEVICE, Build.MANUFACTURER)

    fun initialize(context: Context) {
        if (!isSupported()) return
        val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
        val defaultsKey = if (isQuest2()) "quest2DefaultsV1" else "questDefaultsV1"
        if (!prefs.getBoolean(defaultsKey, false)) {
            // Auto would download an unwrapped display driver and lose the compatibility path.
            // Apply once so deliberate changes made during headset testing remain possible.
            prefs.edit()
                .putString("gpuDriverMode", SessionPrefs.GPU_DRIVERS_MANUAL)
                .putString("androidDriver", if (isQuest2()) DRIVER_A6XX else DRIVER)
                .putString("linuxDriver", "")
                // The existing HDR option uses SurfaceControl presentation. This is a
                // corruption workaround from upstream issue #245, not a claim of HDR accuracy.
                .putBoolean("hdr.steam", true)
                .putBoolean(defaultsKey, true)
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
            appendLine("Experimental Quest 2/3/3S compatibility; 2D panel application")
        }
        Log.i(TAG, report)
        runCatching { File(context.filesDir, "quest-startup.txt").writeText(report) }
            .onFailure { Log.w(TAG, "Could not write startup report", it) }
    }
}
