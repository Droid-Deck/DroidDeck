package com.droiddeck.launcher.gpu

import android.util.Log

object TpUbwcHint {
    const val ENV = "FD_DEV_FEATURES"
    private const val TAG = "TpUbwcHint"

    @Volatile private var resolved = false
    @Volatile private var cached: String? = null

    // On Adreno 740, TPL1_DBG_ECO_CNTL1.TP_UBWC_FLAG_HINT is shared by every driver on the GPU and must
    // match the system driver: blob v6xx leaves it off, newer blobs set it. A mismatch corrupts Android UI.
    internal fun features(model: Int, systemDriverVersion: String?): String? {
        if (model != 740) return null
        val blob = systemDriverVersion?.split('.')?.getOrNull(1)?.toIntOrNull() ?: return null
        return "enable_tp_ubwc_flag_hint=" + if (blob >= 700) "1" else "0"
    }

    @Synchronized
    fun forThisDevice(): String? {
        if (resolved) return cached
        val model = GpuInfo.detect().model
        val version = if (model == 740) VulkanInfo.query()["driver_version"] else null
        cached = features(model, version)
        resolved = true
        Log.i(TAG, "Adreno $model, system driver ${version ?: "n/a"}: ${cached ?: "driver default"}")
        return cached
    }

    fun applyToProcess() {
        val value = forThisDevice() ?: return
        runCatching { android.system.Os.setenv(ENV, value, true) }
            .onFailure { Log.w(TAG, "setenv $ENV failed: ${it.javaClass.simpleName}") }
    }
}
