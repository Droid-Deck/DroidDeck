package com.droiddeck.launcher.gpu

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.session.SessionPrefs

object TpUbwcHint {
    const val ENV = "FD_DEV_FEATURES"
    private const val TAG = "TpUbwcHint"

    @Volatile private var resolved = false
    @Volatile private var cached: String? = null
    @Volatile var activeMode: String? = null
        private set

    // On Adreno 740, TPL1_DBG_ECO_CNTL1.TP_UBWC_FLAG_HINT is shared by every driver on the GPU and must
    // match the system driver. A mismatch corrupts Android UI.
    // Samsung's One UI firmware sets it whatever blob version it reports.
    internal fun features(model: Int, systemDriverVersion: String?, samsung: Boolean = false,
                          mode: String = SessionPrefs.TP_UBWC_AUTO): String? {
        if (model != 740) return null
        if (mode == SessionPrefs.TP_UBWC_ON) return "enable_tp_ubwc_flag_hint=1"
        if (mode == SessionPrefs.TP_UBWC_OFF) return "enable_tp_ubwc_flag_hint=0"
        if (samsung) return "enable_tp_ubwc_flag_hint=1"
        val parts = systemDriverVersion?.split('.') ?: return null
        val blob = parts.getOrNull(1)?.toIntOrNull() ?: return null
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        val on = blob >= 700 || (blob == 676 && patch >= 76)
        return "enable_tp_ubwc_flag_hint=" + if (on) "1" else "0"
    }

    @Synchronized
    fun forThisDevice(context: Context): String? {
        if (resolved) return cached
        val mode = SessionPrefs.tpUbwcMode(context)
        val gpu = GpuInfo.detect()
        val model = gpu.model
        val version = if (model == 740) VulkanInfo.query()["driver_version"] else null
        cached = features(model, version, gpu.oneUi8Gen2, mode)
        activeMode = mode
        resolved = true
        Log.i(TAG, "Adreno $model${if (gpu.oneUi8Gen2) " (One UI)" else ""}, system driver ${version ?: "n/a"}, mode $mode: ${cached ?: "driver default"}")
        return cached
    }

    fun restartRequired(context: Context): Boolean = resolved && activeMode != SessionPrefs.tpUbwcMode(context)

    fun applyToProcess(context: Context) {
        val value = forThisDevice(context) ?: return
        runCatching { android.system.Os.setenv(ENV, value, true) }
            .onFailure { Log.w(TAG, "setenv $ENV failed: ${it.javaClass.simpleName}") }
    }
}
